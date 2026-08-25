package com.nalanda.validation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nalanda.validation.domain.model.DocumentMetadata;
import com.nalanda.validation.domain.model.ValidationRequest;
import com.nalanda.validation.domain.model.ValidationResult;
import com.nalanda.validation.domain.model.ValidationStatus;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessValidationUseCaseTest {

    private InMemoryValidationRequestRepository repository;
    private FakeDocumentStoragePort storage;
    private ProcessValidationUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = new InMemoryValidationRequestRepository();
        storage = new FakeDocumentStoragePort();
        useCase = new ProcessValidationUseCase(repository, storage);
    }

    @Test
    void should_completeWithPass_when_theDocumentIsAPdfOfOneByte() {
        var request = queuedRequest("application/pdf");
        storage.reportSize(1L);

        useCase.execute(request.getId());

        var processed = repository.findById(request.getId());
        assertThat(processed.getStatus()).isEqualTo(ValidationStatus.COMPLETED);
        assertThat(processed.getResult().verdict()).isEqualTo(ValidationResult.Verdict.PASS);
        assertThat(processed.getResult().reason()).isNull();
        assertThat(processed.getResult().fields()).isEqualTo(Map.of("filename", "invoice.pdf"));
    }

    @Test
    void should_completeWithPass_when_theDocumentIsExactlyFifteenMegabytes() {
        var request = queuedRequest("application/pdf");
        storage.reportSize(ProcessValidationUseCase.MAX_DOCUMENT_SIZE_BYTES);

        useCase.execute(request.getId());

        var processed = repository.findById(request.getId());
        assertThat(processed.getStatus()).isEqualTo(ValidationStatus.COMPLETED);
        assertThat(processed.getResult().verdict()).isEqualTo(ValidationResult.Verdict.PASS);
    }

    @Test
    void should_completeWithUnsupportedContentType_when_theDocumentIsNotAPdf() {
        var request = queuedRequest("image/png");
        storage.reportSize(1024L);

        useCase.execute(request.getId());

        assertThatFailedWith(request, ProcessValidationUseCase.UNSUPPORTED_CONTENT_TYPE_REASON);
    }

    @Test
    void should_completeWithEmptyFile_when_storageReportsNoBytes() {
        var request = queuedRequest("application/pdf");
        storage.reportSize(0L);

        useCase.execute(request.getId());

        assertThatFailedWith(request, ProcessValidationUseCase.EMPTY_FILE_REASON);
    }

    @Test
    void should_completeWithFileTooLarge_when_theDocumentExceedsFifteenMegabytes() {
        var request = queuedRequest("application/pdf");
        storage.reportSize(ProcessValidationUseCase.MAX_DOCUMENT_SIZE_BYTES + 1);

        useCase.execute(request.getId());

        assertThatFailedWith(request, ProcessValidationUseCase.FILE_TOO_LARGE_REASON);
    }

    @Test
    void should_reportUnsupportedContentTypeFirst_when_bothRulesWouldFail() {
        var request = queuedRequest("image/png");
        storage.reportSize(0L);

        useCase.execute(request.getId());

        assertThatFailedWith(request, ProcessValidationUseCase.UNSUPPORTED_CONTENT_TYPE_REASON);
    }

    @Test
    void should_recordTheDiscoveredSize_when_processingCompletes() {
        var request = queuedRequest("application/pdf");
        storage.reportSize(4096L);

        useCase.execute(request.getId());

        assertThat(repository.findById(request.getId()).getDocument().sizeInBytes())
                .isEqualTo(4096L);
    }

    @Test
    void should_leaveTheRecordedResultUntouched_when_theSameJobIsDeliveredTwice() {
        var request = queuedRequest("application/pdf");
        storage.reportSize(1024L);
        useCase.execute(request.getId());

        storage.reportSize(0L);
        useCase.execute(request.getId());

        var processed = repository.findById(request.getId());
        assertThat(processed.getStatus()).isEqualTo(ValidationStatus.COMPLETED);
        assertThat(processed.getResult().verdict()).isEqualTo(ValidationResult.Verdict.PASS);
        assertThat(processed.getDocument().sizeInBytes()).isEqualTo(1024L);
    }

    @Test
    void should_endInFailedWithoutAResult_when_storageIsUnavailable() {
        var request = queuedRequest("application/pdf");
        storage.breakStorage();

        useCase.execute(request.getId());

        var processed = repository.findById(request.getId());
        assertThat(processed.getStatus()).isEqualTo(ValidationStatus.FAILED);
        assertThat(processed.getResult()).isNull();
    }

    private void assertThatFailedWith(ValidationRequest request, String expectedReason) {
        var processed = repository.findById(request.getId());
        assertThat(processed.getStatus()).isEqualTo(ValidationStatus.COMPLETED);
        assertThat(processed.getResult().verdict()).isEqualTo(ValidationResult.Verdict.FAIL);
        assertThat(processed.getResult().reason()).isEqualTo(expectedReason);
    }

    private ValidationRequest queuedRequest(String contentType) {
        var request =
                ValidationRequest.create(new DocumentMetadata("invoice.pdf", contentType, 0, "key/invoice.pdf"));
        request.confirmUpload();
        repository.save(request);
        return request;
    }
}
