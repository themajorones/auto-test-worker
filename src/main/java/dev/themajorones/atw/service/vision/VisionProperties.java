package dev.themajorones.atw.service.vision;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vision")
public record VisionProperties(
    ProviderProperties gemini,
    ProviderProperties openrouter
) {

    public record ProviderProperties(
        List<String> apiKeys,
        String model
    ) {
    }
}
