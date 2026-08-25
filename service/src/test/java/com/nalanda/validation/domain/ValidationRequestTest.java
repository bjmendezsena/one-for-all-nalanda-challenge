package com.nalanda.validation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nalanda.validation.domain.model.DocumentMetadata;
import com.nalanda.validation.domain.model.InvalidStatusTransitionException;
import com.nalanda.validation.domain.model.ValidationRequest;
import com.nalanda.validation.domain.model.ValidationResult;
import com.nalanda.validation.domain.model.ValidationStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationRequestTest {

    private static final DocumentMetadata DOCUMENT =
            new DocumentMetadata("invoice.pdf", "application/pdf", 0, "storage-key/invoice.pdf");

    @Test
    void should_startInPendingUploadWithoutResult_when_created() {
        var request = ValidationRequest.create(DOCUMENT);

        assertThat(request.getId()).isNotNull();
        assertThat(request.getStatus()).isEqualTo(ValidationStatus.PENDING_UPLOAD);
        assertThat(request.getResult()).isNull();
        assertThat(request.getDocument()).isEqualTo(DOCUMENT);
    }

    @Test
    void should_moveToQueued_when_confirmingUploadOnPendingUpload() {
        var request = ValidationRequest.create(DOCUMENT);

        request.confirmUpload();

        assertThat(request.getStatus()).isEqualTo(ValidationStatus.QUEUED);
    }

    @Test
    void should_keepCurrentStatus_when_confirmingUploadTwice() {
        var request = ValidationRequest.create(DOCUMENT);
        request.confirmUpload();
        request.startProcessing();

        request.confirmUpload();

        assertThat(request.getStatus()).isEqualTo(ValidationStatus.PROCESSING);
    }

    @Test
    void should_moveToProcessing_when_startingProcessingOnQueued() {
        var request = queuedRequest();

        request.startProcessing();

        assertThat(request.getStatus()).isEqualTo(ValidationStatus.PROCESSING);
    }

    @Test
    void should_throwInvalidStatusTransition_when_startingProcessingBeforeConfirm() {
        var request = ValidationRequest.create(DOCUMENT);

        assertThatThrownBy(request::startProcessing).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void should_throwInvalidStatusTransition_when_startingProcessingTwice() {
        var request = processingRequest();

        assertThatThrownBy(request::startProcessing).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void should_moveToCompletedAndStoreResult_when_completingFromProcessing() {
        var request = processingRequest();
        var result = new ValidationResult(ValidationResult.Verdict.PASS, Map.of("filename", "invoice.pdf"), null);

        request.complete(result);

        assertThat(request.getStatus()).isEqualTo(ValidationStatus.COMPLETED);
        assertThat(request.getResult()).isEqualTo(result);
    }

    @Test
    void should_throwInvalidStatusTransition_when_completingFromQueued() {
        var request = queuedRequest();

        assertThatThrownBy(() -> request.complete(passResult()))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void should_throwInvalidStatusTransition_when_completingTwice() {
        var request = processingRequest();
        request.complete(passResult());

        assertThatThrownBy(() -> request.complete(passResult()))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void should_reachFailedWithoutResult_when_failingFromProcessing() {
        var request = processingRequest();

        request.fail();

        assertThat(request.getStatus()).isEqualTo(ValidationStatus.FAILED);
        assertThat(request.getResult()).isNull();
    }

    @Test
    void should_throwInvalidStatusTransition_when_failingBeforeProcessing() {
        var request = queuedRequest();

        assertThatThrownBy(request::fail).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void should_throwInvalidStatusTransition_when_failingAfterCompletion() {
        var request = processingRequest();
        request.complete(passResult());

        assertThatThrownBy(request::fail).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void should_replaceOnlyTheSize_when_recordingDiscoveredSize() {
        var request = ValidationRequest.create(DOCUMENT);

        request.recordDiscoveredSize(2048L);

        assertThat(request.getDocument().sizeInBytes()).isEqualTo(2048L);
        assertThat(request.getDocument().filename()).isEqualTo(DOCUMENT.filename());
        assertThat(request.getDocument().contentType()).isEqualTo(DOCUMENT.contentType());
        assertThat(request.getDocument().storageKey()).isEqualTo(DOCUMENT.storageKey());
    }

    @Test
    void should_restoreStateVerbatim_when_rehydratingFromPersistence() {
        var original = processingRequest();
        var result = passResult();

        var restored = ValidationRequest.restore(
                original.getId(), original.getDocument(), ValidationStatus.COMPLETED, result);

        assertThat(restored.getId()).isEqualTo(original.getId());
        assertThat(restored.getStatus()).isEqualTo(ValidationStatus.COMPLETED);
        assertThat(restored.getResult()).isEqualTo(result);
    }

    private static ValidationRequest queuedRequest() {
        var request = ValidationRequest.create(DOCUMENT);
        request.confirmUpload();
        return request;
    }

    private static ValidationRequest processingRequest() {
        var request = queuedRequest();
        request.startProcessing();
        return request;
    }

    private static ValidationResult passResult() {
        return new ValidationResult(ValidationResult.Verdict.PASS, Map.of("filename", "invoice.pdf"), null);
    }
}
