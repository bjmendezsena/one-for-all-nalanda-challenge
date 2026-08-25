package com.nalanda.validation.adapter.out.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code storage.*} block of {@code application.yml}, pointing at MinIO locally.
 */
@ConfigurationProperties("storage")
public record StorageProperties(
        String endpoint, String bucket, String accessKey, String secretKey, String region, Duration presignTtl) {
}
