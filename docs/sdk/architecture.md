# Architecture (SDK)

Status: living document. This file lives under `docs/sdk/` — it is specific to the TypeScript SDK (`sdk/`). The backend has its own equivalent, `docs/service/architecture.md`. This file describes the current state of the SDK's architecture and structure (the "what" and "how"). It does not repeat the reasoning behind each choice — the rationale, discarded alternatives, and trade-offs for every decision referenced here live in `docs/design-trade-offs.md`, indexed by the same names used in this document.

## 1. Purpose of this document

This document is the single source of truth for:
- the SDK's structure and public API surface
- how the SDK relates to the backend's HTTP API

It intentionally does NOT cover (see "Related documents" below for where each topic lives instead):
- the backend's architecture → `docs/service/architecture.md`
- business rules and validation logic (shared between service and SDK) → `docs/business-rules.md`
- implementation-level coding conventions for the SDK, with examples → `docs/sdk/code_rules.md`
- how to build/test the SDK → `docs/sdk/building-and-testing.md`
- why a decision was made over its alternatives → `docs/design-trade-offs.md`

## 2. Related documents

| Document | Content |
|---|---|
| `README.md` | Project overview, repository layout, quick start |
| `docs/design-trade-offs.md` | Every design decision: alternatives considered, chosen approach, rationale |
| `docs/business-rules.md` | Domain/business rules — shared between `service/` and `sdk/` |
| `docs/service/architecture.md` | Backend architecture and structure |
| `docs/sdk/architecture.md` | This file — SDK architecture and structure |
| `docs/sdk/building-and-testing.md` | How to build, test, typecheck the SDK, and run its examples |
| `docs/sdk/code_rules.md` | SDK implementation-level coding conventions |

## 3. SDK structure (`sdk/`)

```
sdk/
├── package.json          # exports map for both `import` and `require`
├── vite.config.ts        # library mode, dual ESM+CJS+.d.ts output
├── tsconfig.json         # strict mode
├── src/
│   ├── index.ts          # public entrypoint, re-exports the public API only
│   ├── client.ts         # createClient(...) and the client's methods
│   ├── errors.ts         # typed error class(es) wrapping HTTP status + Problem Details body
│   └── types.ts          # request/response types shared by the client
├── tests/                # Vitest
├── examples/             # runnable usage example(s)
├── README.md             # SDK-specific install + usage
└── CHANGELOG.md          # semver, starting at 0.1.0
```

## 4. Public API surface

The surface as shipped in `0.1.0`. Only `src/index.ts` exports it; `src/internal/**` is never
re-exported.

```typescript
createClient(options: ClientOptions): ValidationClient

client.startValidation(input: StartValidationInput, callOptions?: StartValidationCallOptions): Promise<StartValidationResponse>
client.upload(target: UploadTarget, data: UploadData, contentType?: string): Promise<ConfirmUploadResponse>
client.getValidation(requestId: string): Promise<ValidationRequestDto>
client.waitForCompletion(requestId: string, options?: WaitForCompletionOptions): Promise<ValidationRequestDto>
```

| Operation | HTTP calls |
|---|---|
| `startValidation` | `POST /api/v1/validations`, with `Idempotency-Key` when the caller supplies one |
| `upload` | `PUT target.uploadUrl` (direct to object storage, no `X-Api-Key`), then `POST /api/v1/validations/{requestId}/confirm` |
| `getValidation` | `GET /api/v1/validations/{requestId}` |
| `waitForCompletion` | repeated `getValidation` until `COMPLETED` or `FAILED` |

There is no separate `confirm` operation: `upload` is the only point at which the client knows the
bytes were sent, so it performs the confirm itself, and it skips the confirm if the `PUT` was
rejected. `target` is the object `startValidation` returned — the client keeps no state and the
read endpoint does not return `uploadUrl`.

Alongside the factory, `src/index.ts` exports `ValidationApiError` and the public types
(`ClientOptions`, `ValidationClient`, `ValidationStatus`, `Verdict`, `ValidationResult`,
`ValidationRequestDto`, `StartValidationInput`, `StartValidationCallOptions`,
`StartValidationResponse`, `ConfirmUploadResponse`, `UploadTarget`, `UploadData`,
`WaitForCompletionOptions`, `ProblemDetailsBody`). Usage examples live in `sdk/README.md`.

## 5. Boundary with the backend

The SDK only depends on the HTTP API contract exposed by `service/` (see `docs/business-rules.md` for the endpoint semantics and `docs/service/upload-flow.md` for the exact sequence) — it has no knowledge of Kafka, Postgres, or MinIO; those are internal to the backend.

## 6. Cross-cutting architectural decisions (index)

The table below is an index into `docs/design-trade-offs.md`, where the discarded alternatives and the reasoning for each decision are recorded. This document only states the current, chosen state. Backend-specific decisions are indexed in `docs/service/architecture.md` instead.

| Concern | Chosen approach |
|---|---|
| SDK bundler | Vite (library mode) |
| SDK client architecture | Factory function (`createClient`) + native `fetch`, no class/instance state |
| SDK polling | `waitForCompletion` with exponential backoff + `AbortSignal`, no external retry library |
| SDK testing | Vitest, `fetch` mocked directly, no MSW |
| SDK module format | Pure ESM in `src/`; dual ESM/CJS/`.d.ts` output produced only by the Vite build |
