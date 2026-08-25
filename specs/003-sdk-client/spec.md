# Feature Specification: SDK client for the document validation API

**Feature Branch**: `003-sdk-client`

**Created**: 2026-08-25

**Status**: Draft

**Input**: User description: "Dada la documentación, implementa todo el sdk."

## Overview

The TypeScript client library (`sdk/`) is currently a compiling skeleton: the entrypoint,
the client factory, the types module, the error module and the internal HTTP module all
exist but are empty placeholders. This feature completes it, so that an application
developer consuming the validation API never has to hand-write `fetch` calls, URL joining,
authentication headers, error parsing or polling loops.

The scope is exactly the documented public surface — five operations — plus the typed error
and the request/response types that mirror the API responses. Nothing beyond that surface is
added, and the HTTP contract of the backend does not change.

## Clarifications

### Session 2026-08-25

- Q: The documented public surface lists no confirm operation, and `upload(requestId, data)` cannot issue the direct upload without the upload destination (the client holds no state and the read operation does not return it). What shape do upload and confirm take? → A: `upload(target, data, contentType?)`, where `target` is the object returned by `startValidation` (carrying both the request identifier and the upload destination); the operation performs the direct upload and then the confirm, keeping the public surface at the documented five entries.
- Q: What is the shape of the client options — the skeleton requires an API credential, while the code-rules example merges caller-supplied headers? → A: `{ baseUrl, apiKey, headers? }`; the credential is required (the API answers 401 without it) and extra headers are optional.
- Q: How does the caller supply the idempotency key documented for the create operation? → A: as an optional second argument to `startValidation`, so the input type stays a mirror of the request body.
- Q: The SDK code-rules snippets (§§ 1, 4, 6) show a signature, a header map, a client construction and a result type that the clarified surface supersedes; amending that file is a governance action. What do we do? → A: amend those snippets in this same change, declared as a governance amendment, so the documentation never shows code that would not compile.

## User Scenarios & Testing _(mandatory)_

### User Story 1 - Run a document through validation end to end (Priority: P1)

An application developer installs the library, creates a client pointing at the validation
API with their API key, and drives one document through the whole lifecycle: register the
validation, upload the bytes, confirm the upload, and read the outcome. They never construct
a URL, set an authentication header, or interpret an HTTP status code by hand.

**Why this priority**: This is the reason the library exists. Without the four lifecycle
operations there is no usable client, and every other story builds on them.

**Independent Test**: Drive the four operations against a stubbed transport and assert that
each one issues the documented call and returns the documented shape; the sequence delivers a
complete validation lifecycle on its own.

**Acceptance Scenarios**:

1. **Given** a configured client, **When** the developer starts a validation with a filename
   and a content type, **Then** they receive the request identifier, the initial status, and
   the upload destination for the document bytes.
2. **Given** the object returned by the start operation, **When** the developer uploads the
   document bytes through the client, **Then** the bytes are sent directly to the storage
   destination with the document's content type, and the API's authentication credential is
   not attached to that upload.
3. **Given** the bytes were accepted by storage, **When** the same upload call continues,
   **Then** it confirms the upload against the API and returns the request identifier and the
   status the request now holds.
4. **Given** a confirmed validation, **When** the developer reads the validation, **Then**
   they receive its current status, plus the result (verdict, extracted fields, reason) once
   the validation has produced one.
5. **Given** a client configured with a base address that ends in a trailing separator,
   **When** any operation runs, **Then** the address is normalised so no malformed target is
   produced.

---

### User Story 2 - Understand and react to a rejection (Priority: P2)

When the API rejects a call — unknown request identifier, missing or wrong credential,
structurally invalid input, a conflicting state transition — the developer gets one
predictable, typed failure carrying the HTTP status and the machine-readable problem body,
including field-level details when the API supplies them. They can branch on the status
and display the reason without parsing anything themselves.

**Why this priority**: The documentation makes a meaningful, actionable reason mandatory for
every rejection. A client that surfaces raw transport failures would break that guarantee for
every consumer.

**Independent Test**: Stub rejection responses covering the documented failure classes and
assert that each one surfaces as the library's own error type carrying status and body.

**Acceptance Scenarios**:

1. **Given** the API rejects a read for an unknown request identifier, **When** the developer
   calls the read operation, **Then** the call fails with the library's typed error exposing
   the HTTP status and the problem body's reason.
2. **Given** the API rejects a creation because of invalid input and returns field-level
   details, **When** the developer starts a validation, **Then** the typed error exposes those
   field-level details alongside the status.
3. **Given** the API rejects a call with a body that is not the documented problem shape (or
   with no body at all), **When** the call fails, **Then** the developer still gets the typed
   error with the HTTP status and a usable message rather than an unhandled parse failure.
