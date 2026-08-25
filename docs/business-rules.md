# Business rules

Status: living document. This file lives at the top level of `docs/` (not under `docs/service/` or `docs/sdk/`) because it is shared: both the backend and the TypeScript SDK must agree on these rules. It describes the domain/business rules of the validation service — the actual behavior of the system, independent of how it is implemented. It does not repeat architecture (`docs/service/architecture.md`, `docs/sdk/architecture.md`), event/Kafka mechanics (`docs/service/events.md`, `docs/service/kafka.md`), the upload sequence (`docs/service/upload-flow.md`), or the reasoning behind a rule (`README.md § Design trade-offs`, cross-referenced below where relevant).

## 1. Domain concepts

| Concept | Definition |
|---|---|
| `ValidationRequest` | The lifecycle of one document check, from creation to a final verdict. Identified by `requestId`. |
| `DocumentMetadata` | The metadata of the uploaded document: filename, content type, size, storage key/reference. Owned by a `ValidationRequest`. |
| `ValidationResult` | The outcome of processing: `verdict` (`PASS`/`FAIL`), `fields` (extracted data, stubbed), `reason` (present when `FAIL`, or when informative). |

## 2. Status machine

```
PENDING_UPLOAD → QUEUED → PROCESSING → COMPLETED
                                     → FAILED
```

| Status | Meaning | Entered when |
|---|---|---|
| `PENDING_UPLOAD` | The request exists; the client has not yet confirmed the document was uploaded. | A `ValidationRequest` is created. |
| `QUEUED` | The upload is confirmed; the processing event has been published but not yet picked up. | `confirm` succeeds. |
| `PROCESSING` | A consumer has picked up the job and is running the (stubbed) extraction/validation. | The Kafka consumer starts handling the event. |
| `COMPLETED` | Processing finished; a `ValidationResult` is available, regardless of `PASS`/`FAIL` verdict. | Processing finishes without an infrastructure error. |
| `FAILED` | Processing could not produce a result at all (infrastructure/unexpected error) — distinct from a business `FAIL` verdict, see § 5. | An unexpected error occurs during processing. |

Transitions are one-directional; there is no path back from `COMPLETED`/`FAILED` to an earlier status for a given `ValidationRequest`.

**Important distinction:** `FAILED` (a status) and `verdict: "FAIL"` (a result) are different things. A validation that determines the document does not pass business rules (e.g. wrong content type, bad size) still reaches status `COMPLETED` with `verdict: "FAIL"` — the check ran successfully and produced a conclusive answer. Status `FAILED` is reserved for cases where the system could not complete the check at all.

## 3. Endpoint business semantics

This section states what each endpoint means for the domain, not its wire format (see `docs/service/upload-flow.md` for the exact request/response shapes and sequence).

| Endpoint | Business meaning |
|---|---|
| Create validation | Registers intent to validate a document. Returns a `requestId` and upload instructions. Does not require the document to exist yet. Status starts at `PENDING_UPLOAD`. |
| Confirm upload | Client-asserted signal that the document bytes have been fully uploaded to storage. This is the trigger that moves the request out of `PENDING_UPLOAD` and enqueues processing. The backend does not otherwise learn that an upload happened (see `README.md § Design trade-offs → Document upload flow`). |
| Get validation | Read-only. Returns the current status, and the `ValidationResult` once available (from `COMPLETED` onward). Never mutates state. |

## 4. Input validation rules

- `filename` and `contentType` are required at creation time.
- `contentType` must be a non-empty MIME type string at creation time (format validation only — whether it is *supported* is a separate business rule evaluated at processing time, § 5).
- Requests failing structural validation are rejected synchronously with a `400`-class Problem Details response (see `README.md § Design trade-offs → API error format`) — they never reach `PENDING_UPLOAD`.

## 5. Processing rule (deterministic stub)

The processing step is a deterministic stub (no real extraction/OCR/LLM call). It evaluates the uploaded document against two conditions, in this order:

1. **Supported content type:** the only supported content type is `application/pdf`. Any other content type → `verdict: "FAIL"`, `reason: "unsupported content type"`.
2. **File size range:** the file must be strictly greater than 0 bytes and no larger than 15 MB (15 × 1024 × 1024 bytes).
   - Size `= 0` → `verdict: "FAIL"`, `reason: "empty file"`.
   - Size `> 15 MB` → `verdict: "FAIL"`, `reason: "file too large"`.
3. If both conditions pass → `verdict: "PASS"`.

This rule is deterministic and reproducible: the same `(contentType, size)` pair always yields the same verdict, which is what makes it trivial to cover with automated tests without depending on file content.

Rationale for the specific thresholds (PDF-only, 15 MB ceiling) is recorded in `README.md § Design trade-offs → Deterministic verdict rule for the processing stub`.

## 6. Idempotency rules

| Rule | Behavior |
|---|---|
| Scope | An `Idempotency-Key` is scoped to the create-validation endpoint. |
| Expiration | None. A key remains valid for as long as its associated `ValidationRequest` exists — there is no TTL. |
| Same key, same request | Returns the original `requestId` and its current status; no new resource is created. The upload instructions are re-signed over the already stored storage key, so the returned `uploadUrl` is a fresh signature of the same object — a presigned URL expires and is never persisted, so the literal original URL cannot be reproduced. |
| Same key, different body | Returns the original resource's response, ignoring the new body's contents. No comparison against the original body is performed. |
| Confirm/upload replay | `confirm` is safe to call more than once: if the `ValidationRequest` is no longer in `PENDING_UPLOAD`, it returns success with the current status instead of re-triggering processing. |

Rationale for these specific choices (no TTL, ignore conflicting body) is recorded in `README.md § Design trade-offs → Idempotency-Key concrete rules`.

## 7. Error reporting rule

Every rejection — structural validation failure, unsupported content type, oversized/empty file, not-found `requestId` — must be reported with a clear, specific reason a client can act on or display; a generic/opaque failure is not acceptable. The wire format for this is RFC 7807 Problem Details (see `README.md § Design trade-offs → API error format`); this section only states the business requirement that the *reason* must always be meaningful, independent of the wire format.
