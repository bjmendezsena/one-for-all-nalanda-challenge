# Contract: the example's command-line interface

**Feature**: `004-example-sdk-integration` | **Date**: 2026-08-25

`example/` exposes no API. Its external contract is how it is invoked, what it reads from the
environment, what it writes to the console, and what it returns to the shell.

## Invocation

```bash
npm run example
```

From the repository root. No arguments, no flags, no subcommands — one command rebuilds `sdk/dist`,
compiles the example and runs every scenario (FR-002). `npm --prefix example run start` skips the
SDK rebuild and runs against whatever `sdk/dist` currently holds.

Prerequisites:

1. `npm run install:example` — once, to link `file:../sdk` into `example/node_modules`
2. `npm run docker:up` — `postgres`, `kafka`, `minio`
3. `npm run dev:service` — the backend on `BASE_URL`, enforced by the preflight below

## Environment

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `BASE_URL` | no | `http://localhost:8080` | Root of the API |
| `API_KEY` | no | `local-dev-api-key` | Sent as `X-Api-Key` |

Both defaults are the documented local-development values. Nothing else is read from the environment
and no secret is committed (FR-012).

## Preflight

Before the first scenario, the example issues one read against the API.

- **Reachable** — proceed to the scenarios.
- **Unreachable** — print the address it could not reach and the commands that start the stack, then
  exit `1` without running a scenario (FR-013). No stack trace.
- **Reachable but rejects the credential** — proceed. Scenario 12 covers a wrong key deliberately, so
  the credential is validated by the scenarios, not by the preflight.

## Standard output

A header, one block per scenario as it completes, then a summary.

```
Nalanda SDK example — 14 scenarios against http://localhost:8080

  [1/14] happy-path .......................................... PASS   1.4s
         covers   startValidation + upload + waitForCompletion
         expected COMPLETED with verdict PASS
         observed COMPLETED with verdict PASS, reason null

  [2/14] verdict-unsupported-type ............................ PASS   1.2s
         covers   business rule 5.1
         expected COMPLETED with verdict FAIL, reason "unsupported content type"
         observed COMPLETED with verdict FAIL, reason "unsupported content type"
         note     status is COMPLETED — a FAIL verdict is a conclusive answer, not a failed request

  ...

14 scenarios: 14 passed, 0 failed  (24.8s)
```

Every block names the scenario, what it covers, its expected outcome and its observed outcome, so a
failure is readable without opening the source (FR-009, SC-006).

A failing scenario prints the same block with `FAIL` and the mismatch:

```
  [4/14] verdict-too-large ................................... FAIL   3.1s
         covers   business rule 5.2b
         expected COMPLETED with verdict FAIL, reason "file too large"
         observed COMPLETED with verdict PASS, reason null
```

Execution continues to the next scenario (FR-011).

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Every scenario passed |
| `1` | At least one scenario failed, or the preflight found the API unreachable |

## Guarantees

- **Repeatable** — two consecutive runs produce identical pass/fail outcomes (FR-015, SC-005).
  Idempotency keys are minted per run, so no run inherits a previous run's request.
- **Bounded** — every waiting scenario has an explicit budget; the process cannot hang (FR-007).
- **Read-only towards the artifacts** — the example creates validation requests and uploads
  documents, which is the point; it changes no configuration and touches no file outside its own
  `dist/`.
