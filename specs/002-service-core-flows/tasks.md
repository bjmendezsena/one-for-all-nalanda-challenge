---
description: 'Task list for 002-service-core-flows'
---

# Tasks: Service core flows (validation lifecycle end-to-end)

**Input**: Design documents from `/specs/002-service-core-flows/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/http-api.md

**Tests**: Tests are completion criteria, not a separate phase (Constitution VII, FR-033/FR-034). Every implementation task below carries its own tests; a task is not done until its tests are written and green. Test method names follow `should_<expectedBehavior>_when_<condition>()`.

**Organization**: Grouped by user story so each is independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on an incomplete task)
- **[Story]**: US1 create · US2 confirm · US3 process · US4 read
- Exact file paths in every task. Base package: `com.nalanda.validation`.

## Path Conventions

- Service main: `service/src/main/java/com/nalanda/validation/...`
- Service tests: `service/src/test/java/com/nalanda/validation/...`
- Migrations: `service/src/main/resources/db/changelog/...`
- Only `service/` and `docs/`/`README.md` are touched. `sdk/` and `docker/` are not modified.

---

## Phase 1: Setup (configuration only — no new dependencies)

**Purpose**: make configuration match what the feature needs before any code depends on it.

- [X] T001 Add `security.api-key` (default `local-dev-api-key`) and `storage.presign-ttl` (default `PT15M`) to `service/src/main/resources/application.yml`, alongside the existing `storage.*` block (research.md R-008, R-010)
- [X] T002 Switch `service/src/test/resources/application-test.yml` to `jpa.hibernate.ddl-auto: validate` with `liquibase.enabled: true` pointed at the master changelog, keeping H2 in PostgreSQL mode, so the real migrations build the test schema (research.md R-003)
- [X] T003 [P] Delete the `.gitkeep` placeholders under `service/src/main/java/com/nalanda/validation/**` and `service/src/test/java/com/nalanda/validation/**` as each package receives its first real file

**Checkpoint**: `./gradlew test` still green (only the existing context-load test runs).

---

## Phase 2: Foundational (blocking prerequisites for every story)

**Purpose**: the domain model, the ports, the schema and the cross-cutting web plumbing that all four stories build on. **No user story can start before this phase completes.**

### Domain model and exceptions

- [X] T004 [P] Create `ValidationStatus` enum (`PENDING_UPLOAD`, `QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED`) in `service/src/main/java/com/nalanda/validation/domain/model/ValidationStatus.java`
- [X] T005 [P] Create the `DocumentMetadata` record (`filename`, `contentType`, `sizeInBytes`, `storageKey`) in `service/src/main/java/com/nalanda/validation/domain/model/DocumentMetadata.java`, with a `withSizeInBytes(long)` copy method returning a new instance
- [X] T006 [P] Create the `ValidationResult` record (`verdict`, `fields`, `reason`) with the nested `Verdict` enum in `service/src/main/java/com/nalanda/validation/domain/model/ValidationResult.java`
- [X] T007 [P] Create the `PresignedUpload` record (`url`) in `service/src/main/java/com/nalanda/validation/domain/model/PresignedUpload.java`
- [X] T008 [P] Create `ValidationRequestNotFoundException`, `InvalidStatusTransitionException` and `DocumentStorageException` as plain `RuntimeException` subclasses in `service/src/main/java/com/nalanda/validation/domain/model/`
- [X] T009 Create the `ValidationRequest` entity in `service/src/main/java/com/nalanda/validation/domain/model/ValidationRequest.java` — private constructor, `create(...)`, **`public static restore(...)`** (used by the mapper, which lives in another package; performs no transition), `confirmUpload()` (silent no-op outside `PENDING_UPLOAD`), `startProcessing()`, `complete(...)`, `fail()`, `recordDiscoveredSize(long)` (the `document` field is therefore **not** `final` — approved amendment, research.md R-013), no public setters, Jackson annotations only on the web-facing getters with `document` `@JsonIgnore`d. **Tests** in `service/src/test/java/com/nalanda/validation/domain/ValidationRequestTest.java`: every legal transition, every illegal transition throwing `InvalidStatusTransitionException`, repeated `confirmUpload()` as a no-op, `recordDiscoveredSize(...)` replacing only the size, and `fail()` reaching `FAILED` without producing a `ValidationResult`

### Ports

- [X] T010 [P] Create `ValidationRequestRepository` (`save(request)`, `save(request, idempotencyKey)`, `findById` throwing when absent, `findByIdempotencyKey` returning `Optional`) in `service/src/main/java/com/nalanda/validation/domain/port/ValidationRequestRepository.java` (research.md R-007)
- [X] T011 [P] Create `JobPublisher` (`publishProcessingRequested(UUID)`) in `service/src/main/java/com/nalanda/validation/domain/port/JobPublisher.java`
- [X] T012 [P] Create `JobConsumer` (`onProcessingRequested(UUID)`) in `service/src/main/java/com/nalanda/validation/domain/port/JobConsumer.java`
- [X] T013 [P] Create `DocumentStoragePort` (`createPresignedUpload(storageKey, contentType)`, `sizeOf(storageKey)`) in `service/src/main/java/com/nalanda/validation/domain/port/DocumentStoragePort.java`

### Schema and persistence

- [X] T014 Create the Liquibase changeset `service/src/main/resources/db/changelog/changes/001-create-validation-request.yaml` per data-model.md § 2.1 — `createTable validation_request` with portable types only (`uuid`, `varchar`, `bigint`, `clob`), the unique index `ux_validation_request_idempotency_key`, and an explicit rollback
- [X] T015 Include that changeset from `service/src/main/resources/db/changelog/db.changelog-master.yaml` (today an empty `databaseChangeLog: []`)
- [X] T016 Create `ValidationRequestEntity` (the only `@Entity`, table `validation_request`) in `service/src/main/java/com/nalanda/validation/adapter/out/persistence/ValidationRequestEntity.java`, columns exactly matching T014
- [X] T017 Create `SpringDataValidationRequestRepository` (`JpaRepository<ValidationRequestEntity, UUID>` + `findByIdempotencyKey`) in `service/src/main/java/com/nalanda/validation/adapter/out/persistence/SpringDataValidationRequestRepository.java`
- [X] T018 Create `ValidationRequestMapper` (package-private, static `toEntity`/`toDomain`, hand-written field-by-field, serializing `result_fields` as JSON text via Jackson) in `service/src/main/java/com/nalanda/validation/adapter/out/persistence/ValidationRequestMapper.java`
- [X] T019 Create `JpaValidationRequestRepository` implementing the port in `service/src/main/java/com/nalanda/validation/adapter/out/persistence/JpaValidationRequestRepository.java`, throwing `ValidationRequestNotFoundException` from `findById`. **Tests** in `service/src/test/java/com/nalanda/validation/adapter/out/persistence/JpaValidationRequestRepositoryTest.java` (`@DataJpaTest` on H2 with the real changelog): full round-trip including the `result_fields` JSON and a `null` result, `findById` throwing when absent, `findByIdempotencyKey` hit and miss, and the unique index rejecting a duplicate key

### Web plumbing and security

- [X] T020 [P] Create `ApiExceptionHandler` (`@RestControllerAdvice`) in `service/src/main/java/com/nalanda/validation/adapter/in/web/ApiExceptionHandler.java` — one handler per domain exception (`404`, `409`, `502`), `MethodArgumentNotValidException` → `400` with the `errors[]` extension, and a fallback `500` that never exposes internals, all as `ProblemDetail` (contracts/http-api.md § 4)
- [X] T021 [P] Create `ApiKeyFilter` (`OncePerRequestFilter` over `/api/v1/**`, comparing `X-Api-Key` against `security.api-key`, writing `401` as a Problem Details body) in `service/src/main/java/com/nalanda/validation/adapter/in/web/ApiKeyFilter.java`, without introducing Spring Security. **Tests** in `service/src/test/java/com/nalanda/validation/adapter/in/web/ApiKeyFilterTest.java`: missing key, wrong key, correct key (research.md R-014)
- [X] T022 [P] Create `CorrelationIdFilter` in `service/src/main/java/com/nalanda/validation/adapter/in/web/CorrelationIdFilter.java` — MDC correlation id taken from `X-Request-Id` or generated, cleared afterwards, never logging request bodies (research.md R-011). **Tests** in `service/src/test/java/com/nalanda/validation/adapter/in/web/CorrelationIdFilterTest.java`: the client-supplied id is reused, an absent one is generated, and the MDC is empty after the chain completes
- [X] T022b [P] Create `WebFilterConfig` in `service/src/main/java/com/nalanda/validation/config/WebFilterConfig.java` registering both filters over `/api/v1/**` in a defined order (correlation id first)
- [X] T023 [P] Create `StorageProperties` (`@ConfigurationProperties("storage")`) in `service/src/main/java/com/nalanda/validation/adapter/out/storage/StorageProperties.java` and `S3Config` (`S3Client` + `S3Presigner` beans, path-style access against the MinIO endpoint) in `service/src/main/java/com/nalanda/validation/config/S3Config.java`
- [X] T024 [P] Create `KafkaConfig` in `service/src/main/java/com/nalanda/validation/config/KafkaConfig.java` holding the topic name constant `validation.processing-requested` and the JSON serializer/deserializer wiring that complements `application.yml`

**Checkpoint**: the domain, the ports, the schema, persistence and the web/security plumbing are in place and tested. User stories can now proceed — US1/US2/US4 in parallel, US3 after US2's publisher exists.

---

## Phase 3: User Story 1 — Register the intent to validate a document (Priority: P1) 🎯 MVP

**Goal**: a client can create a validation and receive a `requestId`, `PENDING_UPLOAD` and a usable `uploadUrl`, with idempotent create behavior.

**Independent test**: `POST /api/v1/validations` with a valid body returns `201` with the three fields; repeating it with the same `Idempotency-Key` returns the same `requestId` and creates no second row.

- [X] T025 [US1] Create `S3DocumentStorageAdapter` implementing `DocumentStoragePort` in `service/src/main/java/com/nalanda/validation/adapter/out/storage/S3DocumentStorageAdapter.java` — `createPresignedUpload` via `S3Presigner` with the configured TTL, `sizeOf` via `headObject` returning `0` for a missing object and throwing `DocumentStorageException` on any other failure (research.md R-009). **Tests** in `service/src/test/java/com/nalanda/validation/adapter/out/storage/S3DocumentStorageAdapterTest.java` (JUnit + Mockito): presign delegation, size returned, `0` on missing object, exception on other failures
- [X] T026 [P] [US1] Create the `CreateValidationCommand` record (`filename`, `contentType`, both `@NotBlank`) in `service/src/main/java/com/nalanda/validation/application/CreateValidationCommand.java`
- [X] T027 [P] [US1] Create the `CreateValidationResult` record (`requestId`, `status`, `uploadUrl`) in `service/src/main/java/com/nalanda/validation/application/CreateValidationResult.java`
- [X] T028 [US1] Create `CreateValidationUseCase` in `service/src/main/java/com/nalanda/validation/application/CreateValidationUseCase.java` — idempotency-key lookup first (on hit, re-sign an upload URL over the stored key and return the original request, per research.md R-006), otherwise generate the storage key, build `DocumentMetadata` with size `0`, create the request, sign the URL, and persist with the key. **Tests** in `service/src/test/java/com/nalanda/validation/application/CreateValidationUseCaseTest.java` against `InMemoryValidationRequestRepository` and `FakeDocumentStoragePort` (no Mockito): fresh create, replay with the same key returning the same id and a fresh URL and creating nothing, replay with a different body still returning the original resource, and create without a key
- [X] T029 [US1] Add the `POST /api/v1/validations` handler to `service/src/main/java/com/nalanda/validation/adapter/in/web/ValidationController.java` — `@Valid @RequestBody CreateValidationCommand`, optional `Idempotency-Key` header, `201` with `CreateValidationResult`. **Tests** in `service/src/test/java/com/nalanda/validation/adapter/in/web/ValidationControllerTest.java` (`@WebMvcTest`, use cases mocked): `201` and its JSON shape, `400` with `errors[]` for a blank/missing `filename` and `contentType`, and `401` without the API key

**Checkpoint**: US1 is independently demonstrable end-to-end against a running MinIO.

---

## Phase 4: User Story 2 — Confirm the upload and get the work accepted (Priority: P1)

**Goal**: confirm moves `PENDING_UPLOAD → QUEUED`, publishes exactly one event, returns immediately, and is safe to repeat.

**Independent test**: confirm a `PENDING_UPLOAD` request → `202` with `QUEUED` and one published event; confirm again → `202` with the current status and no second event.

- [X] T030 [P] [US2] Create the `ProcessingRequestedEvent` record (`validationRequestId`) in `service/src/main/java/com/nalanda/validation/adapter/out/messaging/ProcessingRequestedEvent.java`
- [X] T031 [US2] Create `KafkaJobPublisher` implementing `JobPublisher` in `service/src/main/java/com/nalanda/validation/adapter/out/messaging/KafkaJobPublisher.java` — sends to `validation.processing-requested` keyed by the request id string. **Tests** in `service/src/test/java/com/nalanda/validation/adapter/out/messaging/KafkaJobPublisherTest.java` (JUnit + Mockito): correct topic, key and payload
- [X] T032 [P] [US2] Create the `ConfirmUploadResult` record (`requestId`, `status`) in `service/src/main/java/com/nalanda/validation/application/ConfirmUploadResult.java` (research.md R-005)
- [X] T033 [US2] Create `ConfirmUploadUseCase` in `service/src/main/java/com/nalanda/validation/application/ConfirmUploadUseCase.java` — `findById`, `confirmUpload()`, `save`, publish **only** when the status is now `QUEUED`, return `ConfirmUploadResult`; never calls `DocumentStoragePort`. **Tests** in `service/src/test/java/com/nalanda/validation/application/ConfirmUploadUseCaseTest.java` against `InMemoryValidationRequestRepository` and `RecordingJobPublisher`: first confirm publishes once and returns `QUEUED`, repeated confirm publishes nothing and returns the current status, confirm on a `COMPLETED` request publishes nothing, an unknown id throws `ValidationRequestNotFoundException`, and — covering FR-010/SC-002 — the returned status is the one the request holds at publish time, computed without the processing use case having run
- [X] T034 [US2] Add the `POST /api/v1/validations/{requestId}/confirm` handler to `service/src/main/java/com/nalanda/validation/adapter/in/web/ValidationController.java` — no body, `202` with `ConfirmUploadResult`. **Tests** added to `ValidationControllerTest`: `202` and its JSON shape, `404` for an unknown id, `401` without the API key

**Checkpoint**: work is accepted and handed off; nothing consumes it yet.

---

## Phase 5: User Story 3 — The document is checked asynchronously (Priority: P1)

**Goal**: the handed-off job is consumed, the size is discovered, the deterministic rule is applied, and a final state is written — with duplicate deliveries absorbed.

**Independent test**: deliver a job for a `QUEUED` request with a known content type and size and assert the resulting status and verdict; re-deliver and assert nothing changed.

- [X] T035 [US3] Create `ProcessValidationUseCase` in `service/src/main/java/com/nalanda/validation/application/ProcessValidationUseCase.java` — `findById`; `startProcessing()` inside a try/catch that swallows `InvalidStatusTransitionException` as a no-op; `sizeOf`; evaluate the rule against the named constants `SUPPORTED_CONTENT_TYPE`, `MAX_DOCUMENT_SIZE_BYTES` and the three reason constants, in the documented order; `complete(result)`; any unexpected failure (storage outage included, missing object excluded) drives `fail()`. **Tests** in `service/src/test/java/com/nalanda/validation/application/ProcessValidationUseCaseTest.java` against fakes: `PASS` for a PDF of 1 byte, of exactly 15 MB, `FAIL`/`unsupported content type`, `FAIL`/`empty file` for size `0`, `FAIL`/`file too large` for 15 MB + 1, duplicate delivery leaving the recorded result untouched, and a storage outage producing `FAILED` with no result, and the discovered size being recorded on the request
- [X] T036 [US3] Create `KafkaJobConsumer` implementing `JobConsumer` in `service/src/main/java/com/nalanda/validation/adapter/out/messaging/KafkaJobConsumer.java` — `@KafkaListener` on `validation.processing-requested` with group `validation-service`, putting the request id in the MDC and delegating straight to `ProcessValidationUseCase`, with no business logic of its own. **Tests** in `service/src/test/java/com/nalanda/validation/adapter/out/messaging/KafkaJobConsumerTest.java` (JUnit + Mockito): the id from the event is passed through to the use case

**Checkpoint**: the full asynchronous loop closes — create, upload, confirm, process.

---

## Phase 6: User Story 4 — Read the current state and the outcome (Priority: P1)

**Goal**: a client can poll for the status and read the result once it exists, without ever mutating state.

**Independent test**: read a request at each status and assert the response carries the current status, and the result exactly from `COMPLETED` onward.

- [X] T037 [US4] Create `GetValidationUseCase` in `service/src/main/java/com/nalanda/validation/application/GetValidationUseCase.java` — `findById` and return, no mutation. **Tests** in `service/src/test/java/com/nalanda/validation/application/GetValidationUseCaseTest.java`: returns the request unchanged, and an unknown id throws `ValidationRequestNotFoundException`
- [X] T038 [US4] Add the `GET /api/v1/validations/{requestId}` handler to `service/src/main/java/com/nalanda/validation/adapter/in/web/ValidationController.java` returning the `ValidationRequest` itself. **Tests** added to `ValidationControllerTest`: `200` with status only before completion, `200` with the full `result` after completion, `document` absent from the payload, `404` for an unknown id, `401` without the API key

**Checkpoint**: all four stories complete; the contract in `contracts/http-api.md` is fully implemented.

---

## Phase 7: Polish & cross-cutting

- [X] T039 Amend `docs/service/code_rules.md`: § 1 so the document field is not `final` and the entity exposes `recordDiscoveredSize(long)` (R-013), § 2 so the repository port shows both `save` overloads (R-007), and § 6 so the confirm snippet returns `ConfirmUploadResult` (R-005) — all approved amendments
- [X] T040 [P] Amend `docs/business-rules.md` § 6 so the idempotent-replay row states that a freshly signed upload URL is returned with the original resource (research.md R-006)
- [X] T041 [P] Add an API section to `README.md` documenting the three endpoints, the `X-Api-Key` header and the concrete idempotency rules, and extend `§ How to run` with the service run and test commands
- [X] T042 Run `cd service && ./gradlew test` and confirm the whole suite is green with no container running (FR-034); then run `./gradlew bootRun` against `docker compose up -d` and walk the flow in `quickstart.md` § 4 end to end
- [X] T043 Re-read the full diff against `docs/service/code_rules.md` § 9 — no framework import in `domain/`, no `@Entity` outside `adapter/out/persistence`, no Mockito in `application` tests, no Testcontainers, no ad-hoc error shape, confirm doing no storage I/O, and every test named `should_<expectedBehavior>_when_<condition>()`

---

## Dependencies

```
Phase 1 (T001-T003)
   └─► Phase 2 (T004-T024, incl. T022b)   ← blocking for everything below
          ├─► Phase 3 US1 (T025-T029)
          ├─► Phase 4 US2 (T030-T034)
          │        └─► Phase 5 US3 (T035-T036)   # needs the event record from T030
          └─► Phase 6 US4 (T037-T038)
                   └─► Phase 7 (T039-T043)
```

Within Phase 2: T004–T008 are parallel; T009 needs T004–T008. T010–T013 are parallel and need the model. T014→T015→T016→T017→T018→T019 are sequential. T020–T024 are parallel with each other and with the persistence chain, except T022b which needs T021 and T022.

US1, US2 and US4 are independent of each other once Phase 2 is done. US3 depends only on the `ProcessingRequestedEvent` record (T030). T029, T034 and T038 all edit `ValidationController.java`, so they are sequential relative to each other even though their stories are not.

## Parallel execution examples

- **Phase 2, value objects**: T004, T005, T006, T007, T008 — five different files, no shared state.
- **Phase 2, ports**: T010, T011, T012, T013 — four different files.
- **Phase 2, cross-cutting**: T020, T021, T022, T023, T024 — independent of the persistence chain T014–T019 (T022b follows T021 and T022).
- **After Phase 2**: one track on US1 (T025–T028), one on US2 (T030–T033), one on US4 (T037) — they only converge on the controller.

## Implementation strategy

**MVP**: Phase 1 + Phase 2 + Phase 3 (US1). That alone gives a working create endpoint with idempotency, a real presigned URL and a persisted request — demonstrable on its own.

**Increment 2**: Phase 4 (US2) — work is accepted and handed off.

**Increment 3**: Phase 5 (US3) + Phase 6 (US4) — the loop closes and the outcome becomes observable. These two together are what make the asynchronous story demonstrable.

**Increment 4**: Phase 7 — docs amended in the same change (constitution I) and the whole thing verified end to end.

## Task count

44 tasks: 3 setup, 22 foundational, 5 US1, 5 US2, 2 US3, 2 US4, 5 polish.
