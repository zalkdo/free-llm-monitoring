package com.example.freemonitoring.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini 에서 현재 API 키로 호출 가능한 모델 목록 조회 (profile gemini 전용).
 * 반환된 id 를 그대로 /api/chat?model= 등에 넣어 사용한다.
 *   type=chat      : generateContent 지원 모델
 *   type=embedding : embedContent 지원 모델
 *   type=all       : 전체
 */
@Profile("gemini")
@RestController
@RequestMapping("/api/models")
public class GeminiModelController {

    private final RestClient restClient;
    private final String apiKey;
    private final String modelsUrl;

    public GeminiModelController(RestClient.Builder restClientBuilder,
                                 @Value("${gemini.api-key}") String apiKey,
                                 @Value("${gemini.models-url}") String modelsUrl) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey;
        this.modelsUrl = modelsUrl;
    }

    @GetMapping
    public List<ModelInfo> list(@RequestParam(defaultValue = "chat") String type) {
        String method = switch (type) {
            case "chat" -> "generateContent";
            case "embedding" -> "embedContent";
            case "all" -> null;
            default -> throw new IllegalArgumentException("type 은 chat | embedding | all 중 하나여야 합니다: " + type);
        };

        List<ModelInfo> result = new ArrayList<>();
        String pageToken = null;
        do {
            UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(modelsUrl).queryParam("pageSize", 1000);
            if (StringUtils.hasText(pageToken)) {
                uri.queryParam("pageToken", pageToken);
            }
            ModelsResponse response = restClient.get()
                    .uri(uri.build().toUri())
                    .header("x-goog-api-key", apiKey)
                    .retrieve()
                    .body(ModelsResponse.class);
            if (response == null || response.models() == null) {
                break;
            }
            response.models().stream()
                    .filter(m -> method == null
                            || (m.supportedGenerationMethods() != null && m.supportedGenerationMethods().contains(method)))
                    .map(m -> new ModelInfo(m.name().replaceFirst("^models/", ""), m.displayName(),
                            m.inputTokenLimit(), m.outputTokenLimit()))
                    .forEach(result::add);
            pageToken = response.nextPageToken();
        } while (StringUtils.hasText(pageToken));
        return result;
    }

    public record ModelInfo(String id, String displayName, Integer inputTokenLimit, Integer outputTokenLimit) {
    }

    record ModelsResponse(List<GeminiModel> models, String nextPageToken) {
    }

    record GeminiModel(String name, String displayName, Integer inputTokenLimit, Integer outputTokenLimit,
                       List<String> supportedGenerationMethods) {
    }
}
