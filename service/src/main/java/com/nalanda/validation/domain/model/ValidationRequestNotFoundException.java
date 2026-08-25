package com.nalanda.validation.domain.model;

import java.util.UUID;

public class ValidationRequestNotFoundException extends RuntimeException {

    private final UUID requestId;

    public ValidationRequestNotFoundException(UUID requestId) {
        super("Validation request not found: " + requestId);
        this.requestId = requestId;
    }

    public UUID getRequestId() {
        return requestId;
    }
}
