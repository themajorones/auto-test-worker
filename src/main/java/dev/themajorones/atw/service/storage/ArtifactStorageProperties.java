package dev.themajorones.atw.service.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "artifact.storage")
public record ArtifactStorageProperties(
    String endpoint,
    String region,
    String accessKey,
    String secretKey,
    String bucket,
    String prefix,
    boolean pathStyleAccess,
    boolean createBucket
) {
}
