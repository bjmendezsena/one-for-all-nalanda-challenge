package com.nalanda.validation.application;

import com.nalanda.validation.domain.model.DocumentStorageException;
import com.nalanda.validation.domain.model.PresignedUpload;
import com.nalanda.validation.domain.port.DocumentStoragePort;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written fake storage. Every signature it hands out is distinct, so a test can tell a fresh
 * upload URL from a replayed one.
 */
class FakeDocumentStoragePort implements DocumentStoragePort {

    private final List<String> signedStorageKeys = new ArrayList<>();
    private long reportedSizeInBytes;
    private boolean isBroken;

    @Override
    public PresignedUpload createPresignedUpload(String storageKey, String contentType) {
        failIfBroken("sign an upload URL for");
        signedStorageKeys.add(storageKey);
        return new PresignedUpload(
                "https://storage.test/%s?signature=%d".formatted(storageKey, signedStorageKeys.size()));
    }

    @Override
    public long sizeOf(String storageKey) {
        failIfBroken("read the size of");
        return reportedSizeInBytes;
    }

    void reportSize(long sizeInBytes) {
        this.reportedSizeInBytes = sizeInBytes;
    }

    void breakStorage() {
        this.isBroken = true;
    }

    List<String> signedStorageKeys() {
        return List.copyOf(signedStorageKeys);
    }

    private void failIfBroken(String operation) {
        if (isBroken) {
            throw new DocumentStorageException("Could not " + operation + " the document", new IllegalStateException());
        }
    }
}
