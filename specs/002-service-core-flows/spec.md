# Feature Specification: Service core flows (validation lifecycle end-to-end)

**Feature Branch**: `002-service-core-flows`

**Created**: 2026-08-25

**Status**: Draft

**Input**: User description: "Implementa el service basado en la documentación. Implementa todos los flujos, los schemas de la base de datos, el liquibase, etc. Incluye la integración con Kafka (publisher + consumer)."

## Clarifications

### Session 2026-08-25

- Q: Where is the `Idempotency-Key` persisted? → A: As a nullable column on the validation request's own table, with a unique index — no separate table, no TTL, no body hash.
- Q: How is `ValidationResult.fields` persisted? → A: As serialized JSON in a text column — identical across the production and test databases; `fields` is never queried or filtered.
- Q: What generates the schema in persistence tests? → A: The same versioned migrations that run in production, replayed against the in-memory test database — so the migrations themselves are covered and cannot drift from the entities.
- Q: Is a concurrency guard added for concurrent confirms? → A: No optimistic locking. The status machine is the only guard, as the documentation describes; no version column is introduced.
- Q: `docs/service/upload-flow.md` § 2.3 specifies a confirm response body, while the controller snippet in `docs/service/code_rules.md` § 6 shows an empty response. Which wins? → A: The body `{requestId, status}` wins — the endpoint-shape doc and the idempotency rule both require the current status to be observable in the response. The code-rules snippet is corrected in the same change (approved amendment to a non-negotiable doc).
- Q: `docs/service/code_rules.md` § 1 declares the document metadata field `final`, but `docs/service/upload-flow.md` § 2.1 says the size is `0` at creation and discovered during processing. Which wins? → A: The discovered size is recorded and persisted; the field stops being `final` and the entity exposes a transition method for it. The `code_rules.md` § 1 snippet is corrected in the same change (approved amendment).
- Q: On an idempotent create replay, what is returned for the upload instructions? → A: A freshly signed upload URL over the already-stored storage key and content type — same request, no new resource, and a client retrying after a timeout receives a usable URL.

## User Scenarios & Testing _(mandatory)_

### User Story 1 - Register the intent to validate a document (Priority: P1)

An API client registers that it wants a document checked. It sends the document's filename and content type, and receives back a request identifier plus the instructions it needs to upload the document bytes directly to storage. The document itself does not need to exist yet.

**Why this priority**: Nothing else in the lifecycle can happen without a `ValidationRequest` existing. It is the entry point of the whole flow and the only step that mints the identifier every later call depends on.

**Independent Test**: Call create-validation with a valid filename and content type; verify a request identifier, an initial status of `PENDING_UPLOAD`, and upload instructions are returned, and that the request can subsequently be read back.

**Acceptance Scenarios**:

1. **Given** a client with a valid API key, **When** it creates a validation with `filename` and `contentType`, **Then** the response is `201` and contains a `requestId`, `status: PENDING_UPLOAD`, and an `uploadUrl`.
2. **Given** a create request missing `filename` or `contentType`, **When** it is submitted, **Then** it is rejected synchronously with a `400`-class Problem Details body naming the offending field(s), and no `ValidationRequest` is created.
3. **Given** a create request whose `contentType` is an empty/blank string, **When** it is submitted, **Then** it is rejected synchronously with a `400`-class Problem Details body and no `ValidationRequest` is created.
4. **Given** a create request carrying an `Idempotency-Key` already used before, **When** it is submitted again with any body, **Then** the original `requestId` and status are returned together with a freshly signed `uploadUrl` for the already-stored document, and no second `ValidationRequest` is created.
5. **Given** a request without a valid API key, **When** any endpoint is called, **Then** it is rejected with `401` and no state is created or changed.

---

### User Story 2 - Confirm the upload and get the work accepted (Priority: P1)

Having uploaded the document bytes directly to storage, the client tells the service the upload is done. The service accepts the work, moves the request out of `PENDING_UPLOAD`, and returns immediately — it never waits for the document to be checked.

**Why this priority**: This is the "accept work" half of the asynchronous separation the system exists to demonstrate. Without it a created request can never advance.

**Independent Test**: Create a validation, call confirm, and verify the response is `202` with status `QUEUED`, that the response returns without waiting for processing, and that a processing job was handed off exactly once.

