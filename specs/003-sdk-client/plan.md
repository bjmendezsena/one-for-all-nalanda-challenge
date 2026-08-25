# Implementation Plan: SDK client for the document validation API

**Branch**: `003-sdk-client` | **Date**: 2026-08-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-sdk-client/spec.md`

## Summary

Complete `sdk/` — today a compiling skeleton of empty placeholder modules — into the
dependency-free TypeScript client documented in `docs/sdk/architecture.md` and
`docs/sdk/code_rules.md`. The client is a factory function (`createClient`) returning a plain
object of closures over `baseUrl` + headers, backed by a single internal `request()` helper
over native `fetch`. It exposes four operations — `startValidation`, `upload`,
`getValidation`, `waitForCompletion` — with `ValidationApiError` as the only error type
consumers ever see, and types that mirror the backend's actual wire shapes.

**Artifacts touched**: `sdk/` only, plus documentation. **The HTTP contract does not change**;
`service/` is not modified. The one cross-artifact effect is documentation: `docs/sdk/
architecture.md` § 4 explicitly leaves the public surface open to refinement, so the final
signatures are recorded there in the same change (FR-018), alongside `sdk/README.md`,
`sdk/CHANGELOG.md` and the root `README.md § How to build/test the SDK`. Cross-artifact analysis
also surfaced that three examples in `docs/sdk/code_rules.md` (§§ 1, 4, 6) are superseded by the
clarified surface; amending them is a governance action, cleared with the human and scoped to
those examples only (FR-019).

## Technical Context

**Language/Version**: TypeScript 5.9, `"strict": true`, pure ESM source (`docs/sdk/code_rules.md` § 7)

**Primary Dependencies**: none at runtime — native `fetch` only. Existing devDependencies
(`typescript`, `vite`, `vitest`) are used as-is; **no new dependency is added** (constitution IX)

**Storage**: N/A — the SDK is stateless; the only "state" is the closure over `ClientOptions`

**Testing**: Vitest with `fetch` mocked directly via `vi.stubGlobal("fetch", ...)`; no MSW, no
`nock` (`docs/sdk/code_rules.md` § 6, constitution VII)

**Target Platform**: Node.js ≥ 20 (`sdk/package.json` engines) and bundler consumers; dual
ESM + CJS + `.d.ts` output produced only by the Vite library build

**Project Type**: client library wrapping the `service/` HTTP API

**Performance Goals**: N/A — the SDK adds no measurable overhead over one `fetch` per call. The
only timing behaviour is `waitForCompletion`'s backoff: 250 ms initial, doubling, 5 000 ms
ceiling, 30 000 ms budget by default (`docs/sdk/code_rules.md` § 5)

**Constraints**: public surface exported only from `src/index.ts`; `src/internal/**` never
re-exported (constitution IV); every API rejection surfaces as `ValidationApiError`
(constitution V); no `class` client, no HTTP client dependency, no retry library
(`docs/sdk/code_rules.md` § 9)

**Scale/Scope**: 5 source modules (`index`, `client`, `types`, `errors`, `internal/http`), one
example, one test file per behavioural area

## Constitution Check

_GATE: evaluated before Phase 0 and re-checked after Phase 1 design. Result: **PASS**, no
violations, Complexity Tracking left empty._

| Principle | How this plan complies |
|---|---|
| I. Documentation Is the Source of Truth | Every design decision below traces to `docs/sdk/architecture.md`, `docs/sdk/code_rules.md`, `docs/business-rules.md`, `docs/service/upload-flow.md` or `README.md § Design trade-offs`. The two points the docs leave open (the missing confirm operation, the client-options shape) were raised with the human and recorded in `spec.md § Clarifications` rather than silently decided. `docs/sdk/architecture.md` § 4 is updated in the same change (FR-018); the three superseded `docs/sdk/code_rules.md` examples are amended as an explicit governance change cleared with the human (FR-019). No other doc entry is rewritten "for cleanliness". |
| II. Spec-Driven Development | Scope is exactly FR-001…FR-018. No endpoint, status, field or option outside the documented contract is introduced. |
| III. Code Rules Are Non-Negotiable | Amending the superseded examples follows the constitution's amendment process (raised in analysis, decided by the human, applied in the same change) rather than an inline edit during implementation. Otherwise `docs/sdk/code_rules.md` §§ 1–9 is the implementation contract: factory function (§ 1), one internal `request()` over native `fetch` (§ 2), `ValidationApiError` mirroring Problem Details (§ 3), types mirroring the wire (§ 4), hand-written backoff + `AbortSignal` (§ 5), Vitest with stubbed `fetch` (§ 6), ESM-only source (§ 7), naming table (§ 8), and every § 9 restriction. |
| IV. Architecture & Boundaries | Structure stays exactly the tree in `docs/sdk/architecture.md` § 3. No new module, folder or layer. `src/index.ts` is the only public export point; `src/internal/http.ts` is never re-exported. |
| V. Explicit Contracts & Errors | The status vocabulary and verdict vocabulary are typed from `docs/business-rules.md` § 2 and § 5. Every non-OK response — from the API and from the presigned upload — becomes `ValidationApiError` carrying status + Problem Details body, including the `errors` array the backend attaches to input-validation rejections. |
| VI. Asynchronous Processing Integrity | The SDK never assumes a synchronous verdict: `upload` returns as soon as the API accepts the confirm, and the caller reaches the outcome through `getValidation`/`waitForCompletion`. Repeating `upload` is safe because `confirm` is a documented no-op after the first success (`docs/business-rules.md` § 6). The idempotency key is forwarded verbatim; its semantics are never reinterpreted client-side. |
| VII. Quality & Testing | Tests are written with the code, named `should_<expectedBehavior>_when_<condition>()`, and cover every operation, every documented failure class, and the wait's timeout/backoff/cancellation. `fetch` is stubbed directly; fake timers keep the backoff tests deterministic. |
| VIII. Security & Configuration | `X-Api-Key` is sent on every API call and deliberately **not** on the presigned upload (that request carries its own signature; adding an unsigned header risks breaking the signature and leaks the credential to storage). No credential is logged, defaulted or committed. |
| IX. Approved Stack & Operations | No new dependency, library or infrastructure. Vite library mode and Vitest already present. Nothing under `docker/` changes. |

## Project Structure

### Documentation (this feature)

```text
specs/003-sdk-client/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── sdk-public-api.md  # the public surface this feature commits to
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
one-for-all-nalanda-challenge/
├── README.md                     # § How to build/test the SDK — updated
├── docs/
│   └── sdk/
│       └── architecture.md       # § 4 public surface — updated with final signatures
└── sdk/
    ├── README.md                 # NEW — install + usage for a consumer
    ├── CHANGELOG.md              # 0.1.0 entry describing the surface
    ├── src/
    │   ├── index.ts              # public entrypoint — re-exports the public API only
    │   ├── client.ts             # createClient + the four operations + waitForCompletion
    │   ├── types.ts              # ClientOptions, inputs, responses, status/verdict unions
    │   ├── errors.ts             # ProblemDetailsBody, ValidationApiError
    │   └── internal/
    │       └── http.ts           # the single request() helper over native fetch
    ├── tests/
    │   ├── client.test.ts        # startValidation / upload / getValidation
    │   ├── wait-for-completion.test.ts  # backoff, timeout, abort, already-final
    │   └── errors.test.ts        # Problem Details parsing, unparsable body, fallback message
    └── examples/
        └── validate-document.ts  # runnable end-to-end example
```

**Structure Decision**: the tree above is exactly `docs/sdk/architecture.md` § 3 with the
placeholder files filled in — no directory, module or abstraction is added. `tests/` and
`examples/` already exist as `.gitkeep` placeholders and are populated here.
`src/internal/http.ts` holds the only `fetch` call site; `client.ts` holds the operations and
the polling loop, as § 1 and § 5 of the code rules show them.

## Design decisions

Each decision below is either taken from a doc or was cleared with the human; the reasoning is
in `research.md`.

1. **`createClient(options)`** builds `baseUrl` (trailing slash stripped) and a header map of
   `Content-Type: application/json`, `X-Api-Key: options.apiKey`, plus `options.headers`, and
   returns a plain object of four closures (`docs/sdk/code_rules.md` § 1).
2. **`startValidation(input, callOptions?)`** → `POST {baseUrl}/api/v1/validations` with the
   `{ filename, contentType }` body, adding `Idempotency-Key` only when
   `callOptions.idempotencyKey` is present. Returns `StartValidationResponse`.
3. **`upload(target, data, contentType?)`** → `PUT target.uploadUrl` with the raw bytes and no
   API credential, then `POST {baseUrl}/api/v1/validations/{target.requestId}/confirm`.
   Returns `ConfirmUploadResponse`. A rejected `PUT` throws before the confirm runs.
4. **`getValidation(requestId)`** → `GET {baseUrl}/api/v1/validations/{requestId}`, returns
   `ValidationRequestDto`.
5. **`waitForCompletion(requestId, opts?)`** → the loop from `docs/sdk/code_rules.md` § 5,
   verbatim in shape: read, return on `COMPLETED`/`FAILED`, throw on deadline, sleep with
   `AbortSignal` support, double the delay up to the ceiling.
6. **`request<T>()`** is the only place `fetch` is called: it throws `ValidationApiError` on a
   non-OK response after best-effort parsing of the Problem Details body, and returns
   `undefined` for `204`/empty bodies.

## Complexity Tracking

> No Constitution Check violations. Table intentionally empty.
