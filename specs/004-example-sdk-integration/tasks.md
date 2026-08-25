---
description: 'Task list for feature 004-example-sdk-integration'
---

# Tasks: Example project integrating the SDK

**Input**: Design documents from `/specs/004-example-sdk-integration/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/example-cli.md, quickstart.md

**Tests**: Tests are completion criteria, not a separate phase (Constitution VII). The SDK defect fix
carries a Vitest case against a mocked `fetch`. The example project *is* an acceptance harness: each
scenario asserts its own expected outcome, so its scenarios are its tests.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

## Path Conventions

This feature makes the repository a **three**-artifact monorepo (see plan.md, R-006):

- **SDK (TypeScript)**: `sdk/src/...`, tests in `sdk/tests/` — governed by `docs/sdk/code_rules.md`
- **Example (TypeScript, new)**: `example/src/...` — no code-rules file of its own; it follows
  `docs/sdk/code_rules.md` for its TypeScript, as the amendment in T004 states
- **Governing documents**: `.specify/memory/constitution.md`, `CLAUDE.md`, `README.md`,
  `docs/sdk/code_rules.md`
- `service/` is **not** touched by this feature

---

## Phase 1: Setup

**Purpose**: Make `example/` a real, installable consumer of the published package.

- [X] T001 Create `example/package.json`: private, `"type": "module"`, engines `node >= 20`, dependency `"@nalanda/validation-sdk": "file:../sdk"`, devDependencies `typescript` and `@types/node`, scripts `build` (`tsc`), `start` (`npm run build && node dist/main.js`), `typecheck` (`tsc --noEmit`)
- [X] T002 [P] Create `example/tsconfig.json`: `"strict": true`, target ES2022, module NodeNext, moduleResolution NodeNext, `rootDir: "src"`, `outDir: "dist"`, `lib: ["ES2022"]`, `types: ["node"]` — mirroring `sdk/tsconfig.json`, never relaxing `strict`
- [X] T003 [P] Add root scripts to `package.json`: `install:example` (`npm --prefix example install`), `build:example` (`npm --prefix example run build`), `example` (`npm run build:sdk && npm --prefix example run start`, so a stale `sdk/dist` cannot silently be tested); do not wire the example into the root `test` script, since it needs a live backend
- [X] T004 Add `example/dist/` and `example/node_modules/` to `.gitignore` under the existing Node section

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: no scenario can pass until T005 lands, and nothing may land until T008–T011 land.

### The blocking SDK defect (research R-004)

- [X] T005 Fix `sdk/src/internal/http.ts`: `request()` returns `undefined` for a successful response that carries no body, for **any** status and not only `204` — a presigned storage `PUT` answers `200` with an empty body, so today `upload()` throws `SyntaxError` and `confirm` never runs. Keep the helper a single function over native `fetch`; add no dependency (`docs/sdk/code_rules.md` § 2)
- [X] T006 Add `should_returnUndefined_when_responseHasNoBody` to `sdk/tests/client.test.ts` (or a new `sdk/tests/http.test.ts`), stubbing the storage `PUT` with a real body-less `Response` rather than `jsonResponse(200, {})`, and assert the confirm still runs. Verify the case is **red** with T005 reverted, then green — a test that passes either way proves nothing
- [X] T007 Add a `0.1.1` entry to `sdk/CHANGELOG.md` recording the fix (FR-016b)

### Governance amendments (research R-006) — constitution Governance section

- [X] T008 Amend `.specify/memory/constitution.md` to 1.2.0 (MINOR): recognise `example/` as a third, non-publishable artifact that consumes the SDK as an external consumer; state that `example/` follows `docs/sdk/code_rules.md`; add `example/README.md` to the Documentation Index; write the Sync Impact Report comment at the top, matching the existing style
- [X] T009 Amend `docs/sdk/code_rules.md` § 2 so the embedded `request()` snippet matches the fixed implementation from T005 — an additive correction, not a rewrite of the section (constitution I)
- [X] T010 [P] Update `CLAUDE.md`: three artifacts instead of two, one line on what `example/` is and that it is not published
- [X] T011 [P] Update `README.md`: add `example/` to the repository layout, a "Running the example" section (prerequisites in order: `docker:up`, `dev:service`, `build:sdk`, then `npm run example`), and two § Design trade-offs entries — *Example project as a separate consumer* (chosen: top-level `example/` resolving the package by name; rejected: `sdk/examples/`, which imports `dist/` by relative path and proves nothing about packaging) and *Example harness without a test framework* (chosen: a self-written scenario runner; rejected: Vitest against a live backend, which adds a dependency and collides with the SDK's mocked-`fetch` test style)

**Checkpoint**: `npm run test:sdk` green, `npm run build:sdk` green, docs consistent.

---

## Phase 3: User Story 1 — Run the happy path end to end (P1) 🎯 MVP

**Goal**: One command carries `justificante.pdf` from registration to `COMPLETED` / `PASS`.

**Independent test**: with the stack up, `npm run example` reports scenario `happy-path` as PASS and
exits `0`.

- [X] T012 [P] [US1] Create `example/src/config.ts`: read `BASE_URL` (default `http://localhost:8080`) and `API_KEY` (default `local-dev-api-key`) from the environment into an `ExampleConfig`, per data-model.md. No secret in the file
- [X] T013 [P] [US1] Create `example/src/documents.ts`: load the committed `example/justificante.pdf` into `valid`, build `empty` (0 bytes) and `oversized` (`MAX_DOCUMENT_SIZE_IN_BYTES + 1`) at run time; declare `MAX_DOCUMENT_SIZE_IN_BYTES = 15 * 1024 * 1024` as a named constant quoting `docs/business-rules.md` § 5 — never an inline literal (FR-014). Confirm `example/justificante.pdf` is tracked by git — it is currently untracked, and FR-014 requires the sample to ship
- [X] T014 [US1] Create `example/src/runner.ts`: the `Scenario`, `ScenarioContext`, `ScenarioOutcome` and `RunReport` shapes from data-model.md; run scenarios sequentially; catch a throw as a failed scenario and keep going (FR-011); print each block with title, covers, expected, observed and duration, then the summary line (FR-009, contracts/example-cli.md)
- [X] T015 [US1] Create `example/src/main.ts`: build the config and the SDK client via `createClient` imported **by package name** from `@nalanda/validation-sdk` (never from `../sdk/dist`), run the preflight read, print the actionable unreachable-API message and exit `1` when it fails (FR-013), otherwise run the scenarios and exit `0` only when none failed (FR-010). A missing or unbuilt library is a distinct failure naming the prerequisite that fixes it (FR-013a)
- [X] T016 [US1] Create `example/src/scenarios.ts` with scenario 1 `happy-path`: `startValidation` with a per-run idempotency key from `crypto.randomUUID()` (R-008), `upload` the valid PDF as `Uint8Array` with `application/pdf`, `waitForCompletion`, assert `COMPLETED` / `PASS` / `reason: null`
- [X] T017 [US1] Run it end to end against the live stack and confirm the report shows `happy-path` PASS and exit code `0`

