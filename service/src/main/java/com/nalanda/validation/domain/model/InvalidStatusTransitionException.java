package com.nalanda.validation.domain.model;

import java.util.UUID;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(UUID requestId, ValidationStatus actual, ValidationStatus expected) {
        super("Request %s is in status %s, expected %s".formatted(requestId, actual, expected));
    }
}
