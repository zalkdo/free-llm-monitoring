package com.example.freemonitoring.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.util.StringUtils;

/**
 * 요청 단위 모델 선택: model 파라미터가 있으면 해당 모델로, 없으면 기본 모델(application.yml)로 호출한다.
 * ToolCallingChatOptions 를 쓰는 이유: 일반 ChatOptions 를 넘기면 .tools(...) 가 프롬프트에 병합되지 않는다.
 * 선택한 모델은 gen_ai_request_model 라벨로 기록되어 대시보드에서 모델별로 비교할 수 있다.
 */
final class ModelOptions {

    private ModelOptions() {
    }

    static ChatClient.ChatClientRequestSpec apply(ChatClient.ChatClientRequestSpec spec, String model) {
        if (!StringUtils.hasText(model)) {
            return spec;
        }
        return spec.options(ToolCallingChatOptions.builder().model(model).build());
    }
}
