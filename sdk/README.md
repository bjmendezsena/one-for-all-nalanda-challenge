# @nalanda/validation-sdk

TypeScript client for the Nalanda document validation API. Dependency-free: it uses the
platform's native `fetch` and ships dual ESM + CJS builds with type declarations.

The domain rules this client mirrors — the status machine, the verdict rule, idempotency, and the
error contract — live in [`docs/business-rules.md`](../docs/business-rules.md). The public surface
is recorded in [`docs/sdk/architecture.md`](../docs/sdk/architecture.md) § 4, and the conventions
it is written to in [`docs/sdk/code_rules.md`](../docs/sdk/code_rules.md).

## Requirements

Node.js ≥ 20 (native global `fetch`), or any bundler targeting a browser with `fetch`.

## Install

```bash
npm install @nalanda/validation-sdk
```

From this monorepo:

```bash
npm run install:sdk    # npm --prefix sdk install
npm run build:sdk      # dual ESM + CJS + .d.ts via Vite
npm run test:sdk       # Vitest — no running API or infrastructure needed
```

## Usage

```typescript
import { readFile } from "node:fs/promises";
import { createClient, ValidationApiError } from "@nalanda/validation-sdk";

const client = createClient({
  baseUrl: "http://localhost:8080",
  apiKey: process.env.API_KEY ?? "local-dev-api-key",
});

const started = await client.startValidation(
  { filename: "invoice.pdf", contentType: "application/pdf" },
  { idempotencyKey: "demo-key-1" },
);

await client.upload(started, await readFile("invoice.pdf"), "application/pdf");

const finished = await client.waitForCompletion(started.requestId);
console.log(finished.status, finished.result?.verdict, finished.result?.reason);
```

A runnable version is in [`examples/validate-document.mjs`](./examples/validate-document.mjs):

```bash
npm --prefix sdk run example -- ./invoice.pdf application/pdf
```

## `createClient(options)`

| Option | Type | Required | Meaning |
|---|---|---|---|
| `baseUrl` | `string` | yes | Root of the API. A trailing `/` is stripped. |
| `apiKey` | `string` | yes | Sent as `X-Api-Key` on every API call. |
| `headers` | `Record<string, string>` | no | Extra headers merged into every API call. |

Returns a plain object of four functions — there is no class and no instance state, so the methods
can be destructured and passed around freely.

## Operations

### `startValidation(input, callOptions?)`

`POST /api/v1/validations`. Registers the intent to validate a document.

- `input`: `{ filename, contentType }` — both required by the API.
- `callOptions.idempotencyKey`: optional; sent as `Idempotency-Key`. Replaying the same key
  returns the original request instead of creating a new one
  ([`docs/business-rules.md`](../docs/business-rules.md) § 6).
- Returns `{ requestId, status, uploadUrl }`.

### `upload(target, data, contentType?)`

Uploads the bytes and confirms them, in that order:

1. `PUT target.uploadUrl` with the raw bytes. This goes **straight to object storage** — the API
   key and your extra headers are deliberately not attached, and the service never sees the bytes.
   When `contentType` is omitted no `Content-Type` header is set.
2. `POST /api/v1/validations/{target.requestId}/confirm`, which enqueues processing.

- `target`: anything with `{ requestId, uploadUrl }` — pass the `startValidation` result directly.
- `data`: `Blob | ArrayBuffer | ArrayBufferView | ReadableStream<Uint8Array> | string`.
- Returns `{ requestId, status }`.
- If the upload is rejected, it throws and the confirm is **not** attempted, leaving the request in
  `PENDING_UPLOAD`.
- Calling it again is safe: the storage `PUT` overwrites the same object and a repeated confirm is
  a no-op that returns the current status.

### `getValidation(requestId)`

`GET /api/v1/validations/{requestId}`. Returns `{ requestId, status, result? }`. `result` is
`null` until the validation reaches `COMPLETED`.

### `waitForCompletion(requestId, options?)`

Polls `getValidation` until the request reaches a terminal status (`COMPLETED` or `FAILED`) and
returns it.

| Option | Type | Default | Meaning |
|---|---|---|---|
| `timeoutMs` | `number` | `30000` | Total budget for the wait. |
| `initialDelayMs` | `number` | `250` | First pause between reads. |
| `maxDelayMs` | `number` | `5000` | Ceiling the doubling pause never exceeds. |
| `signal` | `AbortSignal` | — | Cancels an in-flight wait. |

The pause doubles after each read, clamped to `maxDelayMs`. Exhausting the budget throws an
`Error` naming it; aborting rejects with an `AbortError` `DOMException`.

```typescript
const controller = new AbortController();
setTimeout(() => controller.abort(), 5_000);

await client.waitForCompletion(requestId, { signal: controller.signal });
```

## Errors

Every rejection — from the API and from the storage upload — is thrown as `ValidationApiError`:

```typescript
try {
  await client.getValidation("missing");
} catch (error) {
  if (error instanceof ValidationApiError) {
    error.status;        // 404
    error.body?.detail;  // "Validation request 'missing' not found"
    error.body?.errors;  // [{ field, message }] on input-validation rejections
  }
}
```

`body` is the RFC 7807 Problem Details payload the API returns. When a rejection has no body, or a
body that is not JSON (object storage answers XML), `body` is `undefined` and the message falls
back to `Request failed with status <n>`.

## Status and verdict are different things

`FAILED` is a **status**: the system could not complete the check. `verdict: "FAIL"` is a
**result**: the check ran and the document did not pass. A document that is not a PDF, is empty,
or exceeds 15 MB ends `COMPLETED` with `verdict: "FAIL"` and a specific `reason` — a conclusive
answer, not an error. See [`docs/business-rules.md`](../docs/business-rules.md) §§ 2 and 5.

## Development

From inside `sdk/`:

```bash
npm install
npm test          # Vitest — fetch is mocked, no API or infrastructure needed
npm run typecheck # tsc --noEmit, strict mode
npm run build     # ESM + CJS + .d.ts into dist/
npm run example   # builds, then runs examples/validate-document.mjs against a live service
```