4. **Given** the direct upload to the storage destination is rejected, **When** the developer
   uploads, **Then** the failure surfaces as the same typed error carrying the storage
   response's status.

---

### User Story 3 - Wait for a verdict without writing a polling loop (Priority: P2)

Because validation is asynchronous, the developer wants to hand the request identifier to the
library and be given back the finished validation, with a bounded wait, increasing pauses
between checks so a slow validation does not hammer the API, and the ability to cancel the
wait from the outside.

**Why this priority**: It is part of the documented public surface and the main ergonomic
gain over calling the API directly, but a consumer can still poll manually with the read
operation from Story 1, so it ranks below the lifecycle itself.

**Independent Test**: Stub a sequence of in-progress reads followed by a finished one and
assert the wait returns the finished validation; assert separately that it gives up at the
deadline and that it stops when cancelled.

**Acceptance Scenarios**:

1. **Given** a validation that is still in progress, **When** the developer waits for
   completion, **Then** the library keeps re-reading until the validation reaches a final
   state and returns it.
2. **Given** a validation that never reaches a final state, **When** the configured time
   budget is exhausted, **Then** the wait fails with a clear timeout failure naming the budget
   that elapsed.
3. **Given** a wait in progress, **When** the caller cancels it, **Then** the wait stops
   promptly with a cancellation failure and issues no further reads.
4. **Given** a wait in progress, **When** consecutive checks are needed, **Then** the pause
   between checks grows up to a configured ceiling instead of staying fixed.
5. **Given** a validation that is already in a final state, **When** the developer waits for
   completion, **Then** it returns after the first read without pausing.

---

### User Story 4 - Adopt the library with confidence (Priority: P3)

A developer evaluating the library can read what it exposes, copy a runnable example, and
build against it in a typed editor. Only the intended surface is importable; internal helpers
are not reachable.

**Why this priority**: Adoption and reviewability matter, but they add no runtime capability;
they are delivered once the behaviour above exists.

**Independent Test**: Build the package and check the published entrypoint exposes exactly the
documented surface, the type declarations are emitted, and the runnable example executes
against a running API.

**Acceptance Scenarios**:

1. **Given** the built package, **When** a consumer imports it, **Then** the documented
   factory, types and error type are available and internal helpers are not.
2. **Given** the built package, **When** a consumer imports it from either module system,
   **Then** it loads and its type declarations resolve.
3. **Given** the repository, **When** a developer follows the library's own install/usage
   notes, **Then** they can run the provided example end to end against the local environment.

---

### Edge Cases

- The base address is configured with a trailing separator, or an operation is called with an
  identifier that needs no escaping — the target address must still be well-formed.
- The API answers with a success status but an empty body — the operation must not fail on
  parsing nothing.
- A rejection body is not valid machine-readable problem output — the typed error still
  carries the status and a fallback message.
- The document bytes are supplied in different shapes a JavaScript caller may hold them in
  (binary buffer, blob, string) — the upload must accept them without the caller converting
  first.
- The direct upload to storage is rejected — the confirm must not run, so the request is left
  in its pending-upload state rather than being queued for a document that was never stored.
- The wait is given a time budget smaller than one pause — it must not sleep past the deadline
  before failing.
- The wait is handed an already-cancelled cancellation token — it must stop immediately.
- The validation ends in the non-conclusive failure state rather than the completed state —
  the wait must return it as a final state, not keep polling until timeout.
- A validation completes with a negative verdict — that is a successful call returning a
  result, not a failure of the operation.

## Requirements _(mandatory)_

### Functional Requirements

- **FR-001**: The library MUST expose a single factory that takes the API's base address and
  the API credential and returns a ready-to-use client of plain functions.
- **FR-002**: The client MUST expose exactly four operations — start a validation, upload the
  document bytes, read a validation, wait for completion — and nothing beyond them. The
  confirm step required by the API MUST be performed inside the upload operation, which is the
  only point at which the library knows the bytes have been sent; no separate confirm operation
  is exposed.
- **FR-003**: Starting a validation MUST send the filename and content type, MUST accept an
  optional idempotency key as a separate argument (leaving the input type a mirror of the
  request body) and forward it in the documented header, and MUST return the request
  identifier, the status and the upload destination.
- **FR-004**: Uploading MUST take the object returned by the start operation (carrying both
  the request identifier and the upload destination) plus the raw document bytes and an
  optional content type, MUST send the bytes to that upload destination, and MUST NOT attach
  the API credential to that request.
