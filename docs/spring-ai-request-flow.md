# Spring AI 요청 처리 흐름

이 프로젝트에서 요청이 어떤 클래스와 메서드를 거쳐 응답되는지, 그리고 Spring AI(1.0.0)가 내부에서 어떻게 동작하는지 정리한 문서입니다.

모든 엔드포인트는 **Controller → ChatClient → Advisor 체인 → ChatModel → OpenAiApi → HTTP 전송** 순서로 처리됩니다. 엔드포인트마다 다른 것은 중간에 끼는 단계뿐입니다.
클래스와 메서드 이름은 실제 오류 스택트레이스에 찍힌 호출 경로와 맞춰 두었습니다.

---

## 0. 전체 구조

```
 브라우저 / curl
      │  GET /api/...
      ▼
┌──────────────────────────────────────────────────────────────────────┐
│ ① Spring MVC   DispatcherServlet → XxxController.method()            │  우리 코드
│                  └ ModelOptions.apply()  (?model= 이 있으면 옵션 지정) │
├──────────────────────────────────────────────────────────────────────┤
│ ② ChatClient   DefaultChatClient                                     │  ◀ 관측: spring.ai.chat.client
│                  .prompt() → .user() / .tools() / .advisors()        │
│                  .call() / .stream() → .content()                    │
├──────────────────────────────────────────────────────────────────────┤
│ ③ Advisor 체인 DefaultAroundAdvisorChain                              │  ◀ 관측: spring.ai.advisor
│                  [QuestionAnswerAdvisor]  ← /api/rag 에서만           │
│                  ChatModelCallAdvisor / ChatModelStreamAdvisor (맨 끝)│
├──────────────────────────────────────────────────────────────────────┤
│ ④ ChatModel    OpenAiChatModel.call() / stream()                     │  ◀ 관측: gen_ai.client.operation
│                  옵션 병합 → 재시도(RetryTemplate) → 툴 실행 루프     │         gen_ai.client.token.usage
├──────────────────────────────────────────────────────────────────────┤
│ ⑤ 저수준 API   OpenAiApi.chatCompletionEntity()  (RestClient)        │
│                OpenAiApi.chatCompletionStream()  (WebClient, SSE)    │
│                  └ [ThoughtSignatureInterceptor] ← gemini, RestClient만│
└──────────────────────────────────────────────────────────────────────┘
      │  POST {base-url}{completions-path}
      ▼
   OpenAI:  https://api.openai.com/v1/chat/completions
   Gemini:  https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
```

Gemini는 OpenAI 호환 방식으로 붙였기 때문에 ④와 ⑤에서 OpenAI와 **같은 클래스**를 씁니다. 차이는 `application-gemini.yml`의 주소, 키, 모델 설정과 ⑤의 인터셉터뿐입니다.

---

## 1. `GET /api/chat?message=` 단순 호출

```
ChatController.chat(message, model)
 └ ModelOptions.apply(chatClient.prompt(), model)
     └ (model 있음) spec.options(ToolCallingChatOptions{model})
 └ .user(message).call().content()
     │
     ▼ DefaultChatClient$DefaultCallResponseSpec.content()
       └ doGetObservableChatClientResponse()        ── 관측 시작 [spring.ai.chat.client]
           └ Prompt 생성 = defaultSystem("WeLab 상담원…") + user(message) + options
           └ DefaultAroundAdvisorChain.nextCall()
               └ ChatModelCallAdvisor.adviseCall()
                   └ OpenAiChatModel.call(prompt)
                       ├ buildRequestPrompt(): 요청 옵션과 yml 기본 옵션 병합
                       │    (model, temperature 0.3 …)
                       └ internalCall()                ── 관측 [gen_ai.client.operation]
                           └ RetryTemplate.execute()   (일시 오류는 재시도, 4xx는 즉시 실패)
                               └ OpenAiApi.chatCompletionEntity(ChatCompletionRequest)
                                   └ RestClient POST /chat/completions
                           ◀ ChatCompletion JSON
                           └ ChatResponse 변환 (텍스트 + Usage 토큰 수)
                                └ 토큰 메트릭 기록 [gen_ai.client.token.usage]
     ◀ ChatResponse → .content() 로 텍스트만 추출 → String 응답
```

---

## 2. `GET /api/chat/stream` 스트리밍 (1번과 다른 부분만)

```
ChatController.stream()
 └ .stream().content()                          → Flux<String> 반환 (아직 호출 안 함)
     ▼ Spring MVC 가 Flux 를 구독하는 순간 실행 시작
     DefaultStreamResponseSpec → advisorChain.nextStream()
       └ ChatModelStreamAdvisor → OpenAiChatModel.stream()
           └ OpenAiApi.chatCompletionStream()   ← WebClient (RestClient 아님)
               POST /chat/completions  {"stream":true, "stream_options":{"include_usage":true}}
               ◀ data:{chunk} data:{chunk} … data:{usage} data:[DONE]
           └ 조각(chunk)마다 Flux 로 흘려보냄, 마지막 usage 조각으로 토큰 메트릭 기록
     ◀ Spring MVC(ReactiveTypeHandler)가 조각마다 "data:…" 로 브라우저에 전송
        (charset=UTF-8 은 server.servlet.encoding.force-response 로 강제)
```

- 스트리밍에서도 토큰 사용량을 집계하려면 `stream-usage: true` 설정이 필요합니다.
- 이 경로는 WebClient를 쓰므로 thought signature 인터셉터가 적용되지 않습니다. 툴 호출이 없는 엔드포인트라 문제는 없습니다.

---

## 3. `GET /api/rag?question=&topK=` RAG

