package com.nalanda.validation.config;

import com.nalanda.validation.adapter.out.storage.StorageProperties;
import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * MinIO is S3-compatible but serves buckets under a path, not a subdomain, so path-style access is
 * enabled on both the client and the presigner.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class S3Config {

    @Bean
    S3Client s3Client(StorageProperties storageProperties) {
        return S3Client.builder()
                .endpointOverride(URI.create(storageProperties.endpoint()))
                .region(Region.of(storageProperties.region()))
                .credentialsProvider(credentialsProvider(storageProperties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Bean
    S3Presigner s3Presigner(StorageProperties storageProperties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(storageProperties.endpoint()))
                .region(Region.of(storageProperties.region()))
                .credentialsProvider(credentialsProvider(storageProperties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    private static StaticCredentialsProvider credentialsProvider(StorageProperties storageProperties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(storageProperties.accessKey(), storageProperties.secretKey()));
    }
}