- **FR-005**: After the bytes are accepted by storage, the upload operation MUST reach the
  API's confirm operation for that request identifier and MUST return the resulting status to
  the caller; if the direct upload is rejected, the confirm MUST NOT be attempted. Repeating
  the upload operation MUST stay safe, matching the API's documented replay behaviour.
- **FR-006**: Reading MUST return the current status and, when present, the result with its
  verdict, extracted fields and reason.
- **FR-007**: Every call to the API MUST carry the configured API credential in the documented
  authentication header. The client options MUST be the base address, the required API
  credential, and an optional map of extra headers merged into every API call.
- **FR-008**: Every rejection from the API MUST be raised as the library's own typed error
  carrying the HTTP status and the parsed problem body, including the field-level details the
  API attaches to input-validation rejections; a raw transport rejection or an untyped generic
  failure MUST NOT reach the caller.
- **FR-009**: When a rejection body is absent or unparsable, the typed error MUST still carry
  the status and a meaningful fallback message.
- **FR-010**: Waiting for completion MUST repeatedly read the validation until it reaches a
  final state, MUST increase the pause between reads up to a ceiling, MUST stop with a clear
  timeout failure when the time budget is exhausted, and MUST support external cancellation.
- **FR-011**: The time budget, the first pause, the pause ceiling and the cancellation token
  for the wait MUST all be caller-configurable, with sane defaults so the common case needs no
  configuration.
- **FR-012**: The public entrypoint MUST re-export only the intended surface; internal helpers
  MUST NOT be reachable from it.
- **FR-013**: The library's types MUST mirror the API's actual response shapes, including the
  status vocabulary and the verdict vocabulary, so consumers get compile-time safety without a
  second, invented contract.
- **FR-014**: Every operation, every documented failure class and the wait's timeout,
  backoff and cancellation behaviour MUST be covered by automated tests that exercise the
  library against a stubbed transport, with no running API required.
- **FR-015**: The library MUST ship a runnable usage example covering the full lifecycle.
- **FR-016**: The library's own install/usage notes and the repository's run/test instructions
  MUST be updated in the same change so a reader can build, test and use it.
- **FR-017**: The change MUST NOT alter the API's HTTP contract, the status machine, or any
  business rule.
- **FR-018**: The SDK architecture document's public-surface section MUST be updated in the
  same change to record the final operation names and signatures, which it explicitly leaves
  open to refinement.
- **FR-019**: The SDK code-rules examples that the clarified surface supersedes MUST be
  amended in the same change — as an explicit governance amendment, not a silent edit — so no
  documented example contradicts the shipped library. Only the superseded examples change; the
  surrounding rules are not rewritten.

### Key Entities

- **Client options**: how to reach the API — its base address, the required API credential,
  and optional extra headers.
- **Start-validation input**: the filename and content type of the document to be validated.
- **Start-validation call options**: the optional idempotency key for the create call.
- **Start-validation response**: the request identifier, the initial status, and the upload
  destination for the bytes.
- **Validation view**: the request identifier, the current status, and the result when one
  exists.
- **Validation result**: the verdict, the extracted fields, and the reason.
- **Wait options**: the time budget, first pause, pause ceiling and cancellation token for a
  wait.
- **API error**: the HTTP status plus the machine-readable problem body, including field-level
  details when supplied.

## Success Criteria _(mandatory)_

### Measurable Outcomes

- **SC-001**: A developer can drive a document from creation to a verdict using only the
  library's documented operations, writing no transport code of their own.
- **SC-002**: 100% of API rejections reaching a consumer arrive as the library's typed error
  with the status and reason available, and 0% arrive as an untyped or transport-level failure.
- **SC-003**: The full public surface — every operation, every documented failure class, and
  the wait's timeout, backoff and cancellation behaviour — is covered by automated tests that
  run with no infrastructure and no running API.
- **SC-004**: The published package loads from both module systems and resolves its type
  declarations, with the intended surface importable and internal helpers not.
- **SC-005**: A developer following the library's own notes can install, build, test and run
  the example without consulting the source.
- **SC-006**: A wait against a validation that never finishes gives up within its configured
  time budget rather than hanging.

## Assumptions

- The backend's HTTP contract is already implemented and stable as documented; this feature
  only consumes it and changes nothing on the service side.
- The API credential is the static header-based stub already documented; no other
  authentication scheme is in scope.
- Consumers run on a JavaScript runtime where the platform's native HTTP capability is
  available globally, as already required by the repository's stated prerequisites.
- The upload destination is a pre-authorised, time-limited address produced by the API at
  creation time; the library treats it as opaque and never derives one itself.
- Idempotency semantics (scope, replay behaviour) are the API's responsibility; the library
  only forwards the caller's key.
- No new runtime dependency is introduced; the existing tooling for building and testing the
  library is used as-is.