**Acceptance Scenarios**:

1. **Given** a request in `PENDING_UPLOAD`, **When** the client confirms the upload, **Then** the response is `202` with `status: QUEUED` and a processing job is handed off exactly once.
2. **Given** a request already in `QUEUED` (or later), **When** the client confirms again, **Then** the call succeeds and returns the current status, and no additional processing job is handed off.
3. **Given** an unknown `requestId`, **When** the client confirms, **Then** the response is `404` with a Problem Details body naming the unknown request.
4. **Given** a confirmed request, **When** the confirm response is returned, **Then** the response does not depend on the outcome of processing having been computed.

---

### User Story 3 - The document is checked asynchronously (Priority: P1)

Separately from any client call, the service picks up the accepted job, discovers the real size of the uploaded document from storage, applies the deterministic check, and records a final outcome.

**Why this priority**: This is the "finish work" half of the separation. It is what turns an accepted request into an answer.

**Independent Test**: Hand off a processing job for a `QUEUED` request whose stored document has a known content type and size, then verify the request reaches `COMPLETED` with the expected verdict, or `FAILED` when the check could not be run at all.

**Acceptance Scenarios**:

1. **Given** a `QUEUED` request for a `application/pdf` document of 1 byte–15 MB, **When** the job is processed, **Then** the request reaches `COMPLETED` with `verdict: PASS` and no failure reason.
2. **Given** a `QUEUED` request whose content type is not `application/pdf`, **When** the job is processed, **Then** the request reaches `COMPLETED` with `verdict: FAIL` and `reason: "unsupported content type"`.
3. **Given** a `QUEUED` request whose stored document is 0 bytes (or was never uploaded), **When** the job is processed, **Then** the request reaches `COMPLETED` with `verdict: FAIL` and `reason: "empty file"`.
4. **Given** a `QUEUED` request whose stored document is larger than 15 MB, **When** the job is processed, **Then** the request reaches `COMPLETED` with `verdict: FAIL` and `reason: "file too large"`.
5. **Given** a request that is no longer `QUEUED`, **When** the same processing job is delivered again, **Then** the delivery is a safe no-op: the recorded outcome is not recomputed, not overwritten, and no error is surfaced.
6. **Given** an unexpected/infrastructure error while checking a `PROCESSING` request, **When** the job runs, **Then** the request reaches `FAILED` — never `COMPLETED` with an invented verdict.

---

### User Story 4 - Read the current state and the outcome (Priority: P1)

The client polls the service with the request identifier to see where the request stands, and reads the outcome once it is available.

**Why this priority**: Without a read endpoint the outcome of the asynchronous work is unobservable; the whole flow would be write-only. It is also the only way the SDK's wait-for-completion behavior can work.

**Independent Test**: Read a request at each status and verify the response reflects the current status, and that the result appears exactly from `COMPLETED` onward.

**Acceptance Scenarios**:

1. **Given** a request that has not completed, **When** it is read, **Then** the response is `200` with the current status and no result.
2. **Given** a `COMPLETED` request, **When** it is read, **Then** the response is `200` with `status: COMPLETED` and the result (verdict, extracted fields, reason).
3. **Given** an unknown `requestId`, **When** it is read, **Then** the response is `404` with a Problem Details body.
4. **Given** any read, **When** it is performed, **Then** it never changes the request's state.

---

### Edge Cases

- Client confirms without ever uploading: confirm still succeeds; processing later finds no stored bytes, treats size as 0, and completes with `verdict: FAIL`, `reason: "empty file"`.
- Client uploads but never confirms: the request stays in `PENDING_UPLOAD` indefinitely — no reconciliation or garbage-collection exists in this slice.
- The same processing job is delivered more than once: guarded by the status machine; the second delivery is a no-op.
- An `Idempotency-Key` is reused with a different body: the original resource's response is returned; the new body is ignored and never compared.
- A processing job references an identifier that does not exist: treated as a processing error, not retried indefinitely.
- Content type is syntactically valid but unsupported at creation time: accepted at creation, and only fails at processing time with `verdict: FAIL` — a `FAIL` verdict is never reported as status `FAILED`.
- Storage is unreachable while a job is being processed: the request ends in `FAILED`, and the failure reason surfaced to the client is specific, never a raw stack trace.

