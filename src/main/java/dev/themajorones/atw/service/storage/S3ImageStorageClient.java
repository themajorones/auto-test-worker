package dev.themajorones.atw.service.storage;

import java.io.InputStream;
import java.net.URI;

import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class S3ImageStorageClient implements ImageStorageClient {

    private static final String IMAGE_BUCKET = "image";

    private final S3Client s3Client;
    private final ArtifactStorageProperties properties;

    public S3ImageStorageClient(ArtifactStorageProperties properties) {
        this.properties = properties;
        var builder = S3Client.builder()
            .region(Region.of(defaultIfBlank(properties.region(), "us-east-1")))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(properties.pathStyleAccess())
                .build())
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())
            ))
            .httpClientBuilder(UrlConnectionHttpClient.builder());
        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        this.s3Client = builder.build();
    }

    @PostConstruct
    @Override
    public void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(IMAGE_BUCKET).build());
        } catch (S3Exception ex) {
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(IMAGE_BUCKET).build());
            } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {
                // Bucket is already available.
            }
        }
    }

    @Override
    public ArtifactStorageObject getObject(String key) {
        ResponseInputStream<GetObjectResponse> response = s3Client.getObject(
            GetObjectRequest.builder()
                .bucket(IMAGE_BUCKET)
                .key(fullKey(key))
                .build()
        );
        GetObjectResponse metadata = response.response();
        long contentLength = metadata.contentLength() == null ? -1L : metadata.contentLength();
        return new ArtifactStorageObject(response, contentLength, metadata.contentType());
    }

    @Override
    public void putObject(String key, InputStream inputStream, long contentLength, String contentType) {
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(IMAGE_BUCKET)
                .key(fullKey(key))
                .contentType(StringUtils.hasText(contentType) ? contentType : "application/octet-stream")
                .build(),
            RequestBody.fromInputStream(inputStream, contentLength)
        );
    }

    private String fullKey(String key) {
        String prefix = properties.prefix();
        if (!StringUtils.hasText(prefix)) {
            return key;
        }
        return prefix.endsWith("/") ? prefix + key : prefix + "/" + key;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
