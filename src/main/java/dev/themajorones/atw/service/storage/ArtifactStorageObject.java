package dev.themajorones.atw.service.storage;

import java.io.IOException;
import java.io.InputStream;

public record ArtifactStorageObject(InputStream inputStream, long contentLength, String contentType) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
