# Feature Specification: Example project integrating the SDK

**Feature Branch**: `004-example-sdk-integration`

**Created**: 2026-08-25

**Status**: Draft

**Input**: User description: "Crea un peuqeño proyecto dentro de la carpeta /example integrando el sdk. Debe probar todo lo que está integrado"

## Clarifications

### Session 2026-08-25

- Q: `example/` would be a third top-level artifact, while the constitution and `CLAUDE.md` describe a two-artifact monorepo (`service/`, `sdk/`). How is it reconciled? → A: Keep it at `example/` as a non-publishable demo consumer, and amend `.specify/memory/constitution.md`, `CLAUDE.md` and `README.md` in the same change so the docs recognise it.
- Q: `sdk/examples/validate-document.mjs` already exists — does it count as integrating the SDK? → A: No. It imports `../dist/index.js` by relative path, so it exercises neither package-name resolution, nor the `exports` map, nor installation as a dependency, nor the published type declarations. The example project must consume the SDK as an external consumer would: its own manifest declaring the SDK as a local file dependency, imported by package name, written in a typed language so the published declarations are exercised too.
- Q: What form does "test everything that is integrated" take? → A: A self-contained scenario runner with no test-framework dependency: each scenario declares its expected outcome, compares it with the observed one, prints a readable report, and the process exits non-zero if any scenario failed.
- Q: The client library parses every non-`204` response as JSON, but a presigned storage `PUT` answers `200` with an empty body, so the upload operation raises a parse error and the confirmation never runs — the happy path cannot pass. Fix here or elsewhere? → A: Fix it inside this feature. The client library must treat a successful response with no body as a body-less success for any status, not only `204`, covered by a test that fails without the fix. This narrows FR-017.

## User Scenarios & Testing _(mandatory)_

### User Story 1 - Run the happy path end to end (Priority: P1)

Someone evaluating the project wants to see the whole document-validation journey actually work.
From the `example/` project they run a single command and watch one scenario carry a real PDF from
"intent registered" all the way to a conclusive `PASS` verdict, with each step reported on screen.

**Why this priority**: This is the core promise of the SDK — one small program proving the
integration is real, not just unit-tested in isolation. On its own it is already a viable
deliverable.

**Independent Test**: With the local stack and the backend running, execute the example's single
entry command and observe a scenario that registers a validation, uploads the sample PDF, waits for
completion, and reports `COMPLETED` with verdict `PASS`.

**Acceptance Scenarios**:

1. **Given** the backend and the local infrastructure are running, **When** the example is executed,
   **Then** it registers a validation request and reports the returned request identifier and an
   initial status of `PENDING_UPLOAD`.
2. **Given** a registered request, **When** the example uploads the sample PDF bytes and confirms
   them, **Then** it reports the request moved out of `PENDING_UPLOAD`.
3. **Given** a confirmed upload, **When** the example waits for the request to finish, **Then** it
   reports final status `COMPLETED` with verdict `PASS` and no failure reason.
4. **Given** the whole scenario succeeded, **When** the run ends, **Then** the process reports
   overall success and exits with a success code.

---

### User Story 2 - See every documented verdict reproduced (Priority: P2)

The same person wants proof that the deterministic verdict rule behaves as documented, not only in
the happy case. The example runs one scenario per documented outcome so all three failure reasons
and the passing case are visible side by side in a single report.

**Why this priority**: Reproducing the verdict rule is what makes the example a demonstration of the
*business* behavior rather than a single smoke test. It depends on nothing beyond User Story 1's
machinery.

**Independent Test**: Execute the example and read the report: it lists one scenario per documented
verdict outcome, each showing the expected verdict and reason.

**Acceptance Scenarios**:

1. **Given** a document whose declared content type is not the supported one, **When** the scenario
   runs to completion, **Then** the final status is `COMPLETED`, the verdict is `FAIL` and the
   reason is the documented "unsupported content type".
2. **Given** a document with zero bytes, **When** the scenario runs to completion, **Then** the final
   status is `COMPLETED`, the verdict is `FAIL` and the reason is the documented "empty file".
3. **Given** a document larger than the documented size ceiling, **When** the scenario runs to
   completion, **Then** the final status is `COMPLETED`, the verdict is `FAIL` and the reason is the
   documented "file too large".
