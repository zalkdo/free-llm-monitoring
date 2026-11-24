# free-llm-monitoring

무료 강의 『Spring AI LLM 비용 모니터링 — Actuator 한 줄로 만드는 토큰 대시보드』 실습 프로젝트

- Spring Boot 3.5 · Spring AI 1.0.x GA · Java 21
- Actuator · Micrometer · Prometheus · Grafana

> 원화 비용 메트릭, 분산 추적(Jaeger), LLM 헬스 체크, 알림 규칙과 장애 재현은
> 『Spring 개발자를 위한 실전 LLM 서비스 개발』 섹션 16 에서 이 프로젝트를 확장해 다룹니다.

## 1. 실행

```bash
# ① 관측 스택 기동 (Prometheus, Grafana)
cd observability
docker compose up -d

# ② 애플리케이션 실행 (OpenAI) — 프로젝트 루트에서
cd ..
export OPENAI_API_KEY=sk-...
./gradlew bootRun

# ③ 부하 발생 → 대시보드 확인
./scripts/load-test.sh
```

Windows (PowerShell):
```powershell
cd observability; docker compose up -d; cd ..
$env:OPENAI_API_KEY="sk-..."
.\gradlew.bat bootRun

# 새 PowerShell 창에서
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

.\scripts\load-test.ps1
```

| 도구 | 주소 |
|---|---|
| Grafana 대시보드 | http://localhost:3000/d/free-llm-monitoring (admin / admin) |
| Prometheus | http://localhost:9090 |
| Actuator | http://localhost:8080/actuator |

### Ollama 로 실행 (무료)
```bash
cd observability && docker compose --profile ollama up -d
docker exec fm-ollama ollama pull llama3.2
docker exec fm-ollama ollama pull nomic-embed-text
cd .. && ./gradlew bootRun --args='--spring.ai.model.chat=ollama --spring.ai.model.embedding=ollama'
```

### Gemini 로 실행
Gemini 의 OpenAI 호환 엔드포인트를 사용합니다 (`application-gemini.yml`). API 키는 Google AI Studio 에서 발급합니다.

```bash
export GEMINI_API_KEY=...
export GEMINI_CHAT_MODEL=gemini-3.5-flash-lite      # 선택 (기본값)
export GEMINI_EMBEDDING_MODEL=gemini-embedding-2    # 선택 (기본값)
./gradlew bootRun --args='--spring.profiles.active=gemini'
```

Windows (PowerShell):
```powershell
$env:GEMINI_API_KEY="..."
$env:GEMINI_CHAT_MODEL="gemini-3.5-flash-lite"   # 선택
.\gradlew.bat bootRun --args='--spring.profiles.active=gemini'
```

- 사용 가능한 모델 조회: `GET /api/models` (chat), `GET /api/models?type=embedding`, `GET /api/models?type=all`
- 요청별 모델 선택: 각 API 에 `&model=<id>` 추가 (예: `/api/chat?message=안녕&model=gemini-3.8-flash`)
- 메트릭의 `gen_ai_system` 라벨은 `openai` 로 기록되며, 모델은 `gen_ai_request_model` 라벨로 구분됩니다.
- 임베딩 모델을 바꾸면 기동 시 FAQ 가 새 모델로 다시 적재됩니다 (요청별 모델 선택은 chat 에만 적용).

주요 Gemini 모델 (출처: Google AI Studio Docs — Models/Deprecations, 2026-11-20 조회 · 계정별 가용 모델은 `/api/models` 로 확인)

| 구분 | 모델 ID | 상태 |
|---|---|---|
| Chat | `gemini-3.8-flash` | Stable |
| Chat | `gemini-3.6-flash` | Stable |
| Chat | `gemini-3.5-flash-lite` | Stable · 신규 프로젝트 권장 (기본값) |
| Chat | `gemini-3.1-flash-lite` | Stable · 2027-05-07 종료 예정 |
| Chat | `gemini-3.1-pro-preview` | Preview |
| Chat | `gemini-3-flash-preview` | Preview |
| Chat | `gemini-4-hydrogen` / `gemini-4-carbon` / `gemini-4-neon` / `gemini-4-argon` | Gemini 4 |
| Chat | `gemini-2.5-flash` / `gemini-2.5-pro` | Deprecated · 기존 사용자만 · 2026-12-01 종료 |
| Embedding | `gemini-embedding-2` | 현행 (기본값) |
| Embedding | `gemini-embedding-001` | 2028-05-14 종료 예정 |

## 2. 강의별 코드 위치

| 강의 | 파일 |
|---|---|
| F-0 실행 확인 | `http/observability.http` 0) |
| F-2 메트릭 | `build.gradle`(actuator), `application.yml`(management), `web/ChatController` |
| F-3 대시보드 | `build.gradle`(micrometer-registry-prometheus), `observability/docker-compose.yml`, `prometheus.yml`, `grafana/dashboards/free-llm-monitoring-dashboard.json` |
| F-4 비용 폭탄 | `web/RagController`(topK), `scripts/load-test.sh` (`TOPK=20`) |

> 요청이 처리되는 클래스·메서드 흐름과 Spring AI 내부 동작은 [docs/spring-ai-request-flow.md](docs/spring-ai-request-flow.md) 참고

## 3. 엔드포인트

| 엔드포인트 | 설명 |
|---|---|
| `GET /api/chat?message=[&model=]` | 단순 채팅 |
| `GET /api/chat/stream?message=[&model=]` | 스트리밍 채팅 |
| `GET /api/rag?question=&topK=4[&model=]` | FAQ 20건 검색 + 답변 (시작 시 FAQ 임베딩·적재) |
| `GET /api/agent?message=[&model=]` | 주문 조회 툴 사용 (모델 호출 2회) |
| `GET /api/models?type=chat\|embedding\|all` | Gemini 사용 가능 모델 목록 (profile `gemini` 전용) |

> `model` 미지정 시 `application.yml`(또는 `application-gemini.yml`)의 기본 모델을 사용합니다.

## 4. 주요 메트릭 (Prometheus 이름)

| Micrometer | Prometheus | 비고 |
|---|---|---|
| `gen_ai.client.operation` | `gen_ai_client_operation_seconds_{count,sum,max,bucket}` | ChatModel·EmbeddingModel 호출 시간 |
| `gen_ai.client.token.usage` | `gen_ai_client_token_usage_total` | 라벨 `gen_ai_token_type`=input/output/total |
| `db.vector.client.operation` | `db_vector_client_operation_seconds_*` | 벡터 검색·저장 시간 |

## 5. 정리
```bash
# 앱: Ctrl + C
cd observability
docker compose down        # 컨테이너 정리
docker compose down -v     # Ollama 모델까지 삭제
```

## 6. [Week 0 검증 대상]
- `./gradlew build` 로 컴파일 확인 (제작 환경에서는 의존성 저장소 접근 불가로 미확인)
- `/actuator/prometheus` 실제 출력으로 메트릭·라벨 이름 확인
- Docker 이미지 태그 최신 안정판 확인
