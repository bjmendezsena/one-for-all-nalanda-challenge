package com.nalanda.validation.application;

import jakarta.validation.constraints.NotBlank;

/** The create request body — reused as-is, there is no separate DTO layer. */
public record CreateValidationCommand(@NotBlank String filename, @NotBlank String contentType) {
}
