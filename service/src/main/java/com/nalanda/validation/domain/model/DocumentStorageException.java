package com.nalanda.validation.domain.model;

/**
 * The storage backend failed. A missing object is <em>not</em> a failure — it is reported as
 * size {@code 0} instead (see {@code docs/service/upload-flow.md} § 4).
 */
public class DocumentStorageException extends RuntimeException {

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
