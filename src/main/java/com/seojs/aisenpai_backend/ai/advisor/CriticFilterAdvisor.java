package com.seojs.aisenpai_backend.ai.advisor;

import com.seojs.aisenpai_backend.ai.service.AiService;
import com.seojs.aisenpai_backend.github.dto.AiReviewResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class CriticFilterAdvisor implements CallAdvisor {

    private final AiService aiService;
    private final String apiKey;
    private final String criticSystemPrompt;
    private final ObjectMapper objectMapper;

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);

        try {
            if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
                return response;
            }
            AssistantMessage outputMessage = response.chatResponse().getResult().getOutput();
            if (outputMessage == null) {
                return response;
            }
            String firstStepRawText = outputMessage.getText();
            if (firstStepRawText == null || firstStepRawText.isBlank()) {
                return response;
            }

            log.info("Running 2nd step Critic filter via Advisor");
            String cleanedJson = cleanJson(firstStepRawText);
            AiReviewResponseDto firstStepDto = objectMapper.readValue(cleanedJson, AiReviewResponseDto.class);
            String reviewJsonForCritic = objectMapper.writeValueAsString(firstStepDto);

            AiReviewResponseDto criticOutputDto = aiService.callAiChatWithStructuredOutput(
                    apiKey, criticSystemPrompt, reviewJsonForCritic, "gpt-4o-mini", 0.1, AiReviewResponseDto.class
            );

            if (criticOutputDto != null) {
                String filteredJson = objectMapper.writeValueAsString(criticOutputDto);
                ChatResponse newChatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(filteredJson))));
                return response.mutate().chatResponse(newChatResponse).build();
            }
        } catch (Exception e) {
            log.warn("Failed to execute 2nd step Critic filter in Advisor. Falling back to original review. Error: {}", e.getMessage());
        }

        return response;
    }

    private String cleanJson(String rawText) {
        String sanitized = rawText.trim();
        if (sanitized.startsWith("```json")) {
            sanitized = sanitized.substring(7);
        } else if (sanitized.startsWith("```")) {
            sanitized = sanitized.substring(3);
        }
        if (sanitized.endsWith("```")) {
            sanitized = sanitized.substring(0, sanitized.length() - 3);
        }
        return sanitized.trim();
    }

    @Override
    public String getName() {
        return "CriticFilterAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
