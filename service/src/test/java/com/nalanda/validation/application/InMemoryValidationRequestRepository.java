package com.nalanda.validation.application;

import com.nalanda.validation.domain.model.DocumentMetadata;
import com.nalanda.validation.domain.model.ValidationRequest;
import com.nalanda.validation.domain.model.ValidationRequestNotFoundException;
import com.nalanda.validation.domain.port.ValidationRequestRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hand-written fake — the application layer is tested without Mockito
 * ({@code docs/service/code_rules.md} § 7). Stores copies so a test cannot observe a mutation the
 * use case never saved.
 */
class InMemoryValidationRequestRepository implements ValidationRequestRepository {

    private final Map<UUID, ValidationRequest> requestsById = new HashMap<>();
    private final Map<String, UUID> idsByIdempotencyKey = new HashMap<>();

    @Override
    public ValidationRequest save(ValidationRequest request) {
        return save(request, null);
    }

    @Override
    public ValidationRequest save(ValidationRequest request, String idempotencyKey) {
        requestsById.put(request.getId(), copyOf(request));
        if (idempotencyKey != null) {
            idsByIdempotencyKey.put(idempotencyKey, request.getId());
        }
        return copyOf(request);
    }

    @Override
    public ValidationRequest findById(UUID id) {
        var stored = requestsById.get(id);
        if (stored == null) {
            throw new ValidationRequestNotFoundException(id);
        }
        return copyOf(stored);
    }

    @Override
    public Optional<ValidationRequest> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(idsByIdempotencyKey.get(idempotencyKey)).map(requestsById::get).map(this::copyOf);
    }

    int count() {
        return requestsById.size();
    }

    private ValidationRequest copyOf(ValidationRequest request) {
        var document = request.getDocument();
        return ValidationRequest.restore(
                request.getId(),
                new DocumentMetadata(
                        document.filename(), document.contentType(), document.sizeInBytes(), document.storageKey()),
                request.getStatus(),
                request.getResult());
    }
}
