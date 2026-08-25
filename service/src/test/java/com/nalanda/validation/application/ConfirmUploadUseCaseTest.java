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

class ConfirmUploadUseCaseTest {

    private InMemoryValidationRequestRepository repository;
    private RecordingJobPublisher publisher;
    private ConfirmUploadUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = new InMemoryValidationRequestRepository();
        publisher = new RecordingJobPublisher();
        useCase = new ConfirmUploadUseCase(repository, publisher);
    }

    @Test
    void should_queueTheRequestAndPublishOnce_when_confirmingAPendingUploadRequest() {
        var request = savedRequest();

        var result = useCase.execute(request.getId());

        assertThat(result.requestId()).isEqualTo(request.getId());
        assertThat(result.status()).isEqualTo(ValidationStatus.QUEUED);
        assertThat(publisher.publishedEvents()).containsExactly(request.getId());
        assertThat(repository.findById(request.getId()).getStatus()).isEqualTo(ValidationStatus.QUEUED);
    }

    @Test
    void should_reportTheStatusWithoutWaitingForProcessing_when_confirmingAPendingUploadRequest() {
        var request = savedRequest();

        var result = useCase.execute(request.getId());

        // The processing use case has not run: the request is QUEUED, never PROCESSING or beyond.
        assertThat(result.status()).isEqualTo(ValidationStatus.QUEUED);
        assertThat(repository.findById(request.getId()).getResult()).isNull();
    }

    @Test
    void should_publishNothingAndReturnTheCurrentStatus_when_confirmingTwice() {
        var request = savedRequest();
        useCase.execute(request.getId());
        publisher.publishedEvents();

        var result = useCase.execute(request.getId());

        assertThat(result.status()).isEqualTo(ValidationStatus.QUEUED);
        assertThat(publisher.publishedEvents()).hasSize(1);
    }

    @Test
    void should_publishNothingAndReturnCompleted_when_confirmingAnAlreadyCompletedRequest() {
        var request = savedRequest();
        request.confirmUpload();
        request.startProcessing();
        request.complete(new ValidationResult(ValidationResult.Verdict.PASS, Map.of("filename", "invoice.pdf"), null));
        repository.save(request);

        var result = useCase.execute(request.getId());

        assertThat(result.status()).isEqualTo(ValidationStatus.COMPLETED);
        assertThat(publisher.publishedEvents()).isEmpty();
    }

    @Test
    void should_throwValidationRequestNotFound_when_confirmingAnUnknownRequest() {
        var unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.execute(unknownId)).isInstanceOf(ValidationRequestNotFoundException.class);
        assertThat(publisher.publishedEvents()).isEmpty();
    }

    private ValidationRequest savedRequest() {
        var request = ValidationRequest.create(
                new DocumentMetadata("invoice.pdf", "application/pdf", 0, "storage-key/invoice.pdf"));
        repository.save(request);
        return request;
    }
}
