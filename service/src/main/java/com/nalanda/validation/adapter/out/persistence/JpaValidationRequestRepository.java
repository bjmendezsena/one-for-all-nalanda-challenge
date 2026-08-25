package com.nalanda.validation.adapter.out.persistence;

import com.nalanda.validation.domain.model.ValidationRequest;
import com.nalanda.validation.domain.model.ValidationRequestNotFoundException;
import com.nalanda.validation.domain.port.ValidationRequestRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaValidationRequestRepository implements ValidationRequestRepository {

    private final SpringDataValidationRequestRepository springDataRepository;

    JpaValidationRequestRepository(SpringDataValidationRequestRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public ValidationRequest save(ValidationRequest request) {
        // The idempotency key belongs to the record, not to the domain entity, so a save that does
        // not carry one must keep the key the request was originally created with.
        var storedIdempotencyKey = springDataRepository
                .findById(request.getId())
                .map(ValidationRequestEntity::getIdempotencyKey)
                .orElse(null);
        return save(request, storedIdempotencyKey);
    }

    @Override
    public ValidationRequest save(ValidationRequest request, String idempotencyKey) {
        var entity = ValidationRequestMapper.toEntity(request, idempotencyKey);
        return ValidationRequestMapper.toDomain(springDataRepository.save(entity));
    }

    @Override
    public ValidationRequest findById(UUID id) {
        return springDataRepository
                .findById(id)
                .map(ValidationRequestMapper::toDomain)
                .orElseThrow(() -> new ValidationRequestNotFoundException(id));
    }

    @Override
    public Optional<ValidationRequest> findByIdempotencyKey(String idempotencyKey) {
        return springDataRepository.findByIdempotencyKey(idempotencyKey).map(ValidationRequestMapper::toDomain);
    }
}
