package com.nalanda.validation.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * The only JPA entity in the codebase. Columns mirror
 * {@code db/changelog/changes/001-create-validation-request.yaml} exactly.
 */
@Entity
@Table(name = "validation_request")
class ValidationRequestEntity {

    private static final int RESULT_FIELDS_MAX_LENGTH = 4000;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 255)
    private String contentType;

    @Column(name = "size_in_bytes", nullable = false)
    private long sizeInBytes;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "result_verdict", length = 16)
    private String resultVerdict;

    // The stubbed result fields, as JSON text. varchar rather than clob because Liquibase's clob
    // resolves to text on PostgreSQL and CLOB on H2, and no single Hibernate mapping validates
    // against both; varchar is identical on the two, which is what ddl-auto: validate needs.
    @Column(name = "result_fields", length = RESULT_FIELDS_MAX_LENGTH)
    private String resultFields;

    @Column(name = "result_reason", length = 512)
    private String resultReason;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    protected ValidationRequestEntity() {
        // required by JPA
    }

    ValidationRequestEntity(
            UUID id,
            String status,
            String filename,
            String contentType,
            long sizeInBytes,
            String storageKey,
            String resultVerdict,
            String resultFields,
            String resultReason,
            String idempotencyKey) {
        this.id = id;
        this.status = status;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeInBytes = sizeInBytes;
        this.storageKey = storageKey;
        this.resultVerdict = resultVerdict;
        this.resultFields = resultFields;
        this.resultReason = resultReason;
        this.idempotencyKey = idempotencyKey;
    }

    UUID getId() {
        return id;
    }

    String getStatus() {
        return status;
    }

    String getFilename() {
        return filename;
    }

    String getContentType() {
        return contentType;
    }

    long getSizeInBytes() {
        return sizeInBytes;
    }

    String getStorageKey() {
        return storageKey;
    }

    String getResultVerdict() {
        return resultVerdict;
    }

    String getResultFields() {
        return resultFields;
    }

    String getResultReason() {
        return resultReason;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }
}
