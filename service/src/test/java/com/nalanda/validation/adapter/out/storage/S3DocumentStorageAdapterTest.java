package com.nalanda.validation.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nalanda.validation.domain.model.DocumentStorageException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

class S3DocumentStorageAdapterTest {

    private static final StorageProperties STORAGE_PROPERTIES = new StorageProperties(
            "http://localhost:9000", "validation-documents", "minioadmin", "minioadmin", "us-east-1",
            Duration.ofMinutes(15));

    private final S3Client s3Client = mock(S3Client.class);
    private final S3Presigner presigner = mock(S3Presigner.class);

    private final S3DocumentStorageAdapter adapter =
            new S3DocumentStorageAdapter(s3Client, presigner, STORAGE_PROPERTIES);

    @Test
    void should_returnTheSignedUrl_when_creatingAPresignedUpload() throws Exception {
        var presignedRequest = mock(PresignedPutObjectRequest.class);
        when(presignedRequest.url()).thenReturn(URI.create("https://storage.test/key?X-Amz-Signature=abc").toURL());
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedRequest);

        var upload = adapter.createPresignedUpload("key/invoice.pdf", "application/pdf");

        assertThat(upload.url()).isEqualTo("https://storage.test/key?X-Amz-Signature=abc");
    }

    @Test
    void should_throwDocumentStorageException_when_signingFails() {
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenThrow(S3Exception.builder().message("presign refused").build());

        assertThatThrownBy(() -> adapter.createPresignedUpload("key/invoice.pdf", "application/pdf"))
                .isInstanceOf(DocumentStorageException.class);
    }

    @Test
    void should_returnTheReportedSize_when_theObjectExists() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(4096L).build());

        assertThat(adapter.sizeOf("key/invoice.pdf")).isEqualTo(4096L);
    }

    @Test
    void should_returnZero_when_theObjectDoesNotExist() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThat(adapter.sizeOf("key/invoice.pdf")).isZero();
    }

    @Test
    void should_throwDocumentStorageException_when_theStorageBackendFails() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("service unavailable").build());

        assertThatThrownBy(() -> adapter.sizeOf("key/invoice.pdf")).isInstanceOf(DocumentStorageException.class);
    }
}
