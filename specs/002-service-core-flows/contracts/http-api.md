# Contract — HTTP API (`service/`)

**Feature**: `002-service-core-flows` | **Date**: 2026-08-25

The wire contract this feature implements. It restates what
`docs/service/upload-flow.md` and `docs/business-rules.md` already define, plus the two
points resolved in `research.md` (R-005, R-006). **This contract is unchanged from what the
SDK already expects** — no endpoint, field, or status is added.

Base path: `/api/v1`. Every endpoint requires the header `X-Api-Key: <configured value>`.
Every error response is `application/problem+json` (RFC 7807).

## 1. `POST /api/v1/validations` — create validation

**Headers**: `X-Api-Key` (required), `Idempotency-Key` (optional), `Content-Type: application/json`.

**Request body**:

```json
{ "filename": "invoice.pdf", "contentType": "application/pdf" }
```

| Field | Required | Rule |
|---|---|---|
| `filename` | yes | non-blank |
| `contentType` | yes | non-blank MIME string |

**`201 Created`**:

```json
{
  "requestId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "PENDING_UPLOAD",
  "uploadUrl": "http://localhost:9000/validation-documents/3fa8.../invoice.pdf?X-Amz-..."
}
```

**Idempotent replay** (same `Idempotency-Key` seen before): `201` with the **original**
`requestId` and its **current** status, plus a freshly signed `uploadUrl` over the stored
storage key (R-006). No new request is created, and the new body is neither compared nor used.

**Errors**: `400` (missing/blank `filename` or `contentType`, malformed JSON) with an
`errors[]` extension listing the offending fields; `401` (missing/wrong API key);
`502` (storage could not sign the URL).

## 2. `POST /api/v1/validations/{requestId}/confirm` — confirm upload

**Headers**: `X-Api-Key` (required). **No request body.**

**`202 Accepted`**:

```json
{ "requestId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "status": "QUEUED" }
```

Publishes `ProcessingRequested` **only** when the request actually moved
`PENDING_UPLOAD → QUEUED`. A repeated confirm returns `202` with the request's current status
(`QUEUED`, `PROCESSING`, `COMPLETED` or `FAILED`) and publishes nothing. Performs no storage I/O.

**Errors**: `401`; `404` (unknown `requestId`).

## 3. `GET /api/v1/validations/{requestId}` — read validation

**Headers**: `X-Api-Key` (required). Read-only; never mutates state.

**`200 OK`** before completion:

```json
{ "requestId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "status": "PROCESSING" }
```

**`200 OK`** after completion:

```json
{
  "requestId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "COMPLETED",
  "result": { "verdict": "PASS", "fields": { "filename": "invoice.pdf" }, "reason": null }
}
```

`result` is absent/`null` until `COMPLETED`. A `FAILED` request carries no result.
`document` is never serialized.

**Errors**: `401`; `404` (unknown `requestId`).

## 4. Error shape

Every error is a `ProblemDetail` produced by the single `@RestControllerAdvice`:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed",
  "instance": "/api/v1/validations",
  "errors": [ { "field": "filename", "message": "must not be blank" } ]
}
```

| Condition | Status | `detail` |
|---|---|---|
| Missing/blank required field, malformed body | `400` | `"Validation failed"` + `errors[]` |
| Missing or incorrect `X-Api-Key` | `401` | names the missing/invalid API key |
| Unknown `requestId` | `404` | names the request id |
| Illegal status transition | `409` | names the actual and expected status |
| Storage backend failure | `502` | names the failing storage operation |
| Anything unexpected | `500` | generic message only — never a stack trace, never internals |

## 5. Asynchronous contract (internal)

Not client-facing, listed so the whole contract is in one place.

| Topic | Key | Payload |
|---|---|---|
| `validation.processing-requested` | `validationRequestId` (string) | `{ "validationRequestId": "<uuid>" }` |

Consumer group `validation-service`, at-least-once delivery, duplicates absorbed by the
status machine. This is the **only** event in the system; no completion event exists.