## Requirements _(mandatory)_

### Functional Requirements

**Create validation**

- **FR-001**: The system MUST expose a create-validation endpoint that accepts `filename` and `contentType` and returns `201` with a `requestId`, `status: PENDING_UPLOAD`, and an `uploadUrl` the client can use to upload the document bytes directly to storage.
- **FR-002**: The system MUST require both `filename` and `contentType`, and MUST require `contentType` to be a non-empty MIME type string, rejecting structurally invalid requests synchronously before any `ValidationRequest` is created.
- **FR-003**: The system MUST generate a storage key per request and persist the request with its document metadata, with size unset/zero at creation time.
- **FR-004**: The system MUST accept an optional `Idempotency-Key` header on create; when a key has been seen before it MUST return the original request's identifier and status plus freshly signed upload instructions for the already-stored document, and MUST NOT create a second request, regardless of the new body's contents.
- **FR-005**: The system MUST NOT expire `Idempotency-Key` values — a key stays valid for as long as its `ValidationRequest` exists.

**Confirm upload**

- **FR-006**: The system MUST expose a confirm-upload endpoint that takes no body and moves a request from `PENDING_UPLOAD` to `QUEUED`, returning `202` with a body carrying the `requestId` and the current status.
- **FR-007**: The system MUST hand off exactly one processing job when, and only when, a request actually transitions `PENDING_UPLOAD → QUEUED`.
- **FR-008**: The system MUST treat a repeated confirm as a safe no-op: it returns success with the current status, does not error, and does not hand off a second processing job.
- **FR-009**: The confirm step MUST NOT perform any storage I/O and MUST NOT verify that the upload actually happened.
- **FR-010**: The system MUST return the confirm response without waiting for the document to be checked.

**Asynchronous processing**

- **FR-011**: The system MUST consume handed-off processing jobs asynchronously, outside any client request.
- **FR-012**: A processing job MUST carry only the validation request identifier; the consumer MUST re-read the current request from the datastore before acting on it.
- **FR-013**: The consumer MUST transition the request `QUEUED → PROCESSING`, then discover the stored document's real size, record it on the request, then apply the deterministic check.
- **FR-014**: The deterministic check MUST evaluate, in order: (a) content type must be `application/pdf`, otherwise `verdict: FAIL`, `reason: "unsupported content type"`; (b) size must be greater than 0 and at most 15 MB, otherwise `verdict: FAIL` with `reason: "empty file"` or `"file too large"` respectively; (c) otherwise `verdict: PASS`.
- **FR-015**: The system MUST record a `COMPLETED` status with a result whenever the check produced a conclusive answer, including when the verdict is `FAIL`.
- **FR-016**: The system MUST record `FAILED` only when the check could not be run at all, and MUST NOT conflate it with `verdict: FAIL`.
- **FR-017**: A repeated delivery of the same processing job MUST be a no-op enforced by the status machine, not by deduplicating messages.
- **FR-018**: Processing jobs MUST be keyed by the validation request identifier so all jobs for one request stay ordered relative to each other.

**Read**

- **FR-019**: The system MUST expose a read endpoint returning the request's current status, plus the result from `COMPLETED` onward, and MUST NOT mutate state.

**Status machine**

- **FR-020**: The system MUST enforce the one-directional status machine `PENDING_UPLOAD → QUEUED → PROCESSING → COMPLETED | FAILED`, rejecting any other transition, and MUST NOT allow a return to an earlier status.
- **FR-021**: Status changes MUST only be possible through the request's own transition behavior — never by external mutation of its state.

**Persistence & schema**

- **FR-022**: The system MUST persist validation requests, their document metadata, their result, and their idempotency key durably, so state survives a restart and is shared across instances. The idempotency key MUST live as a nullable attribute of the validation request itself, not as a separate record.
- **FR-023**: The database schema MUST be created and evolved exclusively through versioned migrations — never through manual or framework-generated DDL at runtime. The same migrations MUST also be what builds the schema for automated persistence tests, so the migrations are exercised and cannot drift from the persisted model.
- **FR-024**: The system MUST guarantee that one idempotency key maps to at most one validation request, enforced at the datastore level.
- **FR-025**: Persisted table names MUST be namespaced to this service's domain.
- **FR-026**: The result's extracted fields MUST be persisted as serialized JSON in a plain text attribute — they are never queried, filtered, or indexed, and the representation MUST behave identically in the production and test databases.
- **FR-027**: No optimistic-locking or version attribute MUST be introduced: repeat-safety is enforced solely by the status machine.

