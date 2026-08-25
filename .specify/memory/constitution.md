<!--
Sync Impact Report
==================
Version change: 1.1.0 → 1.2.0
Rationale: the repository now delivers a third artifact, `example/`, a non-publishable demonstration
  that consumes the SDK the way an external consumer would. Principles III, IV and VII were amended
  to say which rules govern it, and the Documentation Index gained its README. The same amendment
  corrected the `request()` snippet in `docs/sdk/code_rules.md` § 2, which embedded a defect: it
  parsed every non-`204` success as JSON, so a presigned storage `PUT` (`200` with an empty body)
  made `upload` throw before `confirm` ran. Requested by the human in feature
  004-example-sdk-integration; `CLAUDE.md`, `README.md` and `docs/sdk/code_rules.md` were updated in
  the same change.
Previous: 1.0.0 → 1.1.0, principle IX amended so that every Docker asset lives under `docker/` (and
  the backend is explicitly not containerized), replacing the previous "one `docker-compose.yml` at
  the repo root".
Previous: none → 1.0.0 (initial ratification), first constitution for this repository, created when
  spec-kit was integrated.
Principles defined: I Documentation Is the Source of Truth · II Spec-Driven Development ·
  III Code Rules Are Non-Negotiable · IV Architecture & Boundaries · V Explicit Contracts &
  Errors · VI Asynchronous Processing Integrity · VII Quality & Testing · VIII Security &
  Configuration · IX Approved Stack & Operations
Added sections: Additional Constraints, Documentation Index, Development Workflow, Governance
Removed sections: none
Templates status:
  - .specify/templates/plan-template.md ✅ aligned (structure section rewritten for service/ + sdk/;
    example/ is expanded per-feature by /speckit-plan, as 004 did)
  - .specify/templates/spec-template.md ✅ aligned (generic, no changes needed)
  - .specify/templates/tasks-template.md ✅ aligned (tests + path conventions rewritten)
  - .specify/templates/constitution-template.md ✅ aligned (unchanged upstream template)
Follow-up TODOs: none
-->

# One For All — Nalanda Challenge Constitution

This repository delivers three artifacts from one monorepo: `service/` (Java/Spring Boot
backend implementing the asynchronous document-validation flow), `sdk/` (TypeScript
client library wrapping its HTTP API), and `example/` (a small, non-publishable project that
consumes `sdk/` exactly as an external consumer would — declared as a dependency in its own
manifest and imported by package name — and asserts every documented behavior against a running
backend). `example/` exists to prove the SDK's packaging and published types, which nothing inside
`sdk/` can prove about itself. This constitution governs **how** the project is
built; it does **not** duplicate the documentation. Substance lives in `docs/` (the what
and the how) and in `README.md § Design trade-offs` (the why, with the discarded
alternatives). If this document and those ever conflict, **they win** and this document
MUST be amended.

## Core Principles

### I. Documentation Is the Source of Truth

`docs/` is the authoritative specification of the domain, architecture, and conventions;
`README.md § Design trade-offs` is the authoritative record of *why* each decision was
made and which alternative was rejected. Agents and developers MUST read the relevant
doc before implementing, and MUST update the affected doc in the same change when
behavior, contracts, or structure change — code and docs never drift. A decision already
recorded in `README.md § Design trade-offs` is settled: deviating from it requires an
explicit question to the human, never a silent substitution "because it is more
idiomatic". Existing doc entries are not rewritten to "clean them up" — edits are
additive or explicitly requested. All documentation is written in English.
References: `README.md`, `docs/business-rules.md`, `docs/service/architecture.md`,
`docs/sdk/architecture.md`.

### II. Spec-Driven Development

