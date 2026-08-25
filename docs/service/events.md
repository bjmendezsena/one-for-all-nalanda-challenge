# Events (service)

Status: living document. This file lives under `docs/service/`. It is the event catalog: what events exist, their payload shape, who produces them, and who consumes them. Kafka-specific configuration (topic name, consumer group, serialization, partitioning, duplicate handling) lives in `docs/service/kafka.md`, not here. The reasoning behind the design (single event vs two, thin vs fat payload) lives in `docs/design-trade-offs.md § Event/messaging design`.

## 1. Event catalog

| Event | Producer | Consumer | Triggered by |
|---|---|---|---|
| `ProcessingRequested` | `ConfirmUploadUseCase` (via `JobPublisher`) | `KafkaJobConsumer` (implements `JobConsumer`) | A `ValidationRequest` transitions from `PENDING_UPLOAD` to `QUEUED` (i.e. `confirm` succeeds) |

This is the only event in the system. There is no separate "processing completed" event — see § 2.

## 2. Why there is no "processing completed" event

The assignment asks for a clear separation between "accept work" and "finish work". That separation is satisfied structurally, not via a second event:

- **Accept work** = the HTTP layer (`ConfirmUploadUseCase`) returns as soon as the event is published — it never waits for processing.
- **Finish work** = the Kafka consumer (`KafkaJobConsumer`) picks up the event later, runs the (stubbed) validation, and writes the final `COMPLETED`/`FAILED` state directly to Postgres.

A client learns the outcome by polling `GET /api/v1/validations/{requestId}` (or via the SDK's `waitForCompletion`) — it reads the current row in Postgres, not a Kafka event. Nothing else in this slice needs to react to "processing finished", so publishing a second event would have no consumer and add no value.

## 3. `ProcessingRequested`

### 3.1 Payload

```json
{
  "validationRequestId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

| Field | Type | Meaning |
|---|---|---|
| `validationRequestId` | UUID (string) | The id of the `ValidationRequest` to process. |

This is a **thin event**: it carries only the id, not the document metadata or any business fields. The consumer always re-reads the current `ValidationRequest` from Postgres via `ValidationRequestRepository.findById(...)` before acting on it. Postgres remains the single source of truth; the event is purely a wake-up signal.

### 3.2 Producer

`KafkaJobPublisher` (implements `domain/port/JobPublisher`), called from `ConfirmUploadUseCase` immediately after `ValidationRequest.confirmUpload()` succeeds and the request is persisted in status `QUEUED`.

```java
// application/ConfirmUploadUseCase.java (excerpt)
request.confirmUpload();
repository.save(request);
if (request.getStatus() == ValidationStatus.QUEUED) {
    jobPublisher.publishProcessingRequested(request.getId());
}
```

### 3.3 Consumer

`KafkaJobConsumer` (implements `domain/port/JobConsumer`), invoked by a `@KafkaListener`. On receipt:

1. `findById(validationRequestId)` — throws `ValidationRequestNotFoundException` if the id doesn't exist (should not happen in practice; treated as a processing error, not retried indefinitely).
2. `startProcessing()` — transitions `QUEUED → PROCESSING`. If the request is **not** in `QUEUED` (e.g. this is a duplicate delivery of an already-processed event), this is caught and treated as a no-op — see § 4.
3. Evaluate the deterministic stub rule from `docs/business-rules.md` § 5 against the document's content type and size.
4. `complete(result)` or `fail()`, then `repository.save(request)`.

## 4. Duplicate delivery (idempotent consumption)

Kafka delivers at-least-once. The same `ProcessingRequested` message can be delivered more than once (consumer restart, rebalance, retry). The consumer does not deduplicate by message id — it relies on the `ValidationRequest` status machine itself:

```java
try {
    request.startProcessing();
} catch (InvalidStatusTransitionException ex) {
    // Already processed (or being processed) — safe no-op, not an error.
    return;
}
```

Because `startProcessing()` only succeeds from `QUEUED`, a duplicate delivery arriving after the first one has already moved the request to `PROCESSING`, `COMPLETED`, or `FAILED` is rejected by the domain model itself and is swallowed here rather than reprocessing the document or propagating an error.
