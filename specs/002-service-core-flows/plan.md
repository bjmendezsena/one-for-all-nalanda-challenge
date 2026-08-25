# Implementation Plan: Service core flows (validation lifecycle end-to-end)

**Branch**: `002-service-core-flows` | **Date**: 2026-08-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-service-core-flows/spec.md`

## Summary

Implement the whole documented backend slice inside the existing `service/` skeleton: the three
REST endpoints (create / confirm / read), the domain model and its status machine, the four
ports and their adapters (JPA + PostgreSQL, Spring Kafka producer **and** consumer, S3 SDK
against MinIO), the Liquibase schema, the API-key filter, the central Problem Details mapping,
and the full test pyramid. The approach adds nothing beyond what `docs/` already specifies —
no new endpoint, field, status, event, dependency, or infrastructure. Everything the docs left
open is settled in [research.md](./research.md); the two places where two docs disagreed
(R-005, R-006) were resolved by the human and one of them amends
`docs/service/code_rules.md` § 6 in the same change.

## Technical Context

**Language/Version**: Java 21 (Gradle toolchain, already pinned)

**Primary Dependencies**: Spring Boot 3.5.6 (`web`, `validation`, `data-jpa`), Spring Kafka,
Liquibase, AWS SDK v2 `s3` (includes `S3Presigner`), PostgreSQL driver. **No dependency is
added by this feature** — see research.md R-012.

**Storage**: PostgreSQL (single table, schema via Liquibase) + MinIO/S3 for document bytes

**Testing**: JUnit 5 + AssertJ; hand-written fakes for `application` tests; Mockito only for
the messaging and storage adapters; `@WebMvcTest` for controllers; `@DataJpaTest` on H2 in
PostgreSQL mode **with the real Liquibase changelog applied**. Testcontainers is not introduced.

**Target Platform**: JVM service run on the host (`./gradlew bootRun`), talking to the three
containers in `docker/`. The service itself is not containerized.

**Project Type**: Backend web service (one of the monorepo's two artifacts). `sdk/` is untouched.

**Performance Goals**: None beyond the documented behavior — confirm returns without waiting
for processing (FR-010). Development-scale volumes; a single local Kafka partition.

**Constraints**: Hexagonal dependency rule (`domain ← application ← adapter`); domain layer
free of Spring/JPA/Kafka/AWS types; every error a Problem Details body; the whole test suite
green with no container running (FR-034).

**Scale/Scope**: One aggregate (`ValidationRequest`), one table, three endpoints, one event,
four ports.

## Constitution Check

_GATE: evaluated before Phase 0 and re-checked after Phase 1 design. Both passes: **PASS**._

| Principle | How this plan satisfies it | Verdict |
|---|---|---|
| **I. Documentation Is the Source of Truth** | Every behavior traces to `docs/business-rules.md`, `docs/service/upload-flow.md`, `events.md`, `kafka.md`. The two doc-vs-doc conflicts were escalated, not silently resolved (R-005, R-006); the resulting amendment to `docs/service/code_rules.md` § 6 ships in the same change, as does the `README.md` API section update. | PASS |
| **II. Spec-Driven Development** | Scope is exactly the spec's 34 functional requirements. No endpoint, field, status or event is invented. Ambiguities were asked, not guessed. | PASS |
| **III. Code Rules Are Non-Negotiable** | The layer-by-layer design below is written directly from `docs/service/code_rules.md` §§ 1–8, including its § 9 restrictions (no framework types in `domain`, plain `RuntimeException` domain exceptions, entities only in `adapter/out/persistence`, no anemic model, no direct Kafka/JPA/S3 from `application`, confirm never touches storage, fakes not Mockito in `application` tests, no Testcontainers, `should_…_when_…()` names). The single amendment to that document (R-005) was approved by the human before planning. | PASS |
| **IV. Architecture & Boundaries** | Hexagonal across all three integrations, layer-first packages, single Gradle module, one class per use case. No new layer or module. | PASS |
| **V. Explicit Contracts & Errors** | One-directional status machine enforced inside the entity; `FAILED` never conflated with `verdict: FAIL`; `jakarta.validation` at the boundary rejecting before any state exists; every error a Problem Details body with a specific reason; thresholds as named constants. | PASS |
| **VI. Asynchronous Processing Integrity** | Confirm publishes and returns; the consumer finishes the work and writes the final state. Exactly one event. Messages keyed by `validationRequestId`. Duplicate delivery absorbed by `startProcessing()`'s guard. Idempotency-Key semantics unchanged (no TTL, conflicting body ignored). | PASS |
| **VII. Quality & Testing** | Full pyramid, fakes in `application`, Mockito confined to the messaging/storage adapters, H2 for persistence, no Testcontainers, every business rule covered by a test that fails if the rule breaks (FR-033), suite green with nothing running (FR-034). | PASS |
| **VIII. Security & Configuration** | The documented `X-Api-Key` stub only — Spring Security is not introduced. Bytes never proxied; confirm does no storage I/O. No secrets committed; local defaults only. Correlation id in the MDC; request bodies never logged (R-010, R-011). | PASS |
| **IX. Approved Stack & Operations** | No new dependency, library, or infrastructure (R-012). `docker/` untouched. Schema exclusively through Liquibase changesets. Out-of-scope items stay out. | PASS |

**Complexity Tracking**: not applicable — no violation to justify.

## Project Structure

### Documentation (this feature)

```text
specs/002-service-core-flows/
├── plan.md              # this file
├── spec.md              # what and why
├── research.md          # Phase 0 — the 12 open points, decided
├── data-model.md        # Phase 1 — domain model, table, event, records
├── quickstart.md        # Phase 1 — run it, test it, walk the flow
├── contracts/
│   └── http-api.md      # Phase 1 — the wire contract (unchanged for the SDK)
└── checklists/
    └── requirements.md
