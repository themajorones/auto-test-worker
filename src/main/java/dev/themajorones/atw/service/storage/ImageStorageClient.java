package dev.themajorones.atw.service.storage;

import java.io.InputStream;

public interface ImageStorageClient {

    void ensureBucketExists();

    ArtifactStorageObject getObject(String key);

    void putObject(String key, InputStream inputStream, long contentLength, String contentType);
}
