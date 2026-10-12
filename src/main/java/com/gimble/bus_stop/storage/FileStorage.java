package com.gimble.bus_stop.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * Private file storage for identity documents. Files are never served publicly — only through
 * authenticated endpoints. Backed by MinIO / any S3-compatible bucket, or memory in tests (see StorageConfig).
 */
public interface FileStorage {

    /** Stores the content under a server-generated key and returns that key. */
    String store(String keyPrefix, String extension, InputStream content, long size, String contentType) throws IOException;

    Resource load(String key) throws IOException;

    void delete(String key) throws IOException;
}
