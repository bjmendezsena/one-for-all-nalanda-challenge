package com.nalanda.validation.adapter.out.storage;

import com.nalanda.validation.domain.model.DocumentStorageException;
import com.nalanda.validation.domain.model.PresignedUpload;
import com.nalanda.validation.domain.port.DocumentStoragePort;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
class S3DocumentStorageAdapter implements DocumentStoragePort {

    private static final long MISSING_OBJECT_SIZE_IN_BYTES = 0L;

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final StorageProperties storageProperties;

    S3DocumentStorageAdapter(S3Client s3Client, S3Presigner presigner, StorageProperties storageProperties) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.storageProperties = storageProperties;
    }

    @Override
    public PresignedUpload createPresignedUpload(String storageKey, String contentType) {
        try {
            var putObjectRequest = PutObjectRequest.builder()
                    .bucket(storageProperties.bucket())
                    .key(storageKey)
                    .contentType(contentType)
                    .build();
            var presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(storageProperties.presignTtl())
                    .putObjectRequest(putObjectRequest)
                    .build();
            return new PresignedUpload(presigner.presignPutObject(presignRequest).url().toString());
        } catch (RuntimeException ex) {
            throw new DocumentStorageException("Could not sign an upload URL for " + storageKey, ex);
        }
    }

    @Override
    public long sizeOf(String storageKey) {
        try {
            var headObjectRequest = HeadObjectRequest.builder()
                    .bucket(storageProperties.bucket())
                    .key(storageKey)
                    .build();
            return s3Client.headObject(headObjectRequest).contentLength();
        } catch (NoSuchKeyException ex) {
            // The client confirmed an upload that never happened — the deterministic rule turns
            // size 0 into "empty file" (docs/service/upload-flow.md § 4).
            return MISSING_OBJECT_SIZE_IN_BYTES;
        } catch (RuntimeException ex) {
            throw new DocumentStorageException("Could not read the size of " + storageKey, ex);
        }
    }
}
