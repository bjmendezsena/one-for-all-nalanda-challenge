package com.nalanda.validation.application;

import com.nalanda.validation.domain.model.ValidationRequest;
import com.nalanda.validation.domain.port.ValidationRequestRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read-only: returns the current state of a request and never mutates it. */
@Service
public class GetValidationUseCase {

    private final ValidationRequestRepository repository;

    public GetValidationUseCase(ValidationRequestRepository repository) {
        this.repository = repository;
    }

    public ValidationRequest execute(UUID requestId) {
        return repository.findById(requestId);
    }
}
