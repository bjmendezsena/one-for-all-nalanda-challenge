package com.nalanda.validation.adapter.in.web;

import com.nalanda.validation.application.ConfirmUploadResult;
import com.nalanda.validation.application.ConfirmUploadUseCase;
import com.nalanda.validation.application.CreateValidationCommand;
import com.nalanda.validation.application.CreateValidationResult;
import com.nalanda.validation.application.CreateValidationUseCase;
import com.nalanda.validation.application.GetValidationUseCase;
import com.nalanda.validation.domain.model.ValidationRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/validations")
class ValidationController {

    private final CreateValidationUseCase createValidationUseCase;
    private final ConfirmUploadUseCase confirmUploadUseCase;
    private final GetValidationUseCase getValidationUseCase;

    ValidationController(
            CreateValidationUseCase createValidationUseCase,
            ConfirmUploadUseCase confirmUploadUseCase,
            GetValidationUseCase getValidationUseCase) {
        this.createValidationUseCase = createValidationUseCase;
        this.confirmUploadUseCase = confirmUploadUseCase;
        this.getValidationUseCase = getValidationUseCase;
    }

    @PostMapping
    ResponseEntity<CreateValidationResult> create(
            @Valid @RequestBody CreateValidationCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var result = createValidationUseCase.execute(command, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/{requestId}/confirm")
    ResponseEntity<ConfirmUploadResult> confirm(@PathVariable UUID requestId) {
        return ResponseEntity.accepted().body(confirmUploadUseCase.execute(requestId));
    }

    @GetMapping("/{requestId}")
    ValidationRequest get(@PathVariable UUID requestId) {
        return getValidationUseCase.execute(requestId);
    }
}
