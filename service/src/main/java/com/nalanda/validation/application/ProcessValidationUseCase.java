package com.nalanda.validation.application;

import com.nalanda.validation.domain.model.DocumentMetadata;
import com.nalanda.validation.domain.model.InvalidStatusTransitionException;
import com.nalanda.validation.domain.model.ValidationRequest;
import com.nalanda.validation.domain.model.ValidationResult;
import com.nalanda.validation.domain.port.DocumentStoragePort;
import com.nalanda.validation.domain.port.ValidationRequestRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finishes the work: discovers the document size, applies the deterministic stub rule of
 * {@code docs/business-rules.md} § 5, and writes the final state. A conclusive {@code FAIL} verdict
 * still reaches {@code COMPLETED}; only a failure to run the check at all reaches {@code FAILED}.
 */
@Service
public class ProcessValidationUseCase {

    static final String SUPPORTED_CONTENT_TYPE = "application/pdf";
    static final long MAX_DOCUMENT_SIZE_BYTES = 15L * 1024 * 1024;
    static final long MIN_DOCUMENT_SIZE_BYTES_EXCLUSIVE = 0L;
    static final String UNSUPPORTED_CONTENT_TYPE_REASON = "unsupported content type";
    static final String EMPTY_FILE_REASON = "empty file";
    static final String FILE_TOO_LARGE_REASON = "file too large";

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessValidationUseCase.class);

    private final ValidationRequestRepository repository;
    private final DocumentStoragePort storage;

    public ProcessValidationUseCase(ValidationRequestRepository repository, DocumentStoragePort storage) {
        this.repository = repository;
        this.storage = storage;
    }

    public void execute(UUID requestId) {
        var request = repository.findById(requestId);
        try {
            request.startProcessing();
        } catch (InvalidStatusTransitionException ex) {
            // Duplicate delivery of an already-handled event — a safe no-op, not an error
            // (docs/service/events.md § 4).
            return;
        }
        repository.save(request);
        try {
            request.recordDiscoveredSize(storage.sizeOf(request.getDocument().storageKey()));
            request.complete(evaluate(request.getDocument()));
        } catch (RuntimeException ex) {
            LOGGER.error("Could not run the validation of request {}", requestId, ex);
            request.fail();
        }
        repository.save(request);
    }

    private static ValidationResult evaluate(DocumentMetadata document) {
        if (!SUPPORTED_CONTENT_TYPE.equals(document.contentType())) {
            return failed(document, UNSUPPORTED_CONTENT_TYPE_REASON);
        }
        if (document.sizeInBytes() <= MIN_DOCUMENT_SIZE_BYTES_EXCLUSIVE) {
            return failed(document, EMPTY_FILE_REASON);
        }
        if (document.sizeInBytes() > MAX_DOCUMENT_SIZE_BYTES) {
            return failed(document, FILE_TOO_LARGE_REASON);
        }
        return new ValidationResult(ValidationResult.Verdict.PASS, extractedFields(document), null);
    }

    private static ValidationResult failed(DocumentMetadata document, String reason) {
        return new ValidationResult(ValidationResult.Verdict.FAIL, extractedFields(document), reason);
    }

    /** Stubbed extraction — no OCR, no LLM call (docs/business-rules.md § 5). */
    private static Map<String, Object> extractedFields(DocumentMetadata document) {
        return Map.of("filename", document.filename());
    }
}
