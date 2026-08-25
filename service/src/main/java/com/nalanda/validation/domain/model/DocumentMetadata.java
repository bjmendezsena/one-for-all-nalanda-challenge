package com.nalanda.validation.domain.model;

/**
 * Metadata of the document a {@link ValidationRequest} is about. Immutable value object:
 * {@code sizeInBytes} is {@code 0} at creation and only becomes known during processing,
 * through {@link #withSizeInBytes(long)}.
 */
public record DocumentMetadata(String filename, String contentType, long sizeInBytes, String storageKey) {

    public DocumentMetadata withSizeInBytes(long discoveredSizeInBytes) {
        return new DocumentMetadata(filename, contentType, discoveredSizeInBytes, storageKey);
    }
}
