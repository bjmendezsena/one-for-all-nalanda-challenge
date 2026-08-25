package com.nalanda.validation.application;

import com.nalanda.validation.domain.model.ValidationStatus;
import java.util.UUID;

/** The confirm response body — the status the request holds once confirm has run (R-005). */
public record ConfirmUploadResult(UUID requestId, ValidationStatus status) {
}
