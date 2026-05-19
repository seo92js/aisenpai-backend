package com.seojs.aisenpai_backend.ai.service;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AiServiceTest {

    @Autowired
    AiService aiService;

    @Disabled
    @Test
    void callAiChat() {
        String systemPrompt = "당신은 유능한 한국어 날씨 안내 챗봇입니다";
        String userPrompt = "지금 서울 날씨";
        String result = aiService.callAiChat("key 넣어야 함", systemPrompt, userPrompt, "gpt-4o-mini", 0.7);
        assertNotNull(result);
        assertFalse(result.isBlank());
    }
}