**Checkpoint**: the MVP is deliverable — the SDK is proven to work as an installed package.

---

## Phase 4: User Story 2 — See every documented verdict reproduced (P2)

**Goal**: all four documented verdict outcomes visible in one report.

**Independent test**: the report lists scenarios 2–4 alongside `happy-path`, each with its documented
reason.

- [X] T018 [US2] Add the three documented reasons to `example/src/scenarios.ts` as named constants quoting `docs/business-rules.md` § 5: `"unsupported content type"`, `"empty file"`, `"file too large"`
- [X] T019 [P] [US2] Scenario 2 `verdict-unsupported-type`: create with `contentType: "image/png"`, upload the valid bytes as a `string` payload **passing `"image/png"` as the upload content type**, assert `COMPLETED` / `FAIL` / `"unsupported content type"`. The presigned URL signs the content type (research R-009), so a mismatch is a 403, not a verdict
- [X] T020 [P] [US2] Scenario 3 `verdict-empty`: create with `application/pdf`, `upload` the zero-byte document passing `"application/pdf"`, assert `COMPLETED` / `FAIL` / `"empty file"`
- [X] T021 [P] [US2] Scenario 4 `verdict-too-large`: create with `application/pdf`, upload the oversized buffer as a `Blob` with `"application/pdf"`, assert `COMPLETED` / `FAIL` / `"file too large"`; give this scenario a wider `waitForCompletion` budget so the ~16 MB upload stays inside SC-004
- [X] T022 [US2] Make the runner print, for any `FAIL` verdict, the note that the status is still `COMPLETED` — a conclusive answer, not a failed request (US2 acceptance scenario 4, constitution V)

