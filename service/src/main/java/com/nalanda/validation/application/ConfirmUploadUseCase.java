package com.nalanda.validation.application;

import com.nalanda.validation.domain.model.ValidationStatus;
import com.nalanda.validation.domain.port.JobPublisher;
import com.nalanda.validation.domain.port.ValidationRequestRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Accepts the work and hands it off. It performs no storage I/O — confirm never verifies the
 * upload (see {@code docs/service/upload-flow.md} § 3) — and never waits for processing.
 */
@Service
public class ConfirmUploadUseCase {

    private final ValidationRequestRepository repository;
    private final JobPublisher jobPublisher;

    public ConfirmUploadUseCase(ValidationRequestRepository repository, JobPublisher jobPublisher) {
        this.repository = repository;
        this.jobPublisher = jobPublisher;
    }

    public ConfirmUploadResult execute(UUID requestId) {
        var request = repository.findById(requestId);
        var wasPendingUpload = request.getStatus() == ValidationStatus.PENDING_UPLOAD;
        request.confirmUpload(); // no-op if already confirmed — see docs/business-rules.md § 6
        var saved = repository.save(request);
        // Only an actual PENDING_UPLOAD -> QUEUED move publishes: a repeated confirm on a request
        // that is still QUEUED must not re-trigger processing (contracts/http-api.md § 2).
        if (wasPendingUpload && saved.getStatus() == ValidationStatus.QUEUED) {
            jobPublisher.publishProcessingRequested(saved.getId());
        }
        return new ConfirmUploadResult(saved.getId(), saved.getStatus());
    }
}
