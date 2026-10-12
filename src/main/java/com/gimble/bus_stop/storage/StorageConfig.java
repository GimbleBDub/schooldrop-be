package com.gimble.bus_stop.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * app.storage.endpoint = "memory" keeps files in memory (tests, throwaway runs);
 * anything else is an S3-compatible endpoint (MinIO in docker-compose, R2/S3/Spaces in production).
 */
@Slf4j
@Configuration
public class StorageConfig {

    @Bean
    public FileStorage fileStorage(@Value("${app.storage.endpoint}") String endpoint,
                                   @Value("${app.storage.access-key:}") String accessKey,
                                   @Value("${app.storage.secret-key:}") String secretKey,
                                   @Value("${app.storage.bucket}") String bucket,
                                   @Value("${app.storage.region}") String region) {
        if ("memory".equalsIgnoreCase(endpoint)) {
            log.warn("Using in-memory file storage: uploaded documents are lost on restart");
            return new InMemoryFileStorage();
        }
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .region(region)
                .credentials(accessKey, secretKey)
                .build();
        return new S3FileStorage(client, bucket);
    }

    private static String newKey(String keyPrefix, String extension) {
        return keyPrefix + "/" + UUID.randomUUID() + "." + extension;
    }

    /** One private bucket, created on first use. */
    static final class S3FileStorage implements FileStorage {
        private final MinioClient client;
        private final String bucket;
        private volatile boolean ready;

        S3FileStorage(MinioClient client, String bucket) {
            this.client = client;
            this.bucket = bucket;
        }

        private void ensureBucket() throws Exception {
            if (ready) return;
            synchronized (this) {
                if (ready) return;
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
                ready = true;
            }
        }

        @Override
        public String store(String keyPrefix, String extension, InputStream content, long size, String contentType)
                throws IOException {
            String key = newKey(keyPrefix, extension);
            try {
                ensureBucket();
                client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                        .stream(content, size, -1).contentType(contentType).build());
                return key;
            } catch (Exception e) {
                throw new IOException("Object storage write failed", e);
            }
        }

        @Override
        public Resource load(String key) throws IOException {
            try {
                ensureBucket();
                return new InputStreamResource(client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build()));
            } catch (ErrorResponseException e) {
                if ("NoSuchKey".equals(e.errorResponse().code())) throw new FileNotFoundException(key);
                throw new IOException("Object storage read failed", e);
            } catch (Exception e) {
                throw new IOException("Object storage read failed", e);
            }
        }

        @Override
        public void delete(String key) throws IOException {
            try {
                ensureBucket();
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            } catch (Exception e) {
                throw new IOException("Object storage delete failed", e);
            }
        }
    }

    /** For tests and throwaway local runs. */
    static final class InMemoryFileStorage implements FileStorage {
        private final ConcurrentHashMap<String, byte[]> files = new ConcurrentHashMap<>();

        @Override
        public String store(String keyPrefix, String extension, InputStream content, long size, String contentType)
                throws IOException {
            String key = newKey(keyPrefix, extension);
            files.put(key, content.readAllBytes());
            return key;
        }

        @Override
        public Resource load(String key) throws IOException {
            byte[] bytes = files.get(key);
            if (bytes == null) throw new FileNotFoundException(key);
            return new ByteArrayResource(bytes);
        }

        @Override
        public void delete(String key) {
            files.remove(key);
        }
    }
}