```
RagController.ask()
 └ QuestionAnswerAdvisor 생성 (SearchRequest topK, threshold 0.0)
 └ .advisors(qaAdvisor).user(question).call().content()
     ▼ DefaultAroundAdvisorChain.nextCall()
       ┌─ QuestionAnswerAdvisor.before()                     ── 관측 [spring.ai.advisor]
       │   └ SimpleVectorStore.similaritySearch(question)     ── 관측 [db.vector.client.operation]
       │       ├ OpenAiEmbeddingModel.embed(question)          ── 관측 [gen_ai.client.operation: embeddings]
       │       │   └ OpenAiApi.embeddings() → POST /embeddings  → float[] 벡터
       │       └ 메모리 안의 FAQ 20건 벡터와 코사인 유사도 계산 → 상위 topK 건
       │   └ user 메시지 재작성:
       │       "질문 + Context information is below. --- (FAQ topK건) ---"
       ├─ ChatModelCallAdvisor → OpenAiChatModel.call()  (1번과 동일)
       └─ QuestionAnswerAdvisor.after(): 검색된 문서를 응답 메타데이터에 첨부
```

- 요청 1건당 모델 호출은 **임베딩 1회 + 채팅 1회**입니다.
- topK를 4에서 20으로 올리면 프롬프트에 붙는 FAQ가 5배가 되어 입력 토큰이 크게 늘어납니다. 이것이 F-4 "비용 폭탄" 실습의 원리입니다.

### 앱 기동 시 FAQ 적재 (RAG 사전 단계)

```
FaqDataLoader.run()  (ApplicationRunner)
 └ SimpleVectorStore.add(20개 Document)
     └ OpenAiEmbeddingModel.call() → POST /embeddings (FAQ 20건)
     └ 벡터를 메모리 Map 에 보관 (재시작 시 사라지고 다시 적재)
```

---

## 4. `GET /api/agent?message=` 툴 호출 (모델 호출 2회)

```
AgentController.ask()
 └ .tools(orderTools)
     └ @Tool 메서드 스캔 → MethodToolCallback 2개 생성
        (getOrderStatus, calculateShippingFee → 이름·설명·파라미터 JSON Schema)
 └ .call().content()
     ▼ ChatModelCallAdvisor → OpenAiChatModel.call()
       │
       │ ── 1차 호출 ──────────────────────────────────────────────
       ├ internalCall(prompt)
       │   └ POST /chat/completions  { messages:[system,user], tools:[2개 정의] }
       │   ◀ { tool_calls:[{id, name:"getOrderStatus", arguments:{"orderId":"A1001"}}] }
       │     ※ Gemini: 응답의 thought_signature 를 인터셉터가 id 별로 저장
       │
       ├ toolExecutionEligibilityPredicate: "툴 호출 요청이 왔으니 실행해야 함"
       ├ DefaultToolCallingManager.executeToolCalls()     ── 관측 [spring.ai.tool]
       │   └ MethodToolCallback.call(arguments JSON)
       │       └ OrderTools.getOrderStatus("A1001")  (300ms 지연)  → "배송 중 (…)"
       │   └ 대화 이력 = [system, user, assistant(tool_calls), tool(결과)]
       │
       │ ── 2차 호출 (재귀) ───────────────────────────────────────
       └ internalCall(새 Prompt = 위 이력)
           └ POST /chat/completions
               ※ Gemini: 인터셉터가 assistant.tool_calls 에 thought_signature 재주입
                 (없으면 400 "missing a thought_signature")
           ◀ { content:"주문 A1001은 현재 배송 중이며…" }  → 툴 호출 없음 → 루프 종료
 ◀ 최종 텍스트 응답
```

모델이 툴을 다시 요청하면 이 루프가 반복되고, 그만큼 모델 호출 횟수가 늘어납니다. 대시보드의 "모델 호출 수"가 요청 수보다 많게 나오는 것은 이 때문입니다.

---

## 5. 관측(Observation) 계층과 메트릭

```
spring.ai.chat.client          ← ChatClient 1회 = HTTP 요청 1건
 └ spring.ai.advisor           ← Advisor 마다
     ├ db.vector.client.operation        (RAG 검색)
     │   └ gen_ai.client.operation       (embeddings)
     └ gen_ai.client.operation           (chat, agent 는 2번)
         ├ gen_ai.client.token.usage     (input / output / total)
         └ spring.ai.tool                (툴 실행)
```

관측값은 Micrometer가 받아 `/actuator/prometheus`로 내보내고, Prometheus가 수집한 뒤 Grafana에서 그래프로 보여줍니다.
`?model=`로 모델을 바꾸면 메트릭의 `gen_ai_request_model` 라벨로 구분됩니다.

---

## 정리: 엔드포인트별 차이

| 엔드포인트 | 추가 Advisor | 모델 호출 | HTTP 클라이언트 | Gemini 인터셉터 |
|---|---|---|---|---|
| `/api/chat` | 없음 | chat 1회 | RestClient | 통과 (툴 없음) |
| `/api/chat/stream` | 없음 | chat 1회 (스트리밍) | WebClient | 미적용 |
| `/api/rag` | QuestionAnswerAdvisor | embedding 1회 + chat 1회 | RestClient | 통과 |
| `/api/agent` | 없음 (툴 루프) | chat 2회 이상 | RestClient | **서명 저장·주입** |

## 관련 소스

| 구분 | 파일 |
|---|---|
| Controller | `web/ChatController.java`, `web/RagController.java`, `web/AgentController.java` |
| 모델 선택 | `web/ModelOptions.java`, `web/GeminiModelController.java` |
| 설정 | `config/ChatConfig.java`, `config/GeminiThoughtSignatureConfig.java` |
| RAG 데이터 | `rag/FaqDataLoader.java` |
| 툴 | `tools/OrderTools.java` |
| 프로필 설정 | `application.yml`, `application-gemini.yml` |