**Errors, security, observability**

- **FR-028**: Every error response MUST be an RFC 7807 Problem Details body carrying a specific, actionable reason; no raw stack trace and no second, ad-hoc error shape MUST ever reach a client.
- **FR-029**: An unknown `requestId` on read or confirm MUST produce `404` with a Problem Details body.
- **FR-030**: All endpoints MUST require a static API key header and MUST reject a missing or incorrect key with `401` before any state is created or changed.
- **FR-031**: Requests MUST be correlated in structured logs, and the body of a failed validation MUST NOT be dumped verbatim into logs.
- **FR-032**: Business thresholds (the supported content type, the size bounds) MUST be expressed as named constants next to the rule they encode.

**Automated tests**

- **FR-033**: Every behavior in this specification MUST ship with automated tests written in the same change — covering each status transition (valid and rejected), each verdict outcome (`PASS` and each `FAIL` reason), both idempotency rules (repeated create with the same key, repeated confirm), duplicate job delivery, every error response and its status code, and the persistence/schema round-trip.
- **FR-034**: The automated test suite MUST be deterministic and MUST run to green without any container, broker, or external service running.

### Key Entities

- **ValidationRequest**: The lifecycle of one document check, from creation to a final verdict. Identified by `requestId`. Holds the current status, owns its document metadata, and holds the result once available. May carry the idempotency key it was created with.
- **DocumentMetadata**: The metadata of the uploaded document — filename, content type, size in bytes, and the storage key/reference. Owned by a `ValidationRequest`; has no identity of its own.
- **ValidationResult**: The outcome of processing — a verdict (`PASS`/`FAIL`), the extracted fields (stubbed), and a reason (present when `FAIL`, or when informative). Owned by a `ValidationRequest`.
- **ProcessingRequested job**: The asynchronous hand-off from "accept work" to "finish work". Carries only the validation request identifier; the datastore remains the single source of truth.

## Success Criteria _(mandatory)_

### Measurable Outcomes

- **SC-001**: A client can go from creating a validation to reading a final verdict using only the documented endpoints, with no manual step in between.
- **SC-002**: Confirming an upload returns to the client without the outcome having been computed — accepting work and finishing work are observably separate.
- **SC-003**: Every status transition, verdict outcome (`PASS`, and each `FAIL` reason), idempotency rule, and error reason described in the business rules has an automated test that fails if the rule is broken.
- **SC-004**: Repeating any client call (create with the same key, confirm twice) or re-delivering the same processing job leaves the system in exactly the same state as a single call, with exactly one document check performed.
- **SC-005**: 100% of error responses returned by the service carry a specific, machine-readable reason in the standard problem shape; none returns an opaque or generic failure.
- **SC-006**: A validation request that has been checked reports the same status and result after a service restart.
- **SC-007**: The database schema can be built from empty by replaying the versioned migrations alone.
- **SC-008**: The full automated test suite runs to green without requiring any container or external service to be running.

## Assumptions

- The two artifacts' contract is unchanged: this feature implements the endpoints, statuses, and single event already described in the documentation, and introduces no new endpoint, field, status, or event.
- The SDK (`sdk/`) is out of scope for this feature; only `service/` is implemented. The HTTP contract it consumes is not modified.
- The local infrastructure (`docker/`) already exists and is not modified: the datastore, message broker, and object storage come up as they already do.
- The processing step remains the documented deterministic stub — no real extraction, OCR, or model call — and the extracted `fields` remain stubbed.
- The API key is a stub by decision, configured with a safe local default; it is not expanded into a fuller auth scheme.
- Presigned upload URL expiry uses a reasonable default; the upload is a direct client↔storage interaction and its failures are not the service's concern.
- Volume expectations are development-scale; no throughput, latency, or partitioning target beyond the documented single local partition is being met by this slice.
- There is no reconciliation job for requests that were uploaded but never confirmed, and no retention/cleanup of idempotency keys.
