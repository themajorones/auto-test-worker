package dev.themajorones.atw.service.storage;

import java.io.InputStream;

public interface ArtifactStorageClient {

    void ensureBucketExists();

    ArtifactStorageObject getObject(String key);

    void deleteObject(String key);

    void putObject(String key, InputStream inputStream, long contentLength, String contentType);
}
