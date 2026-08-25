# Implementation Plan: Example project integrating the SDK

**Branch**: `004-example-sdk-integration` | **Date**: 2026-08-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-example-sdk-integration/spec.md`

## Summary

Add `example/`: a small, typed Node program that consumes `@nalanda/validation-sdk` the way an
external consumer would — declared in its own manifest as `file:../sdk`, imported by package name —
and runs 14 assertion-backed scenarios against a live local backend, covering the SDK's entire public
surface and every documented business rule it mirrors. It prints a readable report and exits non-zero
if any scenario failed.

Two things ride along because the feature cannot land without them:

1. **A defect fix in `sdk/`** (R-004). `request()` parses every non-`204` success as JSON, but the
   presigned storage `PUT` answers `200` with an empty body, so `upload()` throws `SyntaxError` and
   `confirm` never runs. Without the fix, US1 and US2 can never pass.
2. **Amendments to the governing documents** (R-006), because `example/` is a third top-level
   artifact and because `docs/sdk/code_rules.md` § 2 embeds the buggy snippet.

## Technical Context

**Language/Version**: TypeScript 5.9 targeting Node.js 20 (`.nvmrc`), `"strict": true`

**Primary Dependencies**: `@nalanda/validation-sdk` via `file:../sdk` (runtime). Dev: `typescript`
(already the SDK's toolchain) and `@types/node` (**new to the repository** — see Complexity Tracking).
No test framework, no HTTP client, no assertion library.

**Storage**: N/A — the example holds no state; documents are read from disk or built in memory.

**Testing**: The example *is* the test. A self-written scenario runner, no framework (R-003). The
`sdk/` defect fix is covered by a Vitest case in the SDK's existing suite, per
`docs/sdk/code_rules.md` § 6.

**Target Platform**: Local developer machine, Node ≥ 20, against `docker/docker-compose.yml` plus a
running backend.

**Project Type**: CLI demonstration + acceptance harness. Not publishable.

**Performance Goals**: A full run under two minutes (SC-004). Sequential scenarios; the only heavy
step is one ~16 MB upload to local MinIO.

**Constraints**: No new endpoint, status, event or SDK operation. No committed secret or binary
beyond the existing ~1 KB sample PDF. Runs must be repeatable (SC-005).

**Scale/Scope**: 14 scenarios, ~6 source files in `example/`, one changed line of SDK behavior plus
its test.

## Constitution Check

_GATE: evaluated before Phase 0 and re-checked after Phase 1 design._

| Principle | Verdict | Note |
|---|---|---|
| I — Documentation is the source of truth | **PASS (with amendment)** | `example/` contradicts the two-artifact description, so the constitution, `CLAUDE.md`, `README.md` and the Documentation Index are amended in this same change (R-006). Nothing lands undocumented. |
| II — Spec-driven development | PASS | Full pipeline; the SDK defect was raised as a question before planning, not silently fixed. |
| III — Code rules are non-negotiable | **PASS (with amendment)** | `example/` is a new artifact with no code-rules file; it follows `docs/sdk/code_rules.md` for its TypeScript, and the amendment says so explicitly. The § 2 snippet change is an amendment, not an inline edit, per Governance. |
| IV — Architecture & boundaries | PASS | The example touches only the SDK's public surface; nothing is imported from `src/internal/**` or from `sdk/dist` by path. |
| V — Explicit contracts & errors | PASS | Scenarios assert `FAILED` (status) and `verdict: "FAIL"` are distinct, and that every error surfaces as `ValidationApiError` with a specific reason. |
| VI — Asynchronous processing integrity | PASS | Read-only observation of the flow. The confirm-replay scenario asserts the documented no-op instead of assuming it. |
| VII — Quality & testing | PASS | The SDK fix ships with a test that fails without it (FR-016b). The example itself is assertion-backed with a meaningful exit code. |
| VIII — Security & configuration | PASS | API address and key come from the environment with the documented local defaults; no secret committed. The 401 scenario uses a deliberately wrong key, never a real one. |
| IX — Approved stack & operations | **PASS (with note)** | No new infrastructure. `typescript` is already in use; `@types/node` is a genuinely new devDependency, flagged for approval in Complexity Tracking. |
| Scope discipline | PASS | No endpoint, status, event or option added. FR-016a is a defect fix, not new surface. |
| Comments policy | PASS | No comments by default in `example/`. |

**Post-Phase-1 re-check**: unchanged. The design adds no layer, no dependency beyond the two named,
and no public surface to either existing artifact.

## Project Structure

### Documentation (this feature)

```text
specs/004-example-sdk-integration/
├── plan.md              # This file
├── spec.md
├── research.md          # Phase 0 — verified findings, including the SDK defect
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── example-cli.md   # Phase 1 — the example's CLI/env/exit-code contract
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
one-for-all-nalanda-challenge/
├── .specify/memory/constitution.md   # AMENDED — recognises example/ (1.1.0 → 1.2.0)
├── CLAUDE.md                         # AMENDED — three artifacts, not two
├── README.md                         # AMENDED — repo layout, how to run, § Design trade-offs
├── package.json                      # root scripts: install:example, build:example, example
├── docs/
│   └── sdk/code_rules.md             # AMENDED — § 2 snippet matches the fixed request()
├── sdk/
│   ├── src/internal/http.ts          # FIXED — body-less success on any status
│   ├── tests/client.test.ts          # NEW CASE — fails without the fix
│   └── CHANGELOG.md                  # 0.1.1 entry
└── example/                          # NEW ARTIFACT
    ├── package.json                  # "@nalanda/validation-sdk": "file:../sdk"
    ├── tsconfig.json                 # strict, extends the SDK's conventions
    ├── README.md                     # what it covers, prerequisites, how to run
    ├── justificante.pdf              # already present — the valid sample document
    └── src/
        ├── main.ts                   # entrypoint: config → preflight → run → report → exit code
        ├── config.ts                 # env with documented defaults + reachability preflight
        ├── documents.ts              # valid / empty / oversized document set
        ├── runner.ts                 # Scenario type, sequential execution, report
        └── scenarios.ts              # the 14 scenarios
```

**Structure Decision**: `example/` is a flat, self-contained npm project — no layers, no framework.
The SDK's discipline applies at its own scale: `main.ts` wires, `runner.ts` executes and reports,
`scenarios.ts` declares, and nothing reaches past the SDK's public entrypoint. The layout deliberately
mirrors what a real consumer's repository would look like, because that realism is the feature.

## Scenario inventory

Coverage is the acceptance criterion (SC-002), so the mapping is explicit.

| # | Scenario | SDK surface covered | Expected outcome |
|---|---|---|---|
| 1 | Valid PDF, end to end | `startValidation`, `upload`, `waitForCompletion` | `COMPLETED` / `PASS`, `reason: null` |
| 2 | Non-PDF content type | verdict rule 1 | `COMPLETED` / `FAIL` / `"unsupported content type"` |
| 3 | Zero-byte document | verdict rule 2a | `COMPLETED` / `FAIL` / `"empty file"` |
| 4 | 15 MiB + 1 document | verdict rule 2b, `UploadData` as `Uint8Array` | `COMPLETED` / `FAIL` / `"file too large"` |
| 5 | Idempotency key replayed with a different body | `StartValidationCallOptions.idempotencyKey` | same `requestId`, no new request |
| 6 | Read before completion | `getValidation` | current status, `result` absent |
| 7 | Wait on an unconfirmed request | `timeoutMs`, `initialDelayMs`, `maxDelayMs` | `Error` naming the timeout |
| 8 | Cancel a wait in flight | `signal` | `DOMException` named `AbortError` |
| 9 | Read an unused UUID | `ValidationApiError.status`, `.body.detail` | `404` with a specific reason |
| 10 | Create with a blank `filename` | `ValidationApiError.body.errors` | `400`, per-field messages |
| 11 | Confirm an already-confirmed request | confirm replay | succeeds, returns current status |
| 12 | Wrong API key | `ClientOptions.apiKey` | `401` with a specific reason |
| 13 | Trailing-slash `baseUrl` + extra header | `ClientOptions.baseUrl`, `.headers` | call succeeds, URL well formed |
| 14 | Upload omitting `contentType` | `upload`'s optional `contentType`, non-JSON error body | `400`, `ValidationApiError.body` undefined |

`UploadData` variants are spread across scenarios (`Uint8Array`, `string`, `Blob`) so the union is
exercised rather than described. Every uploading scenario passes the same content type it declared at
creation, because the presigned URL signs it (research R-009); scenario 14 is the one that omits it,
and its expected outcome is the resulting rejection. Every exported type is named in a scenario's signatures, so a
regression in the published `.d.ts` fails the compile step (SC-007).

## Complexity Tracking

| Violation | Why needed | Simpler alternative rejected because |
|---|---|---|
| Third top-level artifact (`example/`) | Only an external consumer resolving the package by name exercises the `exports` map, the `files` allowlist and the published `.d.ts` — the thing this feature exists to prove (R-001) | `sdk/examples/` needs no governance change but imports `../dist/index.js` by relative path, so it proves none of that. Rejected by the user during clarification. |
| `@types/node` devDependency | The example is typed under `"strict": true` and needs `process`, `node:fs/promises` and `node:crypto`. Unavoidable for a typed Node program | Writing the example in plain `.mjs` avoids it, but leaves the published type declarations untested (FR-001a). |
| Touching `sdk/` in an example feature | Without the fix the presigned `PUT` throws `SyntaxError` and the happy path can never pass (R-004) | Shipping the example red demonstrates the harness works but delivers a feature failing its own acceptance criteria. Rejected by the user. |
| Amending the constitution and a code-rules file | Principle I: the governing documents deny a third artifact, and § 2 of the SDK code rules embeds the defective snippet | Landing the code without the amendment puts the repository in documented violation on day one. |
