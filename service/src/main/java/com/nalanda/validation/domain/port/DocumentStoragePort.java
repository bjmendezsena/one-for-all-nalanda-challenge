package com.nalanda.validation.domain.port;

import com.nalanda.validation.domain.model.PresignedUpload;

public interface DocumentStoragePort {

    PresignedUpload createPresignedUpload(String storageKey, String contentType);

    /** @return the size storage reports, or {@code 0} when the object does not exist */
    long sizeOf(String storageKey);
}
