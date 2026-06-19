package com.seojs.aisenpai_backend.ai.advisor;

import com.seojs.aisenpai_backend.ai.service.AiService;
import com.seojs.aisenpai_backend.github.dto.AiReviewResponseDto;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CriticFilterAdvisorTest {

    @Mock
    private AiService aiService;

    @Mock
    private CallAdvisorChain chain;

    @Mock
    private ChatClientRequest request;

    @Mock
    private ChatClientResponse originalResponse;

    @Mock
    private ChatClientResponse.Builder mutatedResponseBuilder;

    @Mock
    private ChatClientResponse mutatedResponse;

    private ObjectMapper objectMapper;
    private CriticFilterAdvisor advisor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        advisor = new CriticFilterAdvisor(aiService, "test-api-key", "critic-prompt", objectMapper);
    }

    @Test
    void adviseCall_FiltersCorrectly() throws Exception {
        // given
        AiReviewResponseDto originalDto = AiReviewResponseDto.builder()
                .generalReview("Good")
                .comments(List.of())
                .build();
        String originalJson = objectMapper.writeValueAsString(originalDto);

        AssistantMessage originalMessage = new AssistantMessage(originalJson);
        Generation generation = new Generation(originalMessage);
        ChatResponse originalChatResponse = new ChatResponse(List.of(generation));

        when(chain.nextCall(request)).thenReturn(originalResponse);
        when(originalResponse.chatResponse()).thenReturn(originalChatResponse);

        AiReviewResponseDto filteredDto = AiReviewResponseDto.builder()
                .generalReview("Filtered Good")
                .comments(List.of())
                .build();

        when(aiService.callAiChatWithStructuredOutput(
                eq("test-api-key"),
                eq("critic-prompt"),
                anyString(),
                eq("gpt-4o-mini"),
                eq(0.1),
                eq(AiReviewResponseDto.class)
        )).thenReturn(filteredDto);

        when(originalResponse.mutate()).thenReturn(mutatedResponseBuilder);
        when(mutatedResponseBuilder.chatResponse(any(ChatResponse.class))).thenReturn(mutatedResponseBuilder);
        when(mutatedResponseBuilder.build()).thenReturn(mutatedResponse);

        // when
        ChatClientResponse finalResponse = advisor.adviseCall(request, chain);

        // then
        assertNotNull(finalResponse);
        verify(aiService).callAiChatWithStructuredOutput(
                eq("test-api-key"),
                eq("critic-prompt"),
                contains("\"generalReview\":\"Good\""),
                eq("gpt-4o-mini"),
                eq(0.1),
                eq(AiReviewResponseDto.class)
        );
        verify(originalResponse).mutate();
        verify(mutatedResponseBuilder).chatResponse(argThat(chatResponse -> {
            String updatedText = chatResponse.getResult().getOutput().getText();
            return updatedText.contains("Filtered Good");
        }));
    }

    @Test
    void adviseCall_FallsBackOnException() throws Exception {
        // given
        AiReviewResponseDto originalDto = AiReviewResponseDto.builder()
                .generalReview("Good")
                .comments(List.of())
                .build();
        String originalJson = objectMapper.writeValueAsString(originalDto);

        AssistantMessage originalMessage = new AssistantMessage(originalJson);
        Generation generation = new Generation(originalMessage);
        ChatResponse originalChatResponse = new ChatResponse(List.of(generation));

        when(chain.nextCall(request)).thenReturn(originalResponse);
        when(originalResponse.chatResponse()).thenReturn(originalChatResponse);

        when(aiService.callAiChatWithStructuredOutput(
                anyString(), anyString(), anyString(), anyString(), anyDouble(), any()
        )).thenThrow(new RuntimeException("API error"));

        // when
        ChatClientResponse finalResponse = advisor.adviseCall(request, chain);

        // then
        // should return original response and not throw exception
        assertSame(originalResponse, finalResponse);
    }
}
