# The HTTP API

Status: living document. This file lives under `docs/service/`. It was extracted out of `README.md § The HTTP API` once the README grew too long. It is the practical, call-it-yourself reference: base path, auth, endpoints, error catalog, idempotency behavior, and a full curl walkthrough. For the business meaning behind each endpoint and status, see `docs/business-rules.md`; for the exact upload sequence and edge cases, see `docs/service/upload-flow.md`.

Base path `/api/v1`, JSON in and out, and **every** endpoint requires the header
`X-Api-Key: <configured value>` (default `local-dev-api-key`, from `security.api-key` in
`application.yml`; override with the `API_KEY` environment variable). Every error is an RFC 7807
Problem Details body (see `docs/design-trade-offs.md § API error format`). The full wire contract lives in
[`docs/service/upload-flow.md`](upload-flow.md) and
[`docs/business-rules.md`](../business-rules.md).

| Endpoint | Does | Success |
|---|---|---|
| `POST /api/v1/validations` | Registers the intent to validate a document and signs an upload URL | `201` with `{ requestId, status, uploadUrl }` |
| `POST /api/v1/validations/{requestId}/confirm` | Accepts the work: moves `PENDING_UPLOAD → QUEUED` and publishes the processing event | `202` with `{ requestId, status }` |
| `GET /api/v1/validations/{requestId}` | Reads the current status, and the result once it exists | `200` with `{ requestId, status, result? }` |

Errors: `400` (blank/missing `filename` or `contentType`, malformed body — carries an `errors[]`
extension naming the offending fields), `401` (missing or wrong `X-Api-Key`), `404` (unknown
`requestId`), `409` (illegal status transition), `502` (storage backend failure), `500` (anything
unexpected, generic message only).

## Idempotency

`POST /api/v1/validations` accepts an optional `Idempotency-Key` header. Concretely
(see `docs/design-trade-offs.md § Idempotency-Key concrete rules`):

- **Never expires** — a key stays valid as long as its `ValidationRequest` exists.
- **Same key seen again** → the **original** `requestId` and its **current** status; no second
  request is created.
- **Same key, different body** → still the original resource; the new body is neither compared nor
  used.
- **The `uploadUrl` is re-signed on every reply**, including a replay: presigned URLs expire and are
  never persisted, so the client always receives a URL it can actually use, over the same stored
  storage key.

`confirm` needs no key: repeating it is safe by construction. It returns `202` with the current
status and publishes nothing unless the request actually moved out of `PENDING_UPLOAD`.

## Walking the flow

With the infrastructure up (`npm run docker:up`) and the service running (`npm run dev:service`) —
see `docs/service/running-locally.md`:

```bash
API_KEY=local-dev-api-key

# 1. Create — returns requestId + uploadUrl
curl -sS -X POST http://localhost:8080/api/v1/validations \
  -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-key-1" \
  -d '{"filename":"invoice.pdf","contentType":"application/pdf"}'

# 2. Upload the bytes straight to MinIO — the service never sees them
curl -sS -X PUT "<uploadUrl>" -H "Content-Type: application/pdf" --data-binary @invoice.pdf

# 3. Confirm — accepts the work and returns immediately
curl -sS -X POST http://localhost:8080/api/v1/validations/<requestId>/confirm -H "X-Api-Key: $API_KEY"

# 4. Read — poll until COMPLETED
curl -sS http://localhost:8080/api/v1/validations/<requestId> -H "X-Api-Key: $API_KEY"
```

A PDF between 1 byte and 15 MB ends `COMPLETED` with `verdict: PASS`. Any other content type,
an empty file (including one that was confirmed but never uploaded), or one over 15 MB ends
`COMPLETED` with `verdict: FAIL` and a specific `reason` — a conclusive answer, which is why it is
not the same thing as status `FAILED` (see [`docs/business-rules.md`](../business-rules.md) § 2).