**Checkpoint**: the verdict rule is demonstrated, not described.

---

## Phase 5: User Story 3 — Exercise the remaining integrated behaviors (P3)

**Goal**: 100% of the SDK's published surface covered (SC-002).

**Independent test**: the report lists scenarios 5–13, each with an explicit expected-versus-observed
line.

- [X] T023 [P] [US3] Scenario 5 `idempotent-create`: `startValidation` twice with the same per-run key and a **different** filename the second time; assert the same `requestId` comes back and no new request was created (`docs/business-rules.md` § 6)
- [X] T024 [P] [US3] Scenario 6 `read-before-completion`: `getValidation` on a freshly created request; assert the current status and that `result` is absent or null
- [X] T025 [P] [US3] Scenario 7 `wait-timeout`: `waitForCompletion` on an unconfirmed request with `timeoutMs: 750`, `initialDelayMs: 100`, `maxDelayMs: 200`; assert a plain `Error` whose message names the timeout — **not** a `ValidationApiError`
- [X] T026 [P] [US3] Scenario 8 `wait-aborted`: `waitForCompletion` on an unconfirmed request with an `AbortController` aborted mid-wait; assert a `DOMException` named `AbortError`
- [X] T027 [P] [US3] Scenario 9 `read-unknown`: `getValidation` with a fresh `crypto.randomUUID()` that was never created; assert `ValidationApiError` with `status: 404` and a non-empty `body.detail`. Use a **well-formed UUID** — a non-UUID path segment returns 500, not 404 (research R-005)
- [X] T028 [P] [US3] Scenario 10 `create-invalid-input`: `startValidation` with a blank `filename` (a deliberate cast past the published types); assert `ValidationApiError` with `status: 400`, `body.detail: "Validation failed"` and a `body.errors` entry naming the `filename` field
- [X] T029 [P] [US3] Scenario 11 `confirm-replay`: `upload` the same target twice; assert the second call succeeds and returns the current status without restarting processing (`docs/service/upload-flow.md` § 4)
- [X] T030 [P] [US3] Scenario 12 `wrong-api-key`: a second client from `createClient` with a deliberately wrong key; assert `ValidationApiError` with `status: 401` and a specific `body.detail`. Never read a real credential for this
- [X] T031 [P] [US3] Scenario 13 `client-options`: a client built with a trailing-slash `baseUrl` and an extra `headers` entry; assert the call succeeds, covering `ClientOptions.baseUrl` normalisation and `headers` merging
- [X] T032 [P] [US3] Scenario 14 `upload-without-content-type`: `upload` a fresh target **omitting** the `contentType` argument, covering the SDK's documented no-`Content-Type` path. The expected outcome **is the rejection**: the presigned URL signed the content type (research R-009), so storage answers `400` `AccessDenied` ("headers present in the request which were not signed"). Assert `ValidationApiError` with `status: 400` and `body === undefined` — storage answers XML, not Problem Details, which is the documented fallback in `sdk/README.md` § Errors and the only path nothing else covers. Assert too that `confirm` was not attempted, so the request stays in `PENDING_UPLOAD`
- [X] T033 [US3] Register scenarios 1–14 in order in `example/src/scenarios.ts` and confirm every exported SDK type (`ValidationStatus`, `Verdict`, `ValidationResult`, `ValidationRequestDto`, `StartValidationResponse`, `ConfirmUploadResponse`, `UploadTarget`, `UploadData`, `WaitForCompletionOptions`, `ProblemDetailsBody`, `ValidationApiError`) is named in a signature or an assertion, so a regression in the published `.d.ts` fails the build (SC-007)

