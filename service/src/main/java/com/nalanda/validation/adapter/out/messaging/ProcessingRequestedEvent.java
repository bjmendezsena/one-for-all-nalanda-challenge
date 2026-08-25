package com.nalanda.validation.adapter.out.messaging;

import java.util.UUID;

/**
 * The only event in the system. Thin on purpose: the consumer re-reads the request from the
 * database, which stays the single source of truth (see {@code docs/service/events.md} § 3.1).
 */
public record ProcessingRequestedEvent(UUID validationRequestId) {
}
