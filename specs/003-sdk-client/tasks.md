---
description: 'Task list for feature 003-sdk-client'
---

# Tasks: SDK client for the document validation API

**Input**: Design documents from `/specs/003-sdk-client/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/sdk-public-api.md, quickstart.md

**Tests**: Tests are completion criteria, not a separate phase (Constitution VII). Every
implementation task below carries its own tests, written against a directly mocked `fetch`
(`vi.stubGlobal`) — never a live backend, never MSW or `nock`. Test names follow
`should_<expectedBehavior>_when_<condition>()`.

**Organization**: grouped by the user stories of `spec.md`, in priority order.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1…US4 from `spec.md`

## Path Conventions

`sdk/` only — `sdk/src/**`, tests in `sdk/tests/**`, example in `sdk/examples/**`. Placement is
governed by `docs/sdk/architecture.md` § 3 and `docs/sdk/code_rules.md`. **No file under
`service/` is touched**; the HTTP contract does not change. Documentation edits are additive and
land in the same change.

---

## Phase 1: Setup

**Purpose**: confirm the existing toolchain is usable before writing code. No new dependency is
added (constitution IX) — `typescript`, `vite` and `vitest` are already declared.

- [X] T001 Install the SDK's dependencies and verify the baseline is green by running `npm run install:sdk`, `npm --prefix sdk run typecheck` and `npm --prefix sdk run test` from the repo root; record nothing, change nothing if they pass.
- [X] T002 Verify `sdk/tsconfig.json` has `"strict": true` and leave it untouched — it is never relaxed to silence a type error (`docs/sdk/code_rules.md` § 9).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the type vocabulary, the error type and the single `fetch` call site that every
user story builds on.

**⚠️ CRITICAL**: no user-story task can start until this phase is complete.

- [X] T003 [P] Define the error contract in `sdk/src/errors.ts`: `ProblemDetailsBody` (`type`, `title`, `status`, `detail`, `errors?: Array<{ field: string; message: string }>`) and `ValidationApiError extends Error` with `name = "ValidationApiError"`, `readonly status: number`, `readonly body?: ProblemDetailsBody`, and the message falling back to `Request failed with status ${status}` when `body?.detail` is absent — exactly as `docs/sdk/code_rules.md` § 3 specifies.
- [X] T004 [P] Define the wire vocabulary and shapes in `sdk/src/types.ts` per `specs/003-sdk-client/data-model.md`: `ValidationStatus`, `Verdict`, `ValidationResult`, `ValidationRequestDto`, `StartValidationInput`, `StartValidationCallOptions`, `StartValidationResponse`, `ConfirmUploadResponse`, `UploadTarget`, `UploadData`, `WaitForCompletionOptions`, and `ClientOptions` as `{ baseUrl: string; apiKey: string; headers?: Record<string, string> }`; replace the placeholder `ValidationClient` with the interface from `specs/003-sdk-client/contracts/sdk-public-api.md`. Remove the `TODO` placeholders.
- [X] T005 Implement the single `request<T>(url, init)` helper in `sdk/src/internal/http.ts` over native `fetch`, per `docs/sdk/code_rules.md` § 2: on a non-OK response parse the body best-effort (`.catch(() => undefined)`) and throw `ValidationApiError(response.status, problem)`; return `undefined` for `204`; otherwise return the parsed JSON. This is the only place in the SDK that calls `fetch`. Remove the `TODO` placeholder.
- [X] T006 Cover the helper's error contract in `sdk/tests/errors.test.ts`: a Problem Details body surfaces as `ValidationApiError` with `status`, `detail` and the `errors` array intact; a body that is not JSON leaves `body` undefined and falls back to `Request failed with status <n>`; a `204` resolves to `undefined` rather than throwing on an empty parse.

**Checkpoint**: types, error type and the HTTP boundary exist; the four operations can now be built on top.

---

## Phase 3: User Story 1 — Run a document through validation end to end (Priority: P1) 🎯 MVP

**Goal**: `createClient` plus `startValidation`, `upload` (presigned `PUT` + confirm) and
`getValidation`, so a consumer can drive a document from creation to a readable outcome without
writing transport code.

**Independent test**: with `fetch` stubbed, each operation issues the call documented in
`contracts/sdk-public-api.md § HTTP mapping` and returns the documented shape; the three of them
in sequence complete a lifecycle.

- [X] T007 [US1] Implement `createClient(options)` in `sdk/src/client.ts` as a factory returning a plain object of closures (never a `class`, `docs/sdk/code_rules.md` § 1): strip a trailing `/` from `options.baseUrl`, build the header map as `Content-Type: application/json` + `X-Api-Key: options.apiKey` + `...options.headers`, and wire the four operations. Remove the `TODO` placeholder and the `void options` stub.
- [X] T008 [US1] Implement `startValidation(input, callOptions?)` in `sdk/src/client.ts`: `POST {baseUrl}/api/v1/validations` with the `{ filename, contentType }` body through `request()`, adding the `Idempotency-Key` header only when `callOptions?.idempotencyKey` is present; return `StartValidationResponse`.
- [X] T009 [US1] Implement `upload(target, data, contentType?)` in `sdk/src/client.ts`: `PUT target.uploadUrl` through `request()` with the raw bytes and, only when `contentType` is supplied, a `Content-Type` header — **no** `X-Api-Key` and **no** caller headers (`docs/service/upload-flow.md` § 2.2, constitution VIII) — then, only if that resolves, `POST {baseUrl}/api/v1/validations/{target.requestId}/confirm` and return `ConfirmUploadResponse`.
- [X] T010 [P] [US1] Implement `getValidation(requestId)` in `sdk/src/client.ts`: `GET {baseUrl}/api/v1/validations/{requestId}` through `request()`, returning `ValidationRequestDto`.
- [X] T011 [US1] Cover the three operations in `sdk/tests/client.test.ts` against a stubbed `fetch`: `startValidation` posts the documented body and returns `requestId`/`status`/`uploadUrl`; it sends `Idempotency-Key` when given one and omits the header entirely when not; `upload` PUTs to the target URL without `X-Api-Key` and then POSTs the confirm, returning the confirm's status; a rejected PUT throws `ValidationApiError` and the confirm is never called; `getValidation` returns the status and the result when present; a `baseUrl` with a trailing `/` still produces a well-formed URL; every call carries `X-Api-Key` and any extra `headers`.

**Checkpoint**: the MVP is usable — a consumer can create, upload, confirm and read.

---

## Phase 4: User Story 2 — Understand and react to a rejection (Priority: P2)

**Goal**: every documented rejection reaches the consumer as `ValidationApiError` with the
status and the actionable reason, never as a raw transport failure.

**Independent test**: stub each documented rejection class and assert the typed error carries
status and body.

- [X] T012 [US2] Extend `sdk/tests/errors.test.ts` to cover the rejection classes the API actually produces, driven through the public client: `404` not-found on `getValidation` surfaces `status` and `detail`; `400` with the `errors` array on `startValidation` exposes the field-level details; `401` from the API-key filter surfaces its `detail`; `409` from a conflicting status transition surfaces its `detail`; a storage rejection of the presigned PUT (non-JSON body) surfaces the storage status with the fallback message.
- [X] T013 [US2] Reconcile any gap the T012 tests expose between `sdk/src/internal/http.ts` / `sdk/src/client.ts` and the guarantees in `specs/003-sdk-client/contracts/sdk-public-api.md § Behavioural guarantees` 1–2, changing the implementation rather than the guarantee.

**Checkpoint**: error handling is contract-complete.

---

## Phase 5: User Story 3 — Wait for a verdict without writing a polling loop (Priority: P2)

**Goal**: `waitForCompletion` with exponential backoff, a bounded budget and `AbortSignal`
support.

**Independent test**: with fake timers and a stubbed `fetch`, a sequence of in-progress reads
followed by a terminal one returns the terminal request; the deadline and the abort each stop the
loop.

- [X] T014 [US3] Declare the defaults as module-level constants in `sdk/src/client.ts` — `DEFAULT_TIMEOUT_MS = 30_000`, `DEFAULT_INITIAL_DELAY_MS = 250`, `DEFAULT_MAX_DELAY_MS = 5_000` — named per `docs/sdk/code_rules.md` § 8, never inline literals.
- [X] T015 [US3] Implement `waitForCompletion(requestId, options?)` and the private `sleep(ms, signal?)` in `sdk/src/client.ts` exactly in the shape of `docs/sdk/code_rules.md` § 5: read, return on `COMPLETED` or `FAILED`, throw a timeout `Error` naming the elapsed budget when the next sleep would cross the deadline, sleep with the `AbortSignal` listener rejecting an `AbortError` `DOMException` and clearing the timer, then double the delay clamped to `maxDelayMs`. No retry library (`docs/sdk/code_rules.md` § 9).
- [X] T016 [US3] Cover the wait in `sdk/tests/wait-for-completion.test.ts` with `vi.useFakeTimers()`: it returns the request once it reaches `COMPLETED`; it also returns on `FAILED` rather than polling on; it returns after a single read when the request is already terminal; the delay between reads doubles and stops growing at `maxDelayMs`; it throws the timeout error when the budget is exhausted; it rejects with `AbortError` and issues no further reads when the signal aborts, including when the signal is already aborted.

**Checkpoint**: the full behavioural surface of the client exists and is covered.

---

## Phase 6: User Story 4 — Adopt the library with confidence (Priority: P3)

**Goal**: the built package exposes exactly the intended surface, loads from both module
systems, and comes with a runnable example and usage notes.

**Independent test**: build the package and check the entrypoint, the emitted declarations, and
that the example runs against the local environment.

- [X] T017 [US4] Update `sdk/src/index.ts` to re-export the public surface listed in `specs/003-sdk-client/data-model.md § Public surface` — `createClient`, `ValidationApiError`, and the public types — and nothing from `sdk/src/internal/` (`docs/sdk/code_rules.md` § 7, constitution IV).
- [X] T018 [P] [US4] Write the runnable end-to-end example in `sdk/examples/validate-document.ts` following `specs/003-sdk-client/quickstart.md`: create, upload, wait, print status/verdict/reason, and catch `ValidationApiError` distinctly. Delete `sdk/examples/.gitkeep`.
- [X] T019 [P] [US4] Write `sdk/README.md` — install, `createClient` options, the four operations with signatures, the error type, the wait options, and a pointer to `docs/business-rules.md` for the verdict rule.
- [X] T020 [US4] Build and verify: `npm --prefix sdk run typecheck`, `npm --prefix sdk run build` (assert `dist/index.js`, `dist/index.cjs` and `dist/index.d.ts` are emitted), `npm --prefix sdk run test`. Delete `sdk/tests/.gitkeep`.

**Checkpoint**: the package is consumable and documented.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: keep code and docs from drifting in the same change (constitution I, FR-016,
FR-018).

- [X] T025 [P] Governance amendment to `docs/sdk/code_rules.md` (FR-019), narrowly scoped to the examples the clarified surface supersedes: § 1 — the returned closures and the header map, which now include `X-Api-Key: options.apiKey`; § 4 — `ClientOptions` gains the required `apiKey` and optional `headers`, `ValidationRequestDto.result` becomes `ValidationResult | null`, and the new option/response types are listed; § 6 — the test snippet constructs the client with an `apiKey`. The surrounding rules, § 5, § 7, § 8 and § 9 are left untouched.
- [X] T021 [P] Record the final public surface in `docs/sdk/architecture.md` § 4 — the four operations with their signatures, replacing the "names may be refined" sketch. Additive edit only; no other section is rewritten.
- [X] T022 [P] Add the `0.1.0` entry to `sdk/CHANGELOG.md` describing the surface this feature ships.
- [X] T023 [P] Update `README.md § How to build/test the SDK` so a reader can install, build, test and run the example, and point it at `sdk/README.md`.
- [X] T024 Final self-verification per the constitution's Development Workflow: re-read the whole diff against `docs/sdk/code_rules.md` §§ 1–9 (factory not class, single `fetch` call site, `ValidationApiError` everywhere, ESM-only source, naming table, no new dependency), confirm `npm --prefix sdk run typecheck`, `build` and `test` are green, and confirm nothing under `service/` or `docker/` changed.

---

## Dependencies

```
Phase 1 (T001–T002)
  └── Phase 2 (T003, T004 in parallel → T005 → T006)
        ├── Phase 3 US1 (T007 → T008, T009, T010 → T011)          ← MVP
        │     ├── Phase 4 US2 (T012 → T013)
        │     └── Phase 5 US3 (T014 → T015 → T016)
        └── Phase 6 US4 (T017 after T007–T015; T018, T019 in parallel; T020 last)
              └── Phase 7 (T025, T021, T022, T023 in parallel → T024)
```

- **US1 blocks everything behavioural**: US2 and US3 exercise the client US1 creates.
- **US2 and US3 are independent of each other** and can proceed in parallel once US1 is done.
- **US4 needs the final surface**, so `T017` waits for the operations to exist; `T018`/`T019`
  can be drafted in parallel with US2/US3 but are verified by `T020` at the end.
- **Phase 7 is last** — the docs describe what actually shipped. `T025` is the governance
  amendment cleared with the human during analysis; it lands with the rest of the doc updates.

## Parallel execution examples

- Phase 2: `T003` (errors.ts) and `T004` (types.ts) touch different files with no dependency.
- Phase 3: `T010` (`getValidation`) is independent of `T008`/`T009` in behaviour, though all
  three land in `client.ts` — sequence them if editing the same file, or write them in one pass.
- Phase 6: `T018` (example) and `T019` (`sdk/README.md`) are different files.
- Phase 7: `T025`, `T021`, `T022` and `T023` are four different files.

## Implementation strategy

Ship the MVP first: Phases 1–3 give a working client for the whole documented lifecycle, and the
tests in `T011` prove it without any infrastructure. Phases 4 and 5 harden the two behaviours the
docs treat as mandatory (typed errors, bounded polling). Phase 6 makes it consumable and Phase 7
makes the documentation match. At every checkpoint the SDK typechecks, builds and tests green.
