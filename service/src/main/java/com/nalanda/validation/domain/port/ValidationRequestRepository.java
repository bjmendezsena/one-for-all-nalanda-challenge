package com.nalanda.validation.domain.port;

import com.nalanda.validation.domain.model.ValidationRequest;
import java.util.Optional;
import java.util.UUID;

public interface ValidationRequestRepository {

    ValidationRequest save(ValidationRequest request);

    /**
     * Saves the request together with the idempotency key it was created with. The key belongs to
     * the persistence record, not to the domain entity.
     */
    ValidationRequest save(ValidationRequest request, String idempotencyKey);

    /** @throws com.nalanda.validation.domain.model.ValidationRequestNotFoundException if missing */
    ValidationRequest findById(UUID id);

    /** Empty means "this key has not been seen yet", which is a valid business outcome. */
    Optional<ValidationRequest> findByIdempotencyKey(String idempotencyKey);
}
