package com.prioritize.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prioritize.config.AiProperties;
import com.prioritize.exception.ApiException;

@Component
public class LlmClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmClient(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    public void warmup() {
        Map<String, Object> body = new HashMap<>();
        body.put("model", aiProperties.getModel());
        body.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        body.put("temperature", 0.0);
        body.put("max_tokens", 1);
        applyKeepAlive(body);
        sendChatRequest(body);
    }

    public String chat(List<Map<String, String>> messages) {
        List<Map<String, Object>> converted = new ArrayList<>();
        for (Map<String, String> message : messages) {
            converted.add(new HashMap<>(message));
        }
        LlmCompletion completion = complete(converted, null);
        if (completion.content() == null || completion.content().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI provider returned an empty response");
        }
        return completion.content().trim();
    }

    public LlmCompletion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", aiProperties.getModel());
        body.put("messages", messages);
        body.put("temperature", 0.4);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        applyKeepAlive(body);
        AssistantAuditLog.write("model_request", body);
        long started = System.nanoTime();
        try {
            String response = sendChatRequest(body);
            AssistantAuditLog.write("model_response", Map.of("body", response,
                    "durationMs", (System.nanoTime() - started) / 1_000_000));
            return parseCompletion(response);
        } catch (RuntimeException ex) {
            AssistantAuditLog.write("model_error", Map.of("errorType", ex.getClass().getName(),
                    "message", ex instanceof ApiException ? ex.getMessage() : "AI request failed",
                    "durationMs", (System.nanoTime() - started) / 1_000_000));
            throw ex;
        }
    }

    private void applyKeepAlive(Map<String, Object> body) {
        String keepAlive = aiProperties.getKeepAlive();
        if (keepAlive != null && !keepAlive.isBlank() && isLikelyOllama()) {
            body.put("keep_alive", keepAlive);
        }
    }

    private boolean isLikelyOllama() {
        String baseUrl = aiProperties.getBaseUrl().toLowerCase();
        return baseUrl.contains("11434") || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1");
    }

    private String sendChatRequest(Map<String, Object> body) {
        String baseUrl = aiProperties.getBaseUrl().replaceAll("/$", "");
        String url = baseUrl + "/chat/completions";

        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiProperties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "AI provider returned an error (HTTP " + response.statusCode() + ")");
            }

            return response.body();
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI request was interrupted");
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach the AI provider");
        }
    }

    private LlmCompletion parseCompletion(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").isNull() ? null : message.path("content").asText().trim();

            List<LlmToolCall> toolCalls = new ArrayList<>();
            JsonNode toolCallsNode = message.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode call : toolCallsNode) {
                    String id = call.path("id").asText();
                    String name = call.path("function").path("name").asText();
                    String arguments = call.path("function").path("arguments").asText("{}");
                    toolCalls.add(new LlmToolCall(id, name, arguments));
                }
            }

            if ((content == null || content.isBlank()) && toolCalls.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI provider returned an empty response");
            }

            return new LlmCompletion(content, toolCalls);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI provider returned an invalid response");
        }
    }
}
