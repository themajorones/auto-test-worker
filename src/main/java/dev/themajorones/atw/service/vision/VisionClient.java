package dev.themajorones.atw.service.vision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class VisionClient implements VisionAnalyzer {

    private final RestClient restClient;
    private final VisionProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger geminiIndex = new AtomicInteger();
    private final AtomicInteger openRouterIndex = new AtomicInteger();

    public VisionClient(RestClient.Builder restClientBuilder, VisionProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    @Override
    public VisionResult analyze(Path screenshotPng, String objective, String uiContext) {
        String prompt = """
            You are an Android UI screenshot annotator. Return concise JSON text only.
            Objective: %s
            UI tree context:
            %s

            Describe visible non-text icons, disabled/enabled visual state, obvious errors, and screen-level context.
            Keep it short and do not choose the next action.
            """.formatted(nullToEmpty(objective), truncate(nullToEmpty(uiContext), 5_000));
        try {
            return analyzeWithGemini(screenshotPng, prompt);
        } catch (RuntimeException ex) {
            try {
                return analyzeWithOpenRouter(screenshotPng, prompt);
            } catch (RuntimeException fallbackEx) {
                return new VisionResult("none", "Vision unavailable: " + fallbackEx.getMessage());
            }
        }
    }

    private VisionResult analyzeWithGemini(Path screenshotPng, String prompt) {
        List<String> keys = keys(properties == null || properties.gemini() == null ? null : properties.gemini().apiKeys());
        if (keys.isEmpty()) {
            throw new IllegalStateException("Gemini API keys are not configured");
        }
        String key = pick(keys, geminiIndex);
        String model = properties.gemini().model();
        Map<String, Object> body = Map.of(
            "contents", List.of(Map.of(
                "parts", List.of(
                    Map.of("text", prompt),
                    Map.of("inline_data", Map.of(
                        "mime_type", "image/png",
                        "data", base64(screenshotPng)
                    ))
                )
            ))
        );
        String response = restClient.post()
            .uri("https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}", model, key)
            .body(body)
            .retrieve()
            .body(String.class);
        return new VisionResult("gemini", extractGeminiText(response));
    }

    private VisionResult analyzeWithOpenRouter(Path screenshotPng, String prompt) {
        List<String> keys = keys(properties == null || properties.openrouter() == null ? null : properties.openrouter().apiKeys());
        if (keys.isEmpty()) {
            throw new IllegalStateException("OpenRouter API keys are not configured");
        }
        String key = pick(keys, openRouterIndex);
        String model = properties.openrouter().model();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of(
            "role", "user",
            "content", List.of(
                Map.of("type", "text", "text", prompt),
                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + base64(screenshotPng)))
            )
        )));
        String response = restClient.post()
            .uri("https://openrouter.ai/api/v1/chat/completions")
            .headers(headers -> headers.setBearerAuth(key))
            .body(body)
            .retrieve()
            .body(String.class);
        return new VisionResult("openrouter", extractOpenRouterText(response));
    }

    private String extractGeminiText(String response) {
        try {
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asString("");
            return StringUtils.hasText(text) ? text.strip() : "No visual annotations returned";
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse Gemini vision response", ex);
        }
    }

    private String extractOpenRouterText(String response) {
        try {
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonNode part : content) {
                    String partText = part.path("text").asString("");
                    if (StringUtils.hasText(partText)) {
                        text.append(partText).append('\n');
                    }
                }
                return StringUtils.hasText(text.toString()) ? text.toString().strip() : "No visual annotations returned";
            }
            String text = content.asString("");
            return StringUtils.hasText(text) ? text.strip() : "No visual annotations returned";
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse OpenRouter vision response", ex);
        }
    }

    private String base64(Path path) {
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read screenshot", ex);
        }
    }

    private List<String> keys(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .toList();
    }

    private String pick(List<String> keys, AtomicInteger index) {
        return keys.get(Math.floorMod(index.getAndIncrement(), keys.size()));
    }

    private String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
