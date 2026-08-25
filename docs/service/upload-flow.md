# Upload flow (service)

Status: living document. This file lives under `docs/service/`. It describes the exact, step-by-step sequence of creating a validation, uploading a document, and confirming it — request/response shapes and edge cases. It does not repeat why presigned URLs + MinIO were chosen (see `README.md § Design trade-offs → Document upload flow`), the business rules being applied (see `docs/business-rules.md`), or the event published as part of this flow (see `docs/service/events.md`).

## 1. Sequence

```
Client                          Service (service/)                  MinIO                 Kafka
  │                                    │                               │                     │
  │  POST /api/v1/validations         │                               │                     │
  │  { filename, contentType }        │                               │                     │
  │  [Idempotency-Key: <key>]         │                               │                     │
  ├───────────────────────────────────►                               │                     │
  │                                    │  createPresignedUpload(...)  │                     │
  │                                    ├──────────────────────────────►                     │
  │                                    │◄──────────────────────────────┤                     │
  │                                    │  save ValidationRequest       │                     │
  │                                    │  (status = PENDING_UPLOAD)    │                     │
  │  201 { requestId, status,         │                               │                     │
  │        uploadUrl }                │                               │                     │
  ◄───────────────────────────────────┤                               │                     │
  │                                    │                               │                     │
  │  PUT <uploadUrl>                  │                               │                     │
  │  <document bytes>                 │                               │                     │
  ├────────────────────────────────────────────────────────────────────►                     │
  │◄────────────────────────────────────────────────────────────────────┤ 200 OK              │
  │                                    │                               │                     │
  │  POST /validations/{id}/confirm   │                               │                     │
  ├───────────────────────────────────►                               │                     │
  │                                    │  status: PENDING_UPLOAD       │                     │
  │                                    │           → QUEUED            │                     │
  │                                    │  publish ProcessingRequested  │                     │
  │                                    ├──────────────────────────────────────────────────────►
  │  202 { requestId, status: QUEUED }│                               │                     │
  ◄───────────────────────────────────┤                               │                     │
  │                                    │                               │                     │
  │                                    │◄─────────────────────────────────────────────────────┤ ProcessingRequested
  │                                    │  sizeOf(storageKey)           │                     │
  │                                    ├──────────────────────────────►                     │
  │                                    │◄──────────────────────────────┤                     │
  │                                    │  evaluate stub rule            │                     │
  │                                    │  (docs/business-rules.md §5)  │                     │
  │                                    │  status → COMPLETED | FAILED  │                     │
  │                                    │                               │                     │
  │  GET /validations/{id}            │                               │                     │
  ├───────────────────────────────────►                               │                     │
  │  200 { requestId, status,         │                               │                     │
  │        result? }                  │                               │                     │
  ◄───────────────────────────────────┤                               │                     │
```

## 2. Step-by-step

### 2.1 Create — `POST /api/v1/validations`

Request:
```json
{ "filename": "invoice.pdf", "contentType": "application/pdf" }
```
Optional header: `Idempotency-Key: <client-generated-key>` (see `docs/business-rules.md` § 6).

Response `201 Created`:
```json
{ "requestId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "status": "PENDING_UPLOAD", "uploadUrl": "https://minio.local/validation-documents/3fa8.../invoice.pdf?X-Amz-..." }
```

The backend generates a `storageKey`, requests a presigned `PUT` URL from MinIO (`DocumentStoragePort.createPresignedUpload`), and persists the `ValidationRequest` in `PENDING_UPLOAD` before responding. `sizeInBytes` in `DocumentMetadata` is `0`/unset at this point — the file hasn't been uploaded yet.

### 2.2 Upload — direct `PUT` to MinIO

The client uploads the raw document bytes directly to `uploadUrl`. The backend is not involved in this step at all — it doesn't see the bytes, and doesn't know the upload happened until step 2.3.

### 2.3 Confirm — `POST /api/v1/validations/{requestId}/confirm`

No request body. Response `202 Accepted`:
```json
{ "requestId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "status": "QUEUED" }
```

`ConfirmUploadUseCase` transitions `PENDING_UPLOAD → QUEUED` and publishes `ProcessingRequested` (see `docs/service/events.md`). **This step does not call MinIO** — it doesn't verify the upload actually happened, and doesn't know the file size yet (see § 3 for why, and § 4 for what happens if the client lied).

Calling `confirm` again after it already succeeded is a safe no-op (see `docs/business-rules.md` § 6) — it returns `200`/`202` with the current status rather than erroring or re-publishing the event.

### 2.4 Processing — Kafka consumer (not client-facing)

Triggered by `ProcessingRequested`, not by an HTTP call. The consumer:
1. `findById(requestId)`, `startProcessing()` (`QUEUED → PROCESSING`).
2. `DocumentStoragePort.sizeOf(storageKey)` — this is where the real file size is discovered for the first time.
3. Evaluates the deterministic stub rule (`docs/business-rules.md` § 5) using `contentType` (known since step 2.1) and the just-discovered `sizeInBytes`.
4. `complete(result)` or `fail()`, persisted to Postgres.

### 2.5 Get — `GET /api/v1/validations/{requestId}`

Response `200 OK`, before completion:
```json
{ "requestId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "status": "PROCESSING" }
```

After completion:
```json
{
  "requestId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "COMPLETED",
  "result": { "verdict": "PASS", "fields": { "filename": "invoice.pdf" }, "reason": null }
}
```

## 3. Why `confirm` doesn't check the upload

`confirm` stays a pure, fast state transition with no storage I/O — see `README.md § Design trade-offs → Upload flow` for the full reasoning. The file size (the one piece of information storage can tell us) is only needed by the processing step, so it's fetched there, once, rather than in `confirm` as well.

## 4. Edge cases

| Scenario | Behavior |
|---|---|
| Client calls `confirm` twice | Second call is a no-op; returns current status, does not re-publish the event (`docs/business-rules.md` § 6). |
| Client calls `confirm` without ever uploading | `confirm` succeeds regardless (it doesn't check). During processing, `sizeOf(storageKey)` finds nothing; this is treated as size `0` → `verdict: FAIL, reason: "empty file"` (same rule as a genuinely empty upload — see `docs/business-rules.md` § 5). |
| Client uploads, but never calls `confirm` | The request stays in `PENDING_UPLOAD` forever from the backend's point of view — it never learns the upload happened. This is an accepted limitation of this slice (no reconciliation/garbage-collection job); see "What I'd do with another day" in `README.md`. |
| `GET`/`confirm` with an unknown `requestId` | `404` via `ValidationRequestNotFoundException` → Problem Details (`docs/service/code_rules.md` § 5). |
| `PUT` to the presigned URL fails or is retried by the client | Not the backend's concern — it's a direct client↔MinIO interaction. A retried `PUT` to the same presigned URL simply overwrites the same object (S3 `PUT` semantics), which is naturally idempotent. |
