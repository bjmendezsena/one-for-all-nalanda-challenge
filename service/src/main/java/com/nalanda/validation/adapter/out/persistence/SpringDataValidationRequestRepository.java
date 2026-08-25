package com.nalanda.validation.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataValidationRequestRepository extends JpaRepository<ValidationRequestEntity, UUID> {

    Optional<ValidationRequestEntity> findByIdempotencyKey(String idempotencyKey);
}
