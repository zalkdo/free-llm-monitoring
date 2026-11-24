package com.example.freemonitoring.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gemini 3 / 4 는 툴 호출 응답에 thought_signature 를 붙여 주고, 다음 요청(툴 결과 전달)에
 * 그 서명을 그대로 돌려받아야 한다. 없으면 400 "Function call is missing a thought_signature".
 * Spring AI 1.0 의 OpenAI 클라이언트는 이 비표준 필드(extra_content)를 버리므로
 * RestClient 인터셉터에서 응답의 서명을 tool_call id 별로 저장했다가 요청에 다시 넣는다.
 * 저장된 서명이 없으면 Google 문서의 우회용 더미 서명을 넣는다.
 * (스트리밍은 WebClient 를 쓰므로 대상이 아니다. 툴 호출은 /api/agent 의 call() 에서만 사용)
 */
@Profile("gemini")
@Configuration
public class GeminiThoughtSignatureConfig {

    @Bean
    public RestClientCustomizer geminiThoughtSignatureCustomizer(ObjectMapper objectMapper) {
        return builder -> builder.requestInterceptor(new ThoughtSignatureInterceptor(objectMapper));
    }

    static class ThoughtSignatureInterceptor implements ClientHttpRequestInterceptor {

        private static final Logger log = LoggerFactory.getLogger(ThoughtSignatureInterceptor.class);
        private static final String DUMMY_SIGNATURE = "skip_thought_signature_validator";
        private static final int MAX_CACHE_SIZE = 1000;

        private final ObjectMapper objectMapper;
        private final Map<String, String> signatures = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                        return size() > MAX_CACHE_SIZE;
                    }
                });

        ThoughtSignatureInterceptor(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            if (!request.getURI().getPath().endsWith("/chat/completions")) {
                return execution.execute(request, body);
            }
            ClientHttpResponse response = execution.execute(request, addSignatures(body));
            if (!response.getStatusCode().is2xxSuccessful()) {
                return response;
            }
            byte[] responseBody = response.getBody().readAllBytes();
            saveSignatures(responseBody);
            return new BufferedResponse(response, responseBody);
        }

        /** 요청의 assistant tool_calls 에 서명을 채운다. 서명은 메시지의 첫 번째 tool_call 에 필수. */
        private byte[] addSignatures(byte[] body) {
            try {
                JsonNode root = objectMapper.readTree(body);
                boolean changed = false;
                for (JsonNode message : root.path("messages")) {
                    JsonNode toolCalls = message.path("tool_calls");
                    for (int i = 0; i < toolCalls.size(); i++) {
                        JsonNode toolCall = toolCalls.get(i);
                        if (toolCall.path("extra_content").path("google").hasNonNull("thought_signature")) {
                            continue;
                        }
                        String signature = signatures.get(toolCall.path("id").asText());
                        if (signature == null && i == 0) {
                            signature = DUMMY_SIGNATURE;
                        }
                        if (signature != null) {
                            ((ObjectNode) toolCall).putObject("extra_content")
                                    .putObject("google")
                                    .put("thought_signature", signature);
                            changed = true;
                        }
                    }
                }
                return changed ? objectMapper.writeValueAsBytes(root) : body;
            } catch (IOException | RuntimeException e) {
                log.warn("thought_signature 주입 실패 — 원본 요청으로 전송합니다: {}", e.getMessage());
                return body;
            }
        }

        /** 응답의 tool_calls[].extra_content.google.thought_signature 를 tool_call id 별로 저장한다. */
        private void saveSignatures(byte[] responseBody) {
            try {
                for (JsonNode choice : objectMapper.readTree(responseBody).path("choices")) {
                    for (JsonNode toolCall : choice.path("message").path("tool_calls")) {
                        JsonNode signature = toolCall.path("extra_content").path("google").path("thought_signature");
                        if (signature.isTextual() && toolCall.hasNonNull("id")) {
                            signatures.put(toolCall.get("id").asText(), signature.asText());
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.debug("thought_signature 추출 실패: {}", e.getMessage());
            }
        }
    }

    /** 이미 읽은 응답 본문을 다시 읽을 수 있게 감싼다. */
    static class BufferedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