The spec is the source of truth for scope; the plan guides implementation. Work flows
through spec-kit: specify → clarify → plan → tasks → analyze → checklist → implement.
The agent MUST NOT invent scope, endpoints, fields, statuses, or data: implement the
smallest change that satisfies the spec, one concern per change, and when ambiguous or
blocked, ask instead of guessing. Debugging MUST reproduce with a failing test first and
fix root causes — production code, invariants, guards, and validations are never weakened
to make a test pass.

### III. Code Rules Are Non-Negotiable

**The code rules in `docs/` are MANDATORY and strictly binding.** The two rule files —
`docs/service/code_rules.md` (backend) and `docs/sdk/code_rules.md` (SDK) — constitute the
complete, non-negotiable restriction set for producing code in this repository. Every
change to `service/` MUST comply with the service code rules, and every change to `sdk/`
with the SDK code rules, restrictively and without selective application. `example/` has no rule
file of its own: its TypeScript MUST comply with `docs/sdk/code_rules.md`, and it MUST reach the SDK
only through the package's public entrypoint — never `sdk/src/internal/**`, and never a relative
path into `sdk/dist`. This explicitly
includes each file's § "Restrictions for AI coding assistants", which are **hard
restrictions, not suggestions**. Compliance is a **blocking acceptance criterion**: any
violation MUST be rejected in review, and no change ships until it complies. Notably and
without limitation:

- `domain/model` and `domain/port` never import Spring, JPA, Kafka, or the AWS SDK; domain
  exceptions extend plain `RuntimeException`; JPA entities live in `adapter/out/persistence`.
- No anemic domain model: `ValidationRequest` changes state only through its own transition
  methods; no public setters.
- Kafka, JPA, and S3 access happens only through ports, never directly from `application`
  or `domain`.
- Every error path produces a `ProblemDetail` from the central `@RestControllerAdvice`;
  no ad-hoc per-controller error shape.
- The SDK stays a factory function over native `fetch` with `ValidationApiError` and
  `AbortSignal`-aware exponential backoff; no HTTP client, no retry library, no `class`
  client, `"strict": true` never relaxed.
- Naming conventions (boolean `is`/`has`/`can`/`should` predicates, verb-phrase methods,
  `should_<expectedBehavior>_when_<condition>()` test names, named constants for business
  thresholds) are part of the rules, not style preferences.

Amending these rule files is a governance change and follows the amendment process below.
References: `docs/service/code_rules.md`, `docs/sdk/code_rules.md`.

### IV. Architecture & Boundaries

The service is hexagonal (ports & adapters), applied consistently to **all three** external
integrations — persistence, messaging, and storage — with layer-first package organization
(`domain` / `application` / `adapter` / `config`) inside a single Gradle module. The
dependency rule `domain ← application ← adapter` MUST hold and the domain layer stays pure
(no framework types, no annotations). The application layer is one class per use case. The
SDK mirrors this discipline at its own scale: a small, intentional public surface exported
only from `src/index.ts`, with internals under `src/internal/` never re-exported. `example/` is a
flat consumer with no layers of its own: it depends on `sdk/` and on nothing else in the repository,
and neither `service/` nor `sdk/` ever depends on it. New
layers, modules, or packages that the architecture docs do not define MUST NOT be invented.
References: `docs/service/architecture.md`, `docs/sdk/architecture.md`.

### V. Explicit Contracts & Errors

The status machine (`PENDING_UPLOAD → QUEUED → PROCESSING → COMPLETED | FAILED`) is
one-directional and authoritative; status `FAILED` (the system could not complete the check)
is never conflated with `verdict: "FAIL"` (the check ran and the document did not pass).
Inputs are validated with `jakarta.validation` at the boundary and rejected synchronously
before any state is created. Every error response is an RFC 7807 Problem Details body with
a **specific, actionable reason** — a generic or opaque failure is not acceptable, and a raw
stack trace never reaches a client. The SDK mirrors that contract: every API error surfaces
as `ValidationApiError` carrying the HTTP status and the Problem Details body, never a raw
`fetch` rejection. Business thresholds (supported content type, size range) live as named
constants next to the rule, never as inline literals.
References: `docs/business-rules.md`, `docs/service/upload-flow.md`,
`docs/service/code_rules.md` (§ 5, § 6), `docs/sdk/code_rules.md` (§ 3).

