package com.nalanda.validation.domain.model;

/**
 * The one-directional lifecycle of a {@link ValidationRequest}
 * (see {@code docs/business-rules.md} § 2).
 */
public enum ValidationStatus {
    PENDING_UPLOAD,
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}
