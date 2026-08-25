package com.nalanda.validation.application;

import com.nalanda.validation.domain.model.ValidationStatus;
import java.util.UUID;

/** The create response body. */
public record CreateValidationResult(UUID requestId, ValidationStatus status, String uploadUrl) {
}
