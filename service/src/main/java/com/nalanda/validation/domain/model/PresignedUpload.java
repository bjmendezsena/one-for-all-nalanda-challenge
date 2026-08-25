package com.nalanda.validation.domain.model;

/**
 * The signed {@code PUT} URL the client uploads the document bytes to.
 */
public record PresignedUpload(String url) {
}
