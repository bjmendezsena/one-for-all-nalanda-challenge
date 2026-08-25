package com.nalanda.validation.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * The lifecycle of one document check. State changes only through the transition methods
 * below — there are no setters (see {@code docs/service/code_rules.md} § 1).
 *
 * <p>The Jackson annotations on the getters are the one accepted exception to domain purity:
 * this object is returned as-is by the read endpoint (see {@code docs/service/code_rules.md} § 6).
 */
public class ValidationRequest {

    private final UUID id;
    private DocumentMetadata document;
    private ValidationStatus status;
    private ValidationResult result;

    private ValidationRequest(UUID id, DocumentMetadata document, ValidationStatus status, ValidationResult result) {
        this.id = id;
        this.document = document;
        this.status = status;
        this.result = result;
    }

    public static ValidationRequest create(DocumentMetadata document) {
        return new ValidationRequest(UUID.randomUUID(), document, ValidationStatus.PENDING_UPLOAD, null);
    }

    /**
     * Rehydrates a request from its persisted state. Used only by the persistence mapper, which
     * lives in another package; it runs no guard and performs no transition.
     */
    public static ValidationRequest restore(
            UUID id, DocumentMetadata document, ValidationStatus status, ValidationResult result) {
        return new ValidationRequest(id, document, status, result);
    }

    public void confirmUpload() {
        if (status != ValidationStatus.PENDING_UPLOAD) {
            // Idempotent no-op for a repeated confirm — see docs/business-rules.md § 6
            return;
        }
        this.status = ValidationStatus.QUEUED;
    }

    public void startProcessing() {
        requireStatus(ValidationStatus.QUEUED);
        this.status = ValidationStatus.PROCESSING;
    }

    public void complete(ValidationResult result) {
        requireStatus(ValidationStatus.PROCESSING);
        this.status = ValidationStatus.COMPLETED;
        this.result = result;
    }

    public void fail() {
        requireStatus(ValidationStatus.PROCESSING);
        this.status = ValidationStatus.FAILED;
    }

    /**
     * Records the size storage reported for the document. The only mutation of the document
     * metadata after creation — a transition of the aggregate, not a setter.
     */
    public void recordDiscoveredSize(long sizeInBytes) {
        this.document = document.withSizeInBytes(sizeInBytes);
    }

    private void requireStatus(ValidationStatus expected) {
        if (status != expected) {
            throw new InvalidStatusTransitionException(id, status, expected);
        }
    }

    @JsonProperty("requestId")
    public UUID getId() {
        return id;
    }

    @JsonProperty("status")
    public ValidationStatus getStatus() {
        return status;
    }

    @JsonIgnore
    public DocumentMetadata getDocument() {
        return document;
    }

    @JsonProperty("result")
    public ValidationResult getResult() {
        return result;
    }
}