4. **Given** any of the above scenarios, **When** the verdict is `FAIL`, **Then** the report makes
   explicit that the status is still `COMPLETED` — a failing verdict is not a failed request.

---

### User Story 3 - Exercise the remaining integrated behaviors (Priority: P3)

The evaluator wants the example to cover every capability the client library exposes, not just the
validation journey: replaying an idempotency key, reading a request on demand, cancelling a wait,
and receiving actionable errors.

**Why this priority**: These behaviors round out "everything that is integrated". They are valuable
but secondary to demonstrating the main journey and the verdict rule.

**Independent Test**: Execute the example and confirm the report contains one scenario per behavior
listed below, each with an explicit expected-versus-observed outcome.

**Acceptance Scenarios**:

1. **Given** a validation registered with an idempotency key, **When** the same key is replayed with
   a different document description, **Then** the original request identifier is returned and no new
   request is created.
2. **Given** a registered request, **When** the example reads it on demand before processing has
   finished, **Then** it reports the current status and an absent result.
3. **Given** a request that has not been confirmed, **When** the example waits for it with a
   deliberately short budget, **Then** the wait ends in a reported timeout and the scenario is
   counted as the expected outcome, not as a crash.
4. **Given** a wait in progress, **When** the example cancels it, **Then** the wait ends promptly as
   a reported cancellation.
5. **Given** a request identifier that does not exist, **When** the example reads it, **Then** it
   reports a not-found API error carrying the HTTP status and a specific, human-readable reason.
6. **Given** a creation payload that violates the documented input rules, **When** the example
   submits it, **Then** it reports a rejection carrying the HTTP status and the per-field messages,
   and no request is created.
7. **Given** an already-confirmed request, **When** the example confirms it again, **Then** the
   repeated confirmation succeeds and reports the current status without restarting processing.
8. **Given** an invalid credential, **When** the example calls the API, **Then** it reports an
   authentication error carrying the HTTP status rather than an unhandled failure.
9. **Given** an upload that omits the document's content type, **When** the bytes are sent to the
   storage destination, **Then** the rejection is reported as an API error carrying the HTTP status,
   with no parsed error body — the destination answers in a format that is not the API's error
   format — and the request is left un-confirmed.

---

### Edge Cases

- **A successful response with no body.** The presigned storage upload answers with a success status
  and an empty body; the client library must treat that as success rather than as a malformed
  payload, otherwise the confirmation step is never reached.

- **The backend is not running.** The example must fail with a message naming the unreachable
  address and how to start the stack, not with an unhandled stack trace.
- **A scenario's expected outcome does not occur.** The run must continue through the remaining
  scenarios, mark that one as failed in the report, and end with a failure exit code.
- **Processing takes longer than expected.** Each waiting scenario has a bounded budget; exceeding it
  is reported as a timed-out scenario rather than hanging indefinitely.
- **The example is run twice in a row.** Every run must produce the same outcomes; replayed
  idempotency keys must not make a second run report false failures.
- **The oversized document.** The example must obtain a document above the size ceiling without a
  large binary being committed to the repository.

## Requirements _(mandatory)_

### Functional Requirements

- **FR-001**: A self-contained example project MUST live in the repository's `example/` directory and
  consume the client library the way an external consumer would — declaring it as a dependency in its
  own manifest and importing it by package name — rather than reaching for the API by hand or reaching
  into the library's build output by relative path.
- **FR-001a**: The example MUST be written in a typed language so that the client library's published
  type declarations are exercised, and the run MUST fail if they do not typecheck.
- **FR-002**: Once its prerequisites are in place, the example MUST run every scenario from a single
  documented command taking no arguments. Bringing up the infrastructure, the backend and the built
  library are prerequisites, not part of that command.
- **FR-003**: The example MUST exercise every operation the client library exposes publicly, and every
  documented option of those operations.
- **FR-004**: The example MUST exercise every documented verdict outcome (`PASS`, and `FAIL` with each
  of the three documented reasons).
- **FR-005**: The example MUST exercise the documented idempotency behavior on creation and the
  documented replay behavior on confirmation.
- **FR-006**: The example MUST exercise the library's error surface, including a not-found read, a
  rejected creation payload with per-field messages, and an unauthenticated call.
- **FR-007**: The example MUST exercise both the cancellation and the timeout paths of the waiting
  operation.
