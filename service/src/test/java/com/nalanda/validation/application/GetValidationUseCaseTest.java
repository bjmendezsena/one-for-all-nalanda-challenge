package com.nalanda.validation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nalanda.validation.domain.model.DocumentMetadata;
import com.nalanda.validation.domain.model.ValidationRequest;
import com.nalanda.validation.domain.model.ValidationRequestNotFoundException;
import com.nalanda.validation.domain.model.ValidationResult;
import com.nalanda.validation.domain.model.ValidationStatus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetValidationUseCaseTest {

    private InMemoryValidationRequestRepository repository;
    private GetValidationUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = new InMemoryValidationRequestRepository();
        useCase = new GetValidationUseCase(repository);
    }

    @Test
    void should_returnTheRequestUnchanged_when_readingAPendingUploadRequest() {
        var request = ValidationRequest.create(
                new DocumentMetadata("invoice.pdf", "application/pdf", 0, "storage-key/invoice.pdf"));
        repository.save(request);

        var found = useCase.execute(request.getId());

        assertThat(found.getId()).isEqualTo(request.getId());
        assertThat(found.getStatus()).isEqualTo(ValidationStatus.PENDING_UPLOAD);
        assertThat(found.getResult()).isNull();
        assertThat(repository.findById(request.getId()).getStatus()).isEqualTo(ValidationStatus.PENDING_UPLOAD);
    }

    @Test
    void should_returnTheResult_when_readingACompletedRequest() {
        var request = ValidationRequest.create(
                new DocumentMetadata("invoice.pdf", "application/pdf", 0, "storage-key/invoice.pdf"));
        request.confirmUpload();
        request.startProcessing();
        var result = new ValidationResult(ValidationResult.Verdict.PASS, Map.of("filename", "invoice.pdf"), null);
        request.complete(result);
        repository.save(request);

        var found = useCase.execute(request.getId());

        assertThat(found.getStatus()).isEqualTo(ValidationStatus.COMPLETED);
        assertThat(found.getResult()).isEqualTo(result);
    }

    @Test
    void should_throwValidationRequestNotFound_when_readingAnUnknownRequest() {
        var unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.execute(unknownId)).isInstanceOf(ValidationRequestNotFoundException.class);
    }
}
