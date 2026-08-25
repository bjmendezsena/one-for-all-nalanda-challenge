package com.nalanda.validation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nalanda.validation.domain.model.DocumentMetadata;
import com.nalanda.validation.domain.model.ValidationRequest;
import com.nalanda.validation.domain.model.ValidationRequestNotFoundException;
import com.nalanda.validation.domain.model.ValidationResult;
import com.nalanda.validation.domain.model.ValidationStatus;
import java.util.Map;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs against H2 in PostgreSQL mode with the real Liquibase changelog applied, so every run is
 * also a regression test for the migrations (research.md R-003).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaValidationRequestRepository.class)
class JpaValidationRequestRepositoryTest {

    @Autowired
    private JpaValidationRequestRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void should_roundTripEveryFieldIncludingResultJson_when_savingACompletedRequest() {
        var request = completedRequest();

        repository.save(request);
        entityManager.flush();
        entityManager.clear();

        var reloaded = repository.findById(request.getId());
        assertThat(reloaded.getId()).isEqualTo(request.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ValidationStatus.COMPLETED);
        assertThat(reloaded.getDocument()).isEqualTo(request.getDocument());
        assertThat(reloaded.getResult().verdict()).isEqualTo(ValidationResult.Verdict.FAIL);
        assertThat(reloaded.getResult().reason()).isEqualTo("file too large");
        assertThat(reloaded.getResult().fields()).isEqualTo(Map.of("filename", "invoice.pdf"));
    }

    @Test
    void should_roundTripWithoutResult_when_savingARequestThatHasNotCompleted() {
        var request = ValidationRequest.create(document());

        repository.save(request);
        entityManager.flush();
        entityManager.clear();

        var reloaded = repository.findById(request.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ValidationStatus.PENDING_UPLOAD);
        assertThat(reloaded.getResult()).isNull();
        assertThat(reloaded.getDocument().sizeInBytes()).isZero();
    }

    @Test
    void should_throwValidationRequestNotFound_when_findingByAnUnknownId() {
        var unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> repository.findById(unknownId))
                .isInstanceOf(ValidationRequestNotFoundException.class);
    }

    @Test
    void should_returnTheOriginalRequest_when_findingByAKnownIdempotencyKey() {
        var request = ValidationRequest.create(document());
        repository.save(request, "demo-key-1");
        entityManager.flush();
        entityManager.clear();

        var found = repository.findByIdempotencyKey("demo-key-1");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(request.getId());
    }

    @Test
    void should_returnEmpty_when_findingByAnUnseenIdempotencyKey() {
        assertThat(repository.findByIdempotencyKey("never-used")).isEmpty();
    }

    @Test
    void should_keepTheStoredIdempotencyKey_when_savingWithoutOne() {
        var request = ValidationRequest.create(document());
        repository.save(request, "demo-key-2");
        entityManager.flush();
        entityManager.clear();

        request.confirmUpload();
        repository.save(request);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByIdempotencyKey("demo-key-2")).isPresent();
    }

    @Test
    void should_rejectTheSecondRow_when_savingADuplicateIdempotencyKey() {
        repository.save(ValidationRequest.create(document()), "duplicate-key");
        entityManager.flush();

        repository.save(ValidationRequest.create(document()), "duplicate-key");

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void should_allowManyRows_when_savingWithoutAnIdempotencyKey() {
        repository.save(ValidationRequest.create(document()), null);
        repository.save(ValidationRequest.create(document()), null);

        entityManager.flush();

        assertThat(entityManager.getEntityManager()
                        .createQuery("select count(e) from ValidationRequestEntity e", Long.class)
                        .getSingleResult())
                .isEqualTo(2L);
    }

    private static DocumentMetadata document() {
        return new DocumentMetadata("invoice.pdf", "application/pdf", 0, UUID.randomUUID() + "/invoice.pdf");
    }

    private static ValidationRequest completedRequest() {
        var request = ValidationRequest.create(document());
        request.confirmUpload();
        request.startProcessing();
        request.recordDiscoveredSize(20L * 1024 * 1024);
        request.complete(new ValidationResult(
                ValidationResult.Verdict.FAIL, Map.of("filename", "invoice.pdf"), "file too large"));
        return request;
    }
}
