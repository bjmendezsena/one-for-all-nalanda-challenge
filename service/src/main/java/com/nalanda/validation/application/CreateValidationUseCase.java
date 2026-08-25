package com.nalanda.validation.application;

import com.nalanda.validation.domain.model.DocumentMetadata;
import com.nalanda.validation.domain.model.ValidationRequest;
import com.nalanda.validation.domain.port.DocumentStoragePort;
import com.nalanda.validation.domain.port.ValidationRequestRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Registers the intent to validate a document and hands the client the instructions to upload it.
 * The document itself does not have to exist yet.
 */
@Service
public class CreateValidationUseCase {

    private static final long UNKNOWN_SIZE_IN_BYTES = 0L;

    private final ValidationRequestRepository repository;
    private final DocumentStoragePort storage;

    public CreateValidationUseCase(ValidationRequestRepository repository, DocumentStoragePort storage) {
        this.repository = repository;
        this.storage = storage;
    }

    public CreateValidationResult execute(CreateValidationCommand command, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = repository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                // The resource is unchanged; only the signature is fresh, because a presigned URL
                // expires and is never persisted (research.md R-006). The new body is ignored.
                return resultFor(existing.get());
            }
        }
        var document = new DocumentMetadata(
                command.filename(),
                command.contentType(),
                UNKNOWN_SIZE_IN_BYTES,
                generateStorageKey(command.filename()));
        var request = ValidationRequest.create(document);
        var result = resultFor(request);
        repository.save(request, idempotencyKey);
        return result;
    }

    private CreateValidationResult resultFor(ValidationRequest request) {
        var document = request.getDocument();
        var presignedUpload = storage.createPresignedUpload(document.storageKey(), document.contentType());
        return new CreateValidationResult(request.getId(), request.getStatus(), presignedUpload.url());
    }

    private static String generateStorageKey(String filename) {
        return UUID.randomUUID() + "/" + filename;
    }
}
