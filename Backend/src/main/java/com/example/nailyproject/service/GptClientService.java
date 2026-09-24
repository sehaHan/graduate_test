package com.example.nailyproject.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions API 공용 클라이언트.
 * Gemini를 쓰던 ChatService/FingerDesignPlanService/RefineService/TextureExtractService가
 * 전부 이걸 통해 GPT를 호출한다 (텍스트 전용 프롬프트/JSON 생성 용도 — 이미지 "생성"이 아니라
 * 참고 이미지를 "읽는" 입력으로만 vision을 쓴다).
 */
@Service
@RequiredArgsConstructor
public class GptClientService {

    private final WebClient.Builder webClientBuilder;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${openai.api.model:gpt-4.1}")
    private String model;

    /**
     * system 프롬프트 + 대화 메시지(role은 "user"/"assistant")를 보내고 응답 텍스트를 그대로 돌려준다.
     *
     * @param jsonObjectMode true면 응답을 JSON 오브젝트로 강제한다(response_format=json_object).
     *                       OpenAI의 이 모드는 최상위가 반드시 오브젝트여야 하므로, 최상위가
     *                       배열이어야 하는 호출(TextureExtractService)은 false로 두고 프롬프트
     *                       지시에만 의존해야 한다.
     */
    public String chat(String systemPrompt, List<Map<String, Object>> messages,
                        int maxCompletionTokens, boolean jsonObjectMode) {
        List<Map<String, Object>> fullMessages = new ArrayList<>();
        fullMessages.add(Map.of("role", "system", "content", systemPrompt));
        fullMessages.addAll(messages);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", fullMessages);
        requestBody.put("max_completion_tokens", maxCompletionTokens);
        if (jsonObjectMode) {
            requestBody.put("response_format", Map.of("type", "json_object"));
        }

        JsonNode responseNode = callWithRetry(requestBody);
        return responseNode.path("choices").get(0).path("message").path("content").asText();
    }

    /** 텍스트 하나만 보내는 단순 호출 (이미지 없음). */
    public String chat(String systemPrompt, String userText, int maxCompletionTokens, boolean jsonObjectMode) {
        return chat(systemPrompt, List.of(Map.of("role", "user", "content", userText)),
                maxCompletionTokens, jsonObjectMode);
    }

    /** 이미지(base64) + 텍스트를 함께 보내는 호출 (참고 이미지 분석용, 이미지 생성이 아님). */
    public String chatWithImage(String systemPrompt, String userText, String imageBase64,
                                 String imageMimeType, int maxCompletionTokens, boolean jsonObjectMode) {
        List<Map<String, Object>> content = List.of(
                Map.of("type", "text", "text", userText),
                Map.of("type", "image_url", "image_url",
                        Map.of("url", "data:" + imageMimeType + ";base64," + imageBase64))
        );
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", content));
        return chat(systemPrompt, messages, maxCompletionTokens, jsonObjectMode);
    }

    /**
     * GPT 호출. 429(요청 한도 초과)나 5xx(서버 오류)면 잠깐 대기 후 최대 2회 재시도.
     */
    private JsonNode callWithRetry(Map<String, Object> requestBody) {
        WebClient webClient = webClientBuilder.build();
        int maxAttempts = 3;
        long backoffMillis = 1500;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return webClient.post()
                        .uri(apiUrl)
                        .header("Authorization", "Bearer " + apiKey.trim())
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();
            } catch (WebClientResponseException e) {
                int statusCode = e.getStatusCode().value();
                boolean isRetryable = statusCode == 429 || statusCode >= 500;
                boolean hasAttemptsLeft = attempt < maxAttempts;

                System.err.println("[GptClientService] OpenAI API 호출 실패 (시도 " + attempt + "/" + maxAttempts + "): "
                        + e.getStatusCode() + " " + e.getResponseBodyAsString());

                if (isRetryable && hasAttemptsLeft) {
                    try {
                        Thread.sleep(backoffMillis * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }

                if (isRetryable) {
                    throw new IllegalStateException("지금 AI 서버가 혼잡해서 응답이 지연되고 있어요. 잠시 후 다시 시도해 주세요.");
                }
                throw new IllegalStateException("AI 응답을 받아오지 못했어요. 잠시 후 다시 시도해 주세요.");
            }
        }
        throw new IllegalStateException("AI 응답을 받아오지 못했어요. 잠시 후 다시 시도해 주세요.");
    }
}
