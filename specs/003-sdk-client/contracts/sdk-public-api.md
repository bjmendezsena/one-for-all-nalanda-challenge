# Contract: SDK public API surface

This is the surface `@nalanda/validation-sdk@0.1.0` commits to. It refines
`docs/sdk/architecture.md` § 4, which explicitly leaves the names open to refinement; that
document is updated to match in the same change (FR-018). Nothing outside this file is exported
from `src/index.ts`.

```typescript
export function createClient(options: ClientOptions): ValidationClient;

export interface ValidationClient {
  startValidation(
    input: StartValidationInput,
    callOptions?: StartValidationCallOptions,
  ): Promise<StartValidationResponse>;

  upload(
    target: UploadTarget,
    data: UploadData,
    contentType?: string,
  ): Promise<ConfirmUploadResponse>;

  getValidation(requestId: string): Promise<ValidationRequestDto>;

  waitForCompletion(
    requestId: string,
    options?: WaitForCompletionOptions,
  ): Promise<ValidationRequestDto>;
}
```

## HTTP mapping

| Operation | Method & path | Headers | Body | Success |
|---|---|---|---|---|
| `startValidation` | `POST {baseUrl}/api/v1/validations` | `Content-Type: application/json`, `X-Api-Key`, extra `headers`, `Idempotency-Key` when supplied | `{ filename, contentType }` | `201` → `StartValidationResponse` |
| `upload` step 1 | `PUT {target.uploadUrl}` | `Content-Type` only when `contentType` is supplied, otherwise no headers at all — **never** `X-Api-Key`, **never** extra `headers` | raw bytes | `200` |
| `upload` step 2 | `POST {baseUrl}/api/v1/validations/{target.requestId}/confirm` | `Content-Type: application/json`, `X-Api-Key`, extra `headers` | none | `202` → `ConfirmUploadResponse` |
| `getValidation` | `GET {baseUrl}/api/v1/validations/{requestId}` | `Content-Type: application/json`, `X-Api-Key`, extra `headers` | none | `200` → `ValidationRequestDto` |
| `waitForCompletion` | repeated `getValidation` | as above | none | `200` → `ValidationRequestDto` in a terminal status |

`baseUrl` has any trailing `/` stripped by `createClient` before paths are appended.

## Behavioural guarantees

1. **Every non-OK response throws `ValidationApiError`** carrying `status` and the parsed body,
   for the API calls *and* for the presigned `PUT`. No raw `fetch` rejection and no bare `Error`
   reaches a consumer. A body that is absent or not JSON leaves `body` `undefined` and the
   message falls back to `Request failed with status ${status}`.
2. **`upload` is ordered and fail-fast**: the confirm runs only after the `PUT` resolves OK.
   When `contentType` is omitted the `PUT` sets no `Content-Type` header, leaving the runtime's
   default rather than guessing one.
3. **`upload` is replay-safe**: a second call re-`PUT`s the same object (S3 overwrite semantics)
   and re-confirms, which the backend treats as a no-op returning the current status
   (`docs/business-rules.md` § 6).
4. **`waitForCompletion` terminates** on `COMPLETED` or `FAILED`, throws a timeout `Error` naming
   the elapsed budget when the deadline would be crossed, and rejects with an `AbortError`
   `DOMException` when `options.signal` aborts. Delay starts at `initialDelayMs`, doubles, and is
   clamped to `maxDelayMs`.
5. **`waitForCompletion` returns a `FAILED` request** rather than throwing — `FAILED` is a
   terminal status, not a transport error, and a `verdict: "FAIL"` inside a `COMPLETED` request
   is likewise a successful call (`docs/business-rules.md` § 2).
6. **No credential leaves the API host**: the presigned `PUT` never carries `X-Api-Key` or the
   caller's extra headers.

## Non-goals

No operation, option, status, verdict or field beyond the above. In particular: no client-side
retry of failed API calls, no client-side re-derivation of an upload URL, no client-side
interpretation of idempotency, no logging.
