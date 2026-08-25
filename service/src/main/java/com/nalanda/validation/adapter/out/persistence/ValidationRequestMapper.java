package com.nalanda.validation.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nalanda.validation.domain.model.DocumentMetadata;
import com.nalanda.validation.domain.model.ValidationRequest;
import com.nalanda.validation.domain.model.ValidationResult;
import com.nalanda.validation.domain.model.ValidationStatus;
import java.util.Map;

/**
 * Hand-written field-by-field mapping between the domain entity and its persistence record — no
 * MapStruct, per {@code docs/service/code_rules.md} § 3. The only place {@code result_fields} is
 * (de)serialized, and the only place a {@link ValidationRequest} is rehydrated without running its
 * transitions.
 */
class ValidationRequestMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> FIELDS_TYPE = new TypeReference<>() {};

    private ValidationRequestMapper() {
    }

    static ValidationRequestEntity toEntity(ValidationRequest domain, String idempotencyKey) {
        var document = domain.getDocument();
        var result = domain.getResult();
        return new ValidationRequestEntity(
                domain.getId(),
                domain.getStatus().name(),
                document.filename(),
                document.contentType(),
                document.sizeInBytes(),
                document.storageKey(),
                result == null ? null : result.verdict().name(),
                result == null ? null : serializeFields(result.fields()),
                result == null ? null : result.reason(),
                idempotencyKey);
    }

    static ValidationRequest toDomain(ValidationRequestEntity entity) {
        var document = new DocumentMetadata(
                entity.getFilename(), entity.getContentType(), entity.getSizeInBytes(), entity.getStorageKey());
        return ValidationRequest.restore(
                entity.getId(), document, ValidationStatus.valueOf(entity.getStatus()), toResult(entity));
    }

    private static ValidationResult toResult(ValidationRequestEntity entity) {
        if (entity.getResultVerdict() == null) {
            return null;
        }
        return new ValidationResult(
                ValidationResult.Verdict.valueOf(entity.getResultVerdict()),
                deserializeFields(entity.getResultFields()),
                entity.getResultReason());
    }

    private static String serializeFields(Map<String, Object> fields) {
        if (fields == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(fields);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize validation result fields", ex);
        }
    }

    private static Map<String, Object> deserializeFields(String json) {
        if (json == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, FIELDS_TYPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Could not deserialize validation result fields", ex);
        }
    }
}