- **FR-008**: Each scenario MUST declare its expected outcome up front and assert the observed outcome
  against it; a mismatch MUST be reported as a failed scenario.
- **FR-009**: The example MUST print a human-readable report naming every scenario, its expected
  outcome, its observed outcome, and its pass/fail state, produced by the example itself without
  depending on a test framework.
- **FR-010**: The run MUST exit with a success code only when every scenario passed, and with a
  failure code otherwise.
- **FR-011**: One scenario failing MUST NOT prevent the remaining scenarios from running.
- **FR-012**: The example MUST read the API address and the credential from the environment, falling
  back to the documented local-development defaults, and MUST NOT contain a committed secret.
- **FR-013**: The example MUST fail with an actionable message, naming the address it could not reach,
  when the API is unavailable.
- **FR-013a**: When the client library cannot be resolved at all — not installed, or not built — the
  failure MUST name the missing prerequisite and the command that satisfies it, distinctly from an
  unreachable API.
- **FR-014**: The example MUST ship the small sample document it needs, and MUST generate the empty
  and oversized documents at run time instead of committing them.
- **FR-015**: Running the example twice in a row MUST produce the same outcomes.
- **FR-016**: The repository documentation MUST state what the example covers, its prerequisites, and
  how to run it.
- **FR-016a**: A successful response carrying no body MUST be handled by the client library as a
  body-less success regardless of its status code, so that confirming a presigned storage upload
  proceeds instead of raising a parse error.
- **FR-016b**: The fix in FR-016a MUST be covered by a client-library test that fails without it, and
  recorded in the library's changelog.
- **FR-017**: Beyond FR-016a, the example MUST NOT change the behavior, the public surface, or the
  HTTP contract of either existing artifact. FR-016a is a defect fix: it adds no operation, option,
  type, or endpoint, and no currently-succeeding call changes its result.
- **FR-018**: The governing documents that describe the repository as a two-artifact monorepo MUST be
  amended in the same change to recognise the example as a third, non-publishable artifact.

### Key Entities

- **Scenario**: One named, independently runnable demonstration. Carries a title, the capability it
  covers, its expected outcome, and — after execution — its observed outcome and pass/fail state.
- **Run report**: The ordered collection of executed scenarios plus the totals that determine the
  process exit code.
- **Sample document set**: The documents the scenarios feed to the API — a committed small valid PDF,
  plus an empty one and an oversized one produced at run time.

## Success Criteria _(mandatory)_

### Measurable Outcomes

- **SC-001**: A newcomer with the local stack running can execute the example and read its result
  without editing any file.
- **SC-002**: Every operation and every documented option of the client library's public surface is
  covered by at least one scenario — 100% coverage of the published surface. An option that cannot
  succeed against this backend counts as covered when a scenario asserts its documented failure.
- **SC-003**: All four documented verdict outcomes appear in the report of a single run.
- **SC-004**: A full run finishes in under two minutes on a local machine.
- **SC-005**: Two consecutive runs produce identical pass/fail outcomes for every scenario.
- **SC-006**: A reader of the report can tell, for any failed scenario, what was expected and what
  happened, without reading the example's source.
- **SC-007**: The example resolves the client library by package name, so a packaging regression in
  the library's manifest or published declarations makes the example's run fail.

## Assumptions

- The example is a demonstration and acceptance harness for the client library, not a new product
  capability: it adds no endpoint, status, event, or option to either existing artifact.
- The example runs against a live local backend plus the local infrastructure; it does not simulate
  or stub the API, because its purpose is to prove the real integration works.
- "Everything that is integrated" is scoped to the client library's published public surface and the
  documented business rules it mirrors — it does not extend to backend internals.
- Scenarios assert their own expected outcomes and report themselves, rather than relying on a reader
  to interpret raw output.
- The sample PDF already present in `example/` is the valid document used by the passing scenario: it
  is a well-formed PDF of 1026 bytes, so it sits inside the documented size range and yields a passing
  verdict.
- The client library must be built before the example runs, since the example consumes its published
  output through the package manifest.
- The pre-existing usage snippet under `sdk/examples/` stays as it is; it is a snippet inside the
  package, not an integration, and this feature neither replaces nor removes it.
- Existing repository conventions for reading configuration from the environment, with
  local-development defaults, apply unchanged.
