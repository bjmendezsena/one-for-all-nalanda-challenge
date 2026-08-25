package com.nalanda.validation.domain.model;

import java.util.Map;

/**
 * The outcome of a validation that actually ran. A {@code FAIL} verdict is a conclusive
 * business answer and is not the same thing as status {@code FAILED}
 * (see {@code docs/business-rules.md} § 2).
 */
public record ValidationResult(Verdict verdict, Map<String, Object> fields, String reason) {

    public enum Verdict { PASS, FAIL }
}
