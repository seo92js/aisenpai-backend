package com.seojs.aisenpai_backend.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Service
public class AiService {

    public String callAiChat(String apiKey, String systemPrompt, String userPrompt, String model, Double temperature) {
        return callAiChat(apiKey, systemPrompt, userPrompt, model, temperature, List.of(), List.of());
    }

    public String callAiChat(String apiKey, String systemPrompt, String userPrompt, String model, Double temperature, List<Advisor> advisors, List<ToolCallback> tools) {
        String actualModel = (model != null) ? model : "gpt-4o-mini";
        double actualTemp = (temperature != null) ? temperature : 0.7;

        log.info("AI review started with model: {}", actualModel);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .model(actualModel)
                .temperature(actualTemp)
                .build();

        OpenAiChatModel customModel = OpenAiChatModel.builder()
                .options(options)
                .build();

        ChatClient customClient = ChatClient.builder(customModel).build();

        ChatClient.ChatClientRequestSpec spec = customClient.prompt()
                .system(systemPrompt)
                .user(userPrompt);

        if (advisors != null && !advisors.isEmpty()) {
            spec.advisors(advisors.toArray(new Advisor[0]));
        }
        if (tools != null && !tools.isEmpty()) {
            spec.tools(tools);
        }

        String result = spec.call().content();

        log.info("AI review completed");
        return result;
    }

    public <T> T callAiChatWithStructuredOutput(String apiKey, String systemPrompt, String userPrompt, String model, Double temperature, Class<T> responseType) {
        return callAiChatWithStructuredOutput(apiKey, systemPrompt, userPrompt, model, temperature, responseType, List.of(), List.of());
    }

    public <T> T callAiChatWithStructuredOutput(String apiKey, String systemPrompt, String userPrompt, String model, Double temperature, Class<T> responseType, List<Advisor> advisors, List<ToolCallback> tools) {
        String actualModel = (model != null) ? model : "gpt-4o-mini";
        double actualTemp = (temperature != null) ? temperature : 0.7;

        log.info("AI review with structured output started with model: {}", actualModel);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .model(actualModel)
                .temperature(actualTemp)
                .build();

        OpenAiChatModel customModel = OpenAiChatModel.builder()
                .options(options)
                .build();

        ChatClient customClient = ChatClient.builder(customModel).build();

        ChatClient.ChatClientRequestSpec spec = customClient.prompt()
                .system(systemPrompt)
                .user(userPrompt);

        if (advisors != null && !advisors.isEmpty()) {
            spec.advisors(advisors.toArray(new Advisor[0]));
        }
        if (tools != null && !tools.isEmpty()) {
            spec.tools(tools);
        }

        T result = spec.call().entity(responseType);

        log.info("AI review with structured output completed");
        return result;
    }

    public boolean validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        try {
            // 최소 토큰으로 API 호출 시도
            callAiChat(apiKey, "Validation", "ping", "gpt-4o-mini", 0.1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
