# Phase 1 data model: SDK client

The SDK holds no persistent state. Everything below is a TypeScript type that mirrors what
crosses the wire (`docs/sdk/code_rules.md` § 4) plus the two option bags the public surface
takes. Field names and vocabularies come from the backend's actual serialization, not from an
independent contract.

## Configuration

### `ClientOptions` (`src/types.ts`)

| Field | Type | Required | Meaning |
|---|---|---|---|
| `baseUrl` | `string` | yes | Root of the API, e.g. `http://localhost:8080`. A trailing `/` is stripped by `createClient`. |
| `apiKey` | `string` | yes | Value sent as `X-Api-Key` on every API call (`ApiKeyFilter`). |
| `headers` | `Record<string, string>` | no | Extra headers merged into every API call, after the defaults. |

### `StartValidationCallOptions` (`src/types.ts`)

| Field | Type | Required | Meaning |
|---|---|---|---|
| `idempotencyKey` | `string` | no | Sent as `Idempotency-Key`; omitted entirely when absent (`docs/business-rules.md` § 6). |

### `WaitForCompletionOptions` (`src/types.ts`)

| Field | Type | Default | Meaning |
|---|---|---|---|
| `timeoutMs` | `number` | `DEFAULT_TIMEOUT_MS` = `30_000` | Total budget for the wait. |
| `initialDelayMs` | `number` | `DEFAULT_INITIAL_DELAY_MS` = `250` | First pause between reads. |
| `maxDelayMs` | `number` | `DEFAULT_MAX_DELAY_MS` = `5_000` | Ceiling the doubling pause never exceeds. |
| `signal` | `AbortSignal` | — | Cancels an in-flight wait. |

Defaults are module-level `UPPER_SNAKE_CASE` constants (`docs/sdk/code_rules.md` § 8), not inline
literals.

## Wire vocabularies

```
ValidationStatus = "PENDING_UPLOAD" | "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED"
Verdict          = "PASS" | "FAIL"
```

`ValidationStatus` is the status machine of `docs/business-rules.md` § 2, in order.
`COMPLETED` and `FAILED` are the two terminal states `waitForCompletion` stops on. `Verdict` is
the outcome of the deterministic rule in § 5 and is **not** the same thing as the `FAILED`
status.

## Request / response shapes

### `StartValidationInput` → request body of `POST /api/v1/validations`

| Field | Type | Notes |
|---|---|---|
| `filename` | `string` | Required by the backend (`@NotBlank`). |
| `contentType` | `string` | Required by the backend (`@NotBlank`). |

### `StartValidationResponse` ← `201 Created`

| Field | Type | Notes |
|---|---|---|
| `requestId` | `string` | UUID, serialized as a string. |
| `status` | `ValidationStatus` | Always `PENDING_UPLOAD` on a fresh create; on an idempotent replay it is the original request's current status. |
| `uploadUrl` | `string` | Presigned `PUT` destination. Opaque to the SDK. |

### `ConfirmUploadResponse` ← `202 Accepted` of `POST /api/v1/validations/{requestId}/confirm`

| Field | Type | Notes |
|---|---|---|
| `requestId` | `string` | |
| `status` | `ValidationStatus` | `QUEUED` on the first confirm; the current status on a replay. |

### `ValidationRequestDto` ← `200 OK` of `GET /api/v1/validations/{requestId}`

| Field | Type | Notes |
|---|---|---|
| `requestId` | `string` | Serialized from the aggregate's id via `@JsonProperty("requestId")`. |
| `status` | `ValidationStatus` | |
| `result` | `ValidationResult \| null` | Present from `COMPLETED` onward; serialized as `null` before that, hence optional **and** nullable in the type. |

### `ValidationResult`

| Field | Type | Notes |
|---|---|---|
| `verdict` | `Verdict` | |
| `fields` | `Record<string, unknown>` | Stubbed extraction output. |
| `reason` | `string \| null` | Set on `FAIL`, may be informative otherwise. |

### `UploadTarget`

The object `upload` accepts as its first argument — structurally satisfied by a
`StartValidationResponse`, so the caller passes the start result straight through.

| Field | Type | Notes |
|---|---|---|
| `requestId` | `string` | Used to build the confirm URL. |
| `uploadUrl` | `string` | Used as the `PUT` target. |

### `UploadData`

The accepted body shapes for the presigned `PUT`: `BodyInit` restricted to what a document
sensibly is — `Blob`, `ArrayBuffer`, `ArrayBufferView`, `ReadableStream<Uint8Array>`, `string`.

## Error shape

### `ProblemDetailsBody` (`src/errors.ts`)

| Field | Type | Source |
|---|---|---|
| `type` | `string?` | RFC 7807 / Spring `ProblemDetail`. |
| `title` | `string?` | |
| `status` | `number?` | |
| `detail` | `string?` | The actionable reason required by `docs/business-rules.md` § 7. |
| `errors` | `Array<{ field: string; message: string }>?` | Added by `ApiExceptionHandler` for `MethodArgumentNotValidException`. |

### `ValidationApiError`

`Error` subclass with `name = "ValidationApiError"`, `readonly status: number` and
`readonly body?: ProblemDetailsBody`. Message is `body?.detail` when present, otherwise
`Request failed with status ${status}`.

## Public surface exported from `src/index.ts`

`createClient`, `ValidationApiError`, and the types above (`ClientOptions`, `ValidationClient`,
`ValidationStatus`, `Verdict`, `ValidationResult`, `ValidationRequestDto`,
`StartValidationInput`, `StartValidationCallOptions`, `StartValidationResponse`,
`ConfirmUploadResponse`, `UploadTarget`, `UploadData`, `WaitForCompletionOptions`,
`ProblemDetailsBody`). Nothing from `src/internal/` is re-exported.