### VI. Asynchronous Processing Integrity

"Accept work" and "finish work" stay separated: the HTTP layer returns as soon as
`ProcessingRequested` is published and never waits for processing; the Kafka consumer
completes the work later and writes the final state. There is exactly one event in the
system — adding another requires a documented consumer and a doc update in the same change.
Messages are keyed by `validationRequestId` so a request's messages stay ordered relative to
each other, and consumers are idempotent: replaying `confirm` or re-delivering a message
MUST NOT re-trigger processing or corrupt state — state-machine guards, not luck, enforce
this. `Idempotency-Key` semantics on create (no TTL; a conflicting body returns the original
resource) are fixed by the docs and MUST NOT be reinterpreted.
References: `docs/service/events.md`, `docs/service/kafka.md`, `docs/business-rules.md` (§ 6).

### VII. Quality & Testing

Tests are completion criteria, not a separate phase: written during implementation,
behavior- and contract-focused, deterministic, and green before a change is done. The
service is covered by the full pyramid — domain and use-case tests against **hand-written
fakes** (`InMemory…`, `Recording…`, no Mockito at that layer), Mockito reserved for the
messaging and storage adapters, and integration tests on **H2 in-memory** plus fakes/mocks
for Kafka/MinIO. **Testcontainers is NOT introduced.** The SDK is tested with Vitest against
a directly mocked `fetch` — MSW, `nock`, and similar are not introduced. A stubbed response MUST
reproduce the shape the real counterpart returns; a stub that is more convenient than the real
response hides defects rather than covering them. Test names follow
`should_<expectedBehavior>_when_<condition>()`. `example/` introduces no test framework: it is
itself an assertion-backed acceptance harness, each scenario declaring its expected outcome and the
process exiting non-zero when any scenario fails. Every business rule in
`docs/business-rules.md` (status transitions, verdict rule, idempotency, error reasons) MUST
have a test that would fail if the rule were broken.
References: `docs/service/code_rules.md` (§ 7), `docs/sdk/code_rules.md` (§ 6),
`README.md § Design trade-offs → Integration testing strategy`.

### VIII. Security & Configuration

Authentication is the documented static API-key stub (`X-Api-Key`) — it is a stub by
decision, not an omission, and MUST NOT be silently expanded into a different auth scheme.
Document bytes are never proxied through the service: clients upload directly to object
storage with a presigned URL, and `confirm` performs no storage I/O. No secrets in code,
logs, or URLs beyond the presigned URL's own signed parameters; no credentials committed —
local values live in compose/env files with safe defaults. Requests are correlated in
structured logs; the request body of a failed validation is never dumped verbatim into logs.
References: `docs/service/upload-flow.md`, `docs/service/architecture.md` (§ 3),
`README.md § Design trade-offs → Authentication`.

### IX. Approved Stack & Operations

Build only within the approved stack recorded in `docs/` and `README.md § Design
trade-offs`: Java/Spring Boot with JPA/Hibernate + Liquibase, Spring Kafka, the AWS S3 SDK
against MinIO, and a dependency-free TypeScript SDK built with Vite in library mode (pure
ESM source; dual output only at the build boundary). **Any new dependency, library, or piece
of infrastructure is raised as a question to the human before being added** — never
introduced silently because it is "commonly used". Every Docker asset lives under `docker/` and
nowhere else; `docker/docker-compose.yml` brings up every local dependency (`postgres`, `kafka`,
`minio`) with a single `docker compose up`, and the backend itself is not containerized. Schema
changes go through Liquibase changesets, never manual DDL. The
items listed as out of scope in `docs/service/architecture.md` § 6 (real AWS/MSK/ECS/
Terraform, a real viewer, full auth, multi-tenancy, UI) stay out of scope.
References: `docs/service/architecture.md`, `docs/sdk/architecture.md`, `README.md`.

