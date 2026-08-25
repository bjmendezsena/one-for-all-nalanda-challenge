package com.nalanda.validation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nalanda.validation.domain.model.ValidationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateValidationUseCaseTest {

    private static final CreateValidationCommand COMMAND =
            new CreateValidationCommand("invoice.pdf", "application/pdf");

    private InMemoryValidationRequestRepository repository;
    private FakeDocumentStoragePort storage;
    private CreateValidationUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = new InMemoryValidationRequestRepository();
        storage = new FakeDocumentStoragePort();
        useCase = new CreateValidationUseCase(repository, storage);
    }

    @Test
    void should_returnAPendingUploadRequestWithAnUploadUrl_when_creatingAValidation() {
        var result = useCase.execute(COMMAND, "demo-key-1");

        assertThat(result.requestId()).isNotNull();
        assertThat(result.status()).isEqualTo(ValidationStatus.PENDING_UPLOAD);
        assertThat(result.uploadUrl()).isNotBlank();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void should_persistTheDocumentMetadataWithAnUnknownSize_when_creatingAValidation() {
        var result = useCase.execute(COMMAND, null);

        var stored = repository.findById(result.requestId());
        assertThat(stored.getDocument().filename()).isEqualTo("invoice.pdf");
        assertThat(stored.getDocument().contentType()).isEqualTo("application/pdf");
        assertThat(stored.getDocument().sizeInBytes()).isZero();
        assertThat(stored.getDocument().storageKey()).endsWith("/invoice.pdf");
    }

    @Test
    void should_returnTheOriginalRequestAndCreateNothing_when_replayingTheSameIdempotencyKey() {
        var original = useCase.execute(COMMAND, "demo-key-1");

        var replay = useCase.execute(COMMAND, "demo-key-1");

        assertThat(replay.requestId()).isEqualTo(original.requestId());
        assertThat(replay.status()).isEqualTo(ValidationStatus.PENDING_UPLOAD);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void should_returnAFreshUploadUrlOverTheStoredStorageKey_when_replayingTheSameIdempotencyKey() {
        var original = useCase.execute(COMMAND, "demo-key-1");

        var replay = useCase.execute(COMMAND, "demo-key-1");

        assertThat(replay.uploadUrl()).isNotEqualTo(original.uploadUrl());
        assertThat(storage.signedStorageKeys()).hasSize(2);
        assertThat(storage.signedStorageKeys().get(1)).isEqualTo(storage.signedStorageKeys().get(0));
    }

    @Test
    void should_ignoreTheNewBody_when_replayingTheSameIdempotencyKeyWithADifferentBody() {
        var original = useCase.execute(COMMAND, "demo-key-1");

        var replay = useCase.execute(new CreateValidationCommand("other.png", "image/png"), "demo-key-1");

        assertThat(replay.requestId()).isEqualTo(original.requestId());
        assertThat(repository.findById(replay.requestId()).getDocument().filename()).isEqualTo("invoice.pdf");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void should_createASeparateRequestEveryTime_when_creatingWithoutAnIdempotencyKey() {
        var first = useCase.execute(COMMAND, null);
        var second = useCase.execute(COMMAND, null);

        assertThat(second.requestId()).isNotEqualTo(first.requestId());
        assertThat(repository.count()).isEqualTo(2);
    }
}