**Checkpoint**: the whole published surface is covered by evidence.

---

## Phase 6: Polish & Cross-Cutting

- [X] T034 Write `example/README.md`: what it is, what the 14 scenarios cover (the table from plan.md), prerequisites in order, how to run, how to point it elsewhere with `BASE_URL`/`API_KEY`, exit codes, and the troubleshooting table from quickstart.md (FR-016)
- [X] T035 Verify FR-015 and SC-005: run `npm run example` twice in a row and confirm identical pass/fail outcomes for all 14 scenarios
- [X] T036 Verify SC-004: a full run completes in under two minutes; report the measured time
- [X] T037 Verify FR-013: stop the backend, run the example, confirm the actionable unreachable message and exit code `1` with no stack trace
- [X] T038 Verify the guard rails: `npm --prefix example run typecheck` clean under `"strict": true`; `grep` the example for `sdk/dist` and `src/internal` and confirm neither is imported
- [X] T039 Self-verification per the constitution's Development Workflow: `npm run test:sdk` and `npm run build:sdk` green, the full diff re-read, `docs/sdk/code_rules.md` § 9 re-checked against every line of `example/` and the `sdk/` change, and all amended documents consistent with what shipped

---

## Dependencies

```
Phase 1 (T001–T004)  setup
        ↓
Phase 2 (T005–T011)  BLOCKING: SDK fix + governance amendments
        ↓
Phase 3 (T012–T017)  US1 — MVP, deliverable on its own
        ↓
Phase 4 (T018–T022)  US2 — needs the runner from US1
        ↓
Phase 5 (T023–T033)  US3 — needs the runner from US1
        ↓
Phase 6 (T034–T039)  polish + verification
```

- **T005 blocks everything downstream**: without it no upload scenario can pass.
- **T014 and T015 block every scenario task**: scenarios need the runner and the context.
- **US2 and US3 are independent of each other** — either can follow US1.
- T008 and T009 are sequential (both are constitution-governed amendments landing together); T010 and
  T011 are parallel to each other.
- T018 precedes T019–T021: those three assert the constants it declares.
- T033 closes Phase 5 and depends on every scenario task before it.

## Parallel execution examples

**Phase 2 docs**: T010 and T011 touch different files and can run together, after T008/T009.

**Phase 4**: T019, T020 and T021 are three independent scenarios — write them in parallel, then T022.

**Phase 5**: T023 through T032 are ten independent scenarios, all parallelizable; T033 closes the
phase by registering them.

## Implementation strategy

1. **Unblock first** (Phase 2). The SDK fix and the amendments are the price of admission; without
   T005 the MVP cannot go green, and without T008–T011 the change lands in documented violation.
2. **MVP at Phase 3.** `happy-path` alone already proves the SDK works as an installed package — the
   single most valuable claim this feature makes.
3. **Widen the evidence** (Phases 4 and 5), verdicts before edge cases, so the business behavior is
   demonstrated before the error surface.
4. **Prove the claims** (Phase 6). Repeatability, timing and the unreachable path are acceptance
   criteria, so they are verified by running them, not by asserting they hold.