## Additional Constraints

Engineering practices (**SOLID, DRY, KISS, YAGNI**, and avoiding the **STUPID**
anti-patterns) and **English-only naming** (variables, methods, classes, files, packages,
DB objects, events, comments) are **mandatory and blocking**, per the naming sections of
both code-rules files.

**Comments policy:** no comments by default. A comment is allowed only when strictly
necessary — genuinely ambiguous code, a constraint the code cannot express, or pending work
marked explicitly as `TODO:` followed by its description. Restating comments, section
banners, and commented-out code are forbidden.

**Scope discipline:** this repository implements one deliberately narrow slice. Features,
endpoints, statuses, events, or configuration knobs that the assessment brief
(`fullstack-engineer-technical-assessment.pdf`) and the specs do not ask for MUST NOT be
added — extra surface is a defect, not a bonus.

## Documentation Index

`docs/` is the single source of truth for what the system does and how it is built;
`README.md § Design trade-offs` is the single source of truth for why. When a task touches
an area, its referenced doc governs.

| Area                                                          | Document(s)                                                    |
| ------------------------------------------------------------- | -------------------------------------------------------------- |
| Assessment brief (origin of the scope)                        | `fullstack-engineer-technical-assessment.pdf`                  |
| Project overview, repo layout, how to run, decision rationale | `README.md` (§ Design trade-offs)                              |
| Domain & business rules (shared by service and SDK)           | `docs/business-rules.md`                                       |
| Service architecture, layers, package structure               | `docs/service/architecture.md`                                 |
| **Service code rules (non-negotiable)**                       | `docs/service/code_rules.md`                                   |
| Event catalog                                                 | `docs/service/events.md`                                       |
| Kafka topic, group, serialization                             | `docs/service/kafka.md`                                        |
| Upload / presigned-URL flow                                   | `docs/service/upload-flow.md`                                  |
| SDK architecture and public surface                           | `docs/sdk/architecture.md`                                     |
| **SDK code rules (non-negotiable)**                           | `docs/sdk/code_rules.md`                                       |
| Example project (SDK integration harness)                     | `example/README.md`                                            |
| Local infrastructure                                          | `docker/` — `docker-compose.yml`, `.env`, `README.md` (documented in `docs/service/architecture.md` § 3) |
| Governance                                                    | `.specify/memory/constitution.md` (this file)                  |

Any new document MUST be added to this index and cross-referenced from `README.md` in the
same change.

## Development Workflow

Work flows through spec-kit: specify → clarify → plan → tasks → analyze → checklist →
implement, orchestrated end-to-end by the `/feature` command. The Constitution Check gate in
`plan-template.md` MUST be evaluated against this document before Phase 0 research and
re-checked after design. Before a change is "done", self-verification is mandatory: build +
tests green (`service/`, `sdk/` and `example/` as applicable), the diff re-read, the code rules of the
touched artifact re-checked, and the affected docs updated in the same change.

## Governance

- This constitution governs process; `docs/` and `README.md § Design trade-offs` govern
  substance. Amendments MUST update the affected files in the same change and keep the
  spec-kit templates (`.specify/templates/*.md`) in sync.
- Changes to `docs/service/code_rules.md` or `docs/sdk/code_rules.md` are governance changes:
  they require the amendment process, not an inline edit during implementation.
- Versioning is semantic: MAJOR for incompatible governance changes or principle
  removals/redefinitions, MINOR for new or materially expanded principles, PATCH for
  clarifications.
- All reviews MUST verify compliance with the principles above; violations require a
  justification entry in the plan's Complexity Tracking table or MUST be rejected.

**Version**: 1.2.0 | **Ratified**: 2026-08-25 | **Last Amended**: 2026-08-25