```

### Source Code (repository root)

Only `service/` is touched. `sdk/`, `docker/` and the root tooling are untouched.

```text
service/src/
├── main/java/com/nalanda/validation/
│   ├── domain/
│   │   ├── model/
│   │   │   ├── ValidationRequest.java              # entity + status machine
│   │   │   ├── ValidationStatus.java               # enum
│   │   │   ├── DocumentMetadata.java               # record
│   │   │   ├── ValidationResult.java               # record + Verdict enum
│   │   │   ├── PresignedUpload.java                # record
│   │   │   ├── ValidationRequestNotFoundException.java
│   │   │   ├── InvalidStatusTransitionException.java
│   │   │   └── DocumentStorageException.java
│   │   └── port/
│   │       ├── ValidationRequestRepository.java    # save / save+key / findById / findByIdempotencyKey
│   │       ├── JobPublisher.java
│   │       ├── JobConsumer.java
│   │       └── DocumentStoragePort.java
│   ├── application/
│   │   ├── CreateValidationUseCase.java            # + CreateValidationCommand / CreateValidationResult
│   │   ├── ConfirmUploadUseCase.java               # + ConfirmUploadResult
│   │   ├── GetValidationUseCase.java
│   │   └── ProcessValidationUseCase.java           # the deterministic rule + its constants
│   ├── adapter/
│   │   ├── in/web/
│   │   │   ├── ValidationController.java
│   │   │   ├── ApiExceptionHandler.java            # the only error translation point
│   │   │   ├── ApiKeyFilter.java                   # X-Api-Key stub -> 401 Problem Details
│   │   │   └── CorrelationIdFilter.java            # MDC correlation id
│   │   └── out/
│   │       ├── persistence/
│   │       │   ├── ValidationRequestEntity.java
│   │       │   ├── SpringDataValidationRequestRepository.java
│   │       │   ├── JpaValidationRequestRepository.java
│   │       │   └── ValidationRequestMapper.java
│   │       ├── messaging/
│   │       │   ├── ProcessingRequestedEvent.java
│   │       │   ├── KafkaJobPublisher.java
│   │       │   └── KafkaJobConsumer.java           # @KafkaListener -> ProcessValidationUseCase
│   │       └── storage/
│   │           ├── S3DocumentStorageAdapter.java
│   │           └── StorageProperties.java
│   └── config/
│       ├── S3Config.java                           # S3Client + S3Presigner beans (MinIO endpoint)
│       ├── KafkaConfig.java                        # topic constant, JSON ser/deser wiring
│       └── WebFilterConfig.java                    # registers the two filters over /api/v1/**
├── main/resources/
│   ├── application.yml                             # + security.api-key, storage.presign-ttl
│   └── db/changelog/
│       ├── db.changelog-master.yaml                # includes the change below
│       └── changes/001-create-validation-request.yaml
└── test/java/com/nalanda/validation/               # mirrors the tree above; see § Test plan
```

**Structure Decision**: the layout already scaffolded by feature `001` is filled in as-is —
layer-first packages inside one Gradle module, exactly as `docs/service/architecture.md` § 4.2
defines. No package outside that tree is created.

## Implementation approach, layer by layer

### 1. Domain (`domain/model`, `domain/port`)

Pure Java, zero framework imports — with the one documented exception that
`ValidationRequest`'s getters carry Jackson annotations for the web boundary
(`docs/service/code_rules.md` § 6), and `document` is `@JsonIgnore`d so the read response
matches the contract. `ValidationRequest` owns the status machine: `confirmUpload()` is a
silent no-op outside `PENDING_UPLOAD`; `startProcessing()`, `complete()` and `fail()` throw
`InvalidStatusTransitionException` from the wrong state. State never changes from outside.

Ports are the four interfaces in `data-model.md` § 1; `findById` throws rather than returning
`Optional`, `findByIdempotencyKey` returns `Optional` because "not seen yet" is a real business
outcome. `save` has both overloads (R-007).

### 2. Application (`application`)

One class per use case, each depending only on the ports it needs.

- **`CreateValidationUseCase`** — on an idempotency-key hit, re-signs an upload URL over the
  stored key and returns the original request (R-006). Otherwise: generate the storage key,
  build `DocumentMetadata` with size `0`, create the request, sign the URL, persist with the key.
- **`ConfirmUploadUseCase`** — `findById`, `confirmUpload()`, `save`, and publish **only if**
  the status is now `QUEUED`. Never touches `DocumentStoragePort` (§ 9 restriction).
- **`GetValidationUseCase`** — `findById`, return. No mutation.
- **`ProcessValidationUseCase`** — `findById`; `startProcessing()` inside a try/catch that
  swallows `InvalidStatusTransitionException` as a no-op (duplicate delivery); `sizeOf` then
  `recordDiscoveredSize(...)` so the size is persisted, not just used;
  evaluate the rule against the named constants; `complete(result)`. Any unexpected failure
  (including a storage outage, but **not** a missing object — R-009) drives `fail()` and a
  `FAILED` status.

### 3. Adapters

- **Persistence** — `ValidationRequestEntity` (the only `@Entity`), a Spring Data interface, a
  `JpaValidationRequestRepository` implementing the port, and a hand-written mapper that also
  (de)serializes `result_fields` as JSON text.
- **Messaging** — `KafkaJobPublisher` sends `ProcessingRequestedEvent` keyed by the request id;
  `KafkaJobConsumer` is the `@KafkaListener` implementing `JobConsumer` and delegating straight
  to `ProcessValidationUseCase`. No business logic lives in the adapter.
- **Storage** — `S3DocumentStorageAdapter` uses `S3Presigner` for the `PUT` URL and
  `S3Client.headObject` for the size; a missing object becomes `0`, any other failure becomes
  `DocumentStorageException`.

### 4. Config and cross-cutting

`S3Config` (path-style access against the MinIO endpoint), `KafkaConfig` (topic constant, JSON
ser/deser and the trusted-packages setting already in `application.yml`), and `WebFilterConfig`
registering `ApiKeyFilter` (`/api/v1/**`, `401` as Problem Details) and `CorrelationIdFilter`
(MDC). Both filters live in `adapter/in/web` — they are web-boundary concerns, and
`docs/service/architecture.md` § 4.4 sanctions that placement — so their tests land in the
`adapter/in/web` test package the architecture document already defines, and no new test
package is invented. `ApiExceptionHandler`
is the single `@RestControllerAdvice`, with one handler per domain exception plus the
validation and fallback handlers.

### 5. Schema

`changes/001-create-validation-request.yaml` creates the single table and the unique index from
`data-model.md` § 2.1, using only portable Liquibase types, with an explicit rollback. The
master changelog includes it. `application-test.yml` switches to `ddl-auto: validate` with
Liquibase enabled so the changelog is what builds the test schema too (R-003).

## Test plan

Mirrors the source tree; every test name is `should_<expectedBehavior>_when_<condition>()`.

| Layer | Tooling | What it proves |
|---|---|---|
| `domain/ValidationRequestTest` | JUnit only | every legal transition, every illegal one throwing, confirm-as-no-op, `FAILED` ≠ `verdict: FAIL` |
| `application/*UseCaseTest` | JUnit + `InMemoryValidationRequestRepository`, `RecordingJobPublisher`, `FakeDocumentStoragePort` — **no Mockito** | create (fresh + idempotent replay), confirm (first + repeat, publish exactly once), get, and the full verdict matrix: `PASS`, `unsupported content type`, `empty file`, `file too large`, duplicate delivery no-op, storage outage → `FAILED` |
| `adapter/in/web/ValidationControllerTest` | `@WebMvcTest`, use cases mocked | status codes and JSON shapes for all three endpoints, `400` with `errors[]`, `404`, `409`, `401` without the API key |
| `adapter/out/persistence/JpaValidationRequestRepositoryTest` | `@DataJpaTest` on H2 + **the real changelog** | round-trip of every field including `result_fields` JSON, `findById` throwing when absent, `findByIdempotencyKey`, unique-key enforcement |
| `adapter/out/messaging/*Test` | JUnit + Mockito | the publisher sends the right topic/key/payload; the consumer delegates the id to the use case |
| `adapter/out/storage/S3DocumentStorageAdapterTest` | JUnit + Mockito | presign delegation; `sizeOf` returning the reported size, `0` for a missing object, and throwing on any other failure |

## Documentation updated in the same change

Per constitution I, shipped with the code, additively:

- `docs/service/code_rules.md` § 1 — the document metadata field is not `final`, and the entity exposes `recordDiscoveredSize(long)` (approved amendment, R-013).
- `docs/service/code_rules.md` § 6 — the confirm snippet returns `ConfirmUploadResult` (approved amendment, R-005).
- `docs/service/code_rules.md` § 2 — the repository port shows both `save` overloads (R-007).
- `docs/business-rules.md` § 6 — the idempotent-replay row states that a fresh upload URL is returned (R-006).
- `README.md` — the API section documents the three endpoints, the API-key header and the idempotency rules; `§ How to run` gains the service run/test commands.

## Risks

| Risk | Mitigation |
|---|---|
| H2 rejects a changeset PostgreSQL accepts | only portable Liquibase types are used, and the `@DataJpaTest` suite runs the changelog on every build, so a divergence fails immediately |
| Entity and changelog drift apart | `ddl-auto: validate` in both profiles turns any mismatch into a startup failure |
| Two concurrent confirms publish twice | accepted (R-004): the consumer's status guard still keeps processing exactly-once; only a duplicate message is possible |
| The deterministic rule leaks into the adapter | the rule and its constants live only in `ProcessValidationUseCase`; the consumer adapter just forwards an id |
