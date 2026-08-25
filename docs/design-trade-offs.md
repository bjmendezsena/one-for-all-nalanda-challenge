# Design trade-offs

Status: living document. This file was extracted out of `README.md § Design trade-offs` once that section grew to dominate the README's length. It records every architecture, business, and code decision made during design, including the discarded alternative and the actual reason behind the choice — not just "what was done", but "why". It is referenced by name (e.g. "see `docs/design-trade-offs.md` § Domain model style") from `README.md` and from `docs/**/*.md` wherever a specific decision is relevant. Entries stay in their original order, so a "(see X above)" reference within this file still resolves to an earlier section here.

## Async processing mechanism: real Kafka via docker-compose

**Alternatives considered:** real Kafka via Testcontainers/docker-compose, or a custom abstraction (`JobPublisher`/`JobConsumer`) with an in-memory/`@Async` implementation for local mode.

**Chosen:** real Kafka, run as one more service in `docker-compose`.

**Why:**
- Infrastructure consistency: since the local environment already spins up several services via `docker-compose` (Postgres, MinIO), adding Kafka doesn't add real orchestration cost — the whole environment comes up with a single `docker compose up`.
- With no meaningful operational overhead added, we prefer to maximize production fidelity (a real messaging mechanism with a producer/consumer, instead of just a "Kafka-ready" interface) without penalizing the timebox.

_(Initial decision: the custom abstraction was chosen first for simplicity and low effort, since the assignment offers it as an equally valid alternative. It was reversed after deciding to also run MinIO alongside Postgres via docker-compose, since at that point the local environment already requires container orchestration anyway.)_

## Document byte storage: MinIO (S3-compatible) via docker-compose

**Alternatives considered:** local filesystem (store the file on disk/volume and reference it with a storage key), or an S3-compatible stub such as MinIO.

**Chosen:** MinIO, as an additional service in `docker-compose` alongside Postgres.

**Why:**
- Since the project already spins up several services via `docker-compose`, adding MinIO doesn't add real orchestration cost (one more container in the same `docker compose up`).
- It enables a real "presigned URL" flow, faithful to how it would work with S3/AWS in production, instead of simulating it with a custom upload endpoint — stronger signal on backend judgment for the storage/upload-gateway part.

## Document upload flow: real presigned URL (MinIO) + confirm endpoint

**Alternatives considered:** a real presigned URL against MinIO (the client uploads directly to the bucket) with a `.../confirm` endpoint so the backend learns the upload finished, or a custom `PUT .../content` endpoint where the backend acts as a proxy and forwards the bytes to MinIO internally.

**Chosen:** real presigned URL (MinIO) + `POST .../confirm`.

**Why:**
- Consistency with the MinIO decision: the whole point of using MinIO was to be able to issue real presigned URLs; using the backend as a byte proxy would negate that benefit.
- Better architecture: the backend never receives or forwards the file bytes, it only generates the signed URL and reacts to the confirmation — less load and less API surface, closer to a real production design where heavy traffic (the file) never passes through the business service.
- The `confirm` endpoint stops being optional in this design: since the upload happens outside the backend's view (directly to MinIO), it's the only way for the backend to know it can move the status to `QUEUED` and publish the event to Kafka.

## Idempotency strategy: Idempotency-Key header (create) + state-machine guards (confirm/upload)

**Alternatives considered:** a client-supplied `Idempotency-Key` header on `POST /validations` combined with state guards on `confirm`/`upload`, or relying only on state-machine guards (each `POST` always creates a new resource; only the state transitions are made safe to repeat).

**Chosen:** `Idempotency-Key` header on create + state-machine guards on confirm/upload.

**Why:**
- Robustness against real network retries: in a flow with presigned URLs and separate calls (create, confirm), a client timeout/retry is a realistic scenario. Without an `Idempotency-Key`, a retried `POST /validations` would create a duplicate resource; without state guards, a retried `confirm` would re-publish the event to Kafka and cause the document to be processed twice.
- It's explicitly requested by the assignment, which asks for "idempotent create/upload behaviour where it matters (document your rules)" — an explicit `Idempotency-Key` is the standard, easy-to-document way to demonstrate this in the API.

**Rules (documented in `docs/service/api.md`):**
- `POST /api/v1/validations` accepts an optional `Idempotency-Key` header. If a request with the same key was already processed, the endpoint returns the original `requestId` and response instead of creating a new resource.
- `POST /api/v1/validations/{requestId}/confirm` checks the current status before transitioning: if the resource is no longer in `PENDING_UPLOAD` (i.e. `confirm` was already called), it returns success with the current status instead of re-publishing to Kafka.

## Persistence & migrations: JPA/Hibernate + Liquibase

**Alternatives considered:** JPA/Hibernate (code-first entities, DB schema derived from Java classes) vs jOOQ (schema-first via SQL migrations + generated typesafe query code) for the ORM layer; Liquibase vs Flyway for schema migrations.

**Chosen:** JPA/Hibernate for persistence, Liquibase for migrations.

**Why:**
- Prior hands-on experience with both JPA/Hibernate and Liquibase, which reduces execution risk inside a 24h timebox.
- It's the de facto standard for Spring Boot projects, which keeps the codebase approachable for any teammate reviewing it.
- It's explicitly what the assignment asks for ("JPA / Spring Data is fine", "PostgreSQL + Liquibase"), so no deviation needs to be justified.

jOOQ (schema-first codegen, closer to a Prisma-like workflow) was considered but not chosen: it would add an extra dependency and generation step for no additional signal, since the assignment already names JPA/Spring Data as sufficient.

## API error format: RFC 7807 Problem Details

**Alternatives considered:** RFC 7807/9457 Problem Details (`application/problem+json` with `type`/`title`/`status`/`detail`/`instance`), or a custom internal error convention — the assignment explicitly allows either ("Consistent error body (problem details or a small internal convention)").

**Chosen:** RFC 7807 Problem Details.

**Why:**
- Spring Boot 3.x supports it natively via `org.springframework.http.ProblemDetail`, including out-of-the-box enrichment for `jakarta.validation` failures — no custom error class, serializer, or bespoke documentation to write.
- It's a real, recognizable standard rather than an invented shape a reviewer has to learn from scratch by reading the code.
- It fits the SDK requirement of "clear error type(s) with HTTP status + body": the SDK just parses this standard shape (`status`, `title`, `detail`) into its own TypeScript error class, with no custom mapping needed.
- `ProblemDetail` can still be extended with extra properties (e.g. a structured list of field validation errors via `setProperty(...)`) if a specific need arises, so it isn't a limiting choice.

## SDK bundler: Vite (library mode)

**Alternatives considered:** Vite in library mode (`build.lib`, Rollup under the hood), or tsup (a bundler purpose-built for TypeScript libraries, minimal config).

**Chosen:** Vite (library mode).

**Why:**
- It's what the assignment itself suggests ("Vite (or equivalent) producing dual ESM + CJS + .d.ts"), so no deviation needs to be justified.
- Prior hands-on experience with Vite and its ecosystem, which reduces configuration risk inside a 24h timebox.

## Example project: a separate top-level consumer rather than another snippet in `sdk/examples/`

**Alternatives considered:** adding another script under `sdk/examples/` next to `validate-document.mjs`; or a separate top-level `example/` project that installs the SDK through its own manifest.

**Chosen:** a separate top-level `example/`, declaring `"@nalanda/validation-sdk": "file:../sdk"` and importing by package name.

**Why:**
- `sdk/examples/validate-document.mjs` imports `../dist/index.js` by relative path. That resolves a file, not a package: it never touches the `exports` map, the `files: ["dist"]` allowlist, or the published `.d.ts`. Those three are exactly what breaks when a library is published, and nothing in the repository was checking them.
- An external consumer is also the only place the SDK's ergonomics can be judged honestly — if the published types are wrong or an export is missing, `example/` fails to compile, which is the point.
- The cost is a third artifact in a repository documented as having two, so the constitution, `CLAUDE.md` and this README were amended in the same change (constitution 1.2.0) rather than letting the docs drift.
- It paid for itself immediately: the first end-to-end run surfaced a real defect in `sdk/`, where a presigned `PUT` answering `200` with an empty body was handed to `JSON.parse` (fixed in `0.1.1`). The unit tests had missed it because they stubbed storage with a JSON body.

## Example harness: a self-written scenario runner, not a test framework

**Alternatives considered:** a Vitest suite in `example/` pointed at the live service; Node's built-in `node:test`; or a small hand-written runner with no test dependency.

**Chosen:** a hand-written runner (~60 lines) where each scenario declares its expected outcome, the report prints expected-versus-observed, and the process exits non-zero if any scenario failed.

**Why:**
- `docs/sdk/code_rules.md` § 6 fixes the SDK's test style as Vitest against a *mocked* `fetch`. Putting a Vitest suite that hits a live backend next to it would blur a boundary the code rules draw deliberately.
- The artifact has two audiences at once — someone reading it as an example, and CI reading its exit code. Test-runner output serves the second well and the first badly; a report naming each scenario, what it covers and what it expected serves both.
- It keeps `example/` dependency-free at runtime, so what a reader sees is SDK usage rather than framework wiring.
- The cost is writing the runner and the assertions by hand. At fourteen scenarios that is a small, readable file; at ten times that, a framework would win.

## Authentication: static API key stub

**Alternatives considered:** no authentication at all (the assignment marks full auth as out of scope and stubs as optional, "if present"), or a minimal static API key stub via header.

**Chosen:** static API key stub (a filter/interceptor requiring a configured `X-Api-Key` header, returning 401 if missing/incorrect).

**Why:**
- It's a small amount of code (a single filter) that adds extra signal on backend judgment — shows security was considered — without diverting meaningful time from the higher-weight parts of the evaluation (async design, SDK).

## Integration testing strategy: H2 in-memory + mocks/fakes for Kafka/MinIO

**Alternatives considered:** Testcontainers (real Postgres/Kafka/MinIO containers spun up per test run), or an in-memory H2 database for repository tests combined with mocks/fakes for the `JobPublisher`/Kafka and MinIO interactions.

**Chosen:** H2 in-memory + mocks/fakes.

**Why:**
- Maximum simplicity for the test suite: no Docker dependency to run tests, faster feedback loop, less moving parts to set up within the 24h timebox.

**Trade-off accepted:** this is a deliberate departure from full production fidelity. H2 is not 100% SQL-compatible with PostgreSQL (dialect differences can hide real bugs, e.g. around Postgres-specific column types or constraints), and mocking Kafka/MinIO means the tests don't exercise the real serialization/network behavior of those integrations — only the business logic around them. Given the 24h timebox, this was accepted in exchange for a simpler, faster, Docker-independent test suite; Testcontainers remains the natural next step ("what I'd do with another day", see `README.md`) to close this gap.

## Internal service architecture: hexagonal (ports & adapters) across all integrations

**Alternatives considered:** a traditional layered architecture (`Controller → Service → Repository`, with JPA repositories, `KafkaTemplate`, and the S3 SDK client used directly inside business logic), or a hexagonal architecture where the domain/application layer depends only on its own ports (`ValidationRequestRepository`, `JobPublisher`, `DocumentStoragePort`) and every concrete integration (JPA/Postgres, Kafka, MinIO/S3) is implemented as an adapter behind those ports.

**Chosen:** hexagonal architecture, applied consistently to all three integrations (persistence, messaging, and object storage) — not only to messaging.

**Why:**
- Testability: business/domain logic can be tested against fakes or in-memory implementations of each port, without needing Postgres, Kafka, or MinIO running — independent from (and complementary to) the H2+mocks integration tests, which still exercise the adapters themselves.
- It's a strong, consistent signal on the highest-weight evaluation criteria ("API & domain design", "Async / messaging design: clean boundaries; Kafka-ready thinking"): separating domain from infrastructure across every integration demonstrates that judgment explicitly, rather than only in the messaging piece the assignment calls out by name.

**Note on MinIO/S3 specifically:** MinIO itself already provides a protocol-level abstraction — it implements the S3 API, so the same AWS S3 SDK client (`S3Client`/`S3Presigner`) works unmodified against MinIO, real AWS S3, or any other S3-compatible provider (just by changing endpoint/credentials). That alone is enough to move between S3-compatible backends. It does **not**, however, cover a provider with a fundamentally different API (e.g. Cloudinary, native Google Cloud Storage, Azure Blob Storage, or plain local disk), and it doesn't stop AWS SDK types (`PutObjectPresignRequest`, `S3Presigner`, ...) from leaking into domain/service code if called directly. A dedicated `DocumentStoragePort` was kept anyway, for consistency with the rest of the hexagonal design and so domain-level tests don't depend on any AWS SDK type — not because the S3 protocol itself needed extra abstraction to swap vendors.

## Package organization: layer-first (domain / application / adapter / config)

**Alternatives considered:** organizing packages by layer at the top level (`domain/`, `application/`, `adapter/in/web`, `adapter/out/{persistence,messaging,storage}`, `config/`), or by feature with hexagonal layers nested inside each feature (`validation/{domain,application,adapter}/...`).

**Chosen:** layer-first.

**Why:**
- It's the simplest and most readable option given there is a single domain (validation request) in this slice — feature-first would add nesting with no real benefit right now.
- It also makes the hexagonal architecture visible immediately at the top package level, without a reviewer needing to open a feature folder to see the domain/application/adapter split.

## Gradle module structure: single module

**Alternatives considered:** a single Gradle module for `service/` (hexagonal separation enforced only at the Java package level), or a multi-module Gradle build with `domain`/`application`/`infrastructure` as separate subprojects, each with its own `build.gradle`, so the compiler enforces that `domain` cannot depend on `infrastructure`.

**Chosen:** single module.

**Why:**
- Consistent with the assignment's explicit warning against "incomplete microservices cosplay" and with this being a "thin vertical slice" — multi-module for a single small service would go in the opposite direction.

## Deterministic verdict rule for the processing stub: supported content-type + file size range

**Alternatives considered:** a filename convention (e.g. filename contains "fail" → FAIL), a size-only threshold, or a combination of supported content-type + file size range.

**Chosen:** combination of supported content-type + file size range. Concretely: only `application/pdf` is a supported content-type; a file must be strictly larger than 0 bytes and no larger than 15 MB to pass. Outside either condition, the stub returns `FAIL` with a clear `reason`; otherwise it returns `PASS`.

**Why:**
- It resolves the assignment's two explicit stub requirements ("fail clearly on empty files or unsupported content types" and produce a `PASS`/`FAIL` verdict) with one coherent rule, instead of treating them as unrelated mechanisms.
- Restricting to PDF-only reflects a more realistic business rule than an arbitrary filename convention: business documents (invoices, contracts) are, in practice, almost always PDF.
- The 0–15 MB range is a realistic size boundary for individual business documents (comfortably covers a multi-page scanned PDF) and is trivial to test deterministically (empty file → FAIL, oversized generated file → FAIL, normal PDF → PASS).

## Idempotency-Key concrete rules: no TTL, conflicting body ignored

**Alternatives considered (TTL):** no expiration (the key stays valid for as long as the `ValidationRequest` exists), or a short TTL (e.g. 24h, mirroring how some real APIs like Stripe scope idempotency keys).

**Chosen (TTL):** no expiration.

**Alternatives considered (key reused with a different body):** return the original resource, ignoring the new body; or reject with `409 Conflict` if the new body doesn't match the original (requires storing/comparing the original body or its hash).

**Chosen (conflicting body):** return the original resource, ignoring the new body.

**Why:**
- Simplicity within the 24h timebox: no expiration column, no cleanup job, and no body-comparison logic are needed — less code and less surface for bugs, for a problem (avoiding duplicate resources from network retries) that these simpler rules already solve.
- The scenario being protected against (a client retry after a timeout) happens within seconds/minutes, not days, so an indefinite key is practically equivalent to a short TTL for this use case, without the extra bookkeeping.
- In practice, a reused key with a slightly different body is almost always a retry with some non-deterministic field (a timestamp, an internal client request id), not a genuine new request — returning the original resource matches what the client actually expects.

## Domain model style: rich entity + immutable value objects

**Alternatives considered:** a rich domain model (`ValidationRequest` as a mutable entity with identity, exposing behavior methods — `confirmUpload()`, `startProcessing()`, `complete(result)`, `fail()` — that enforce the status machine internally; `DocumentMetadata`/`ValidationResult` as immutable Java records with no identity), or a fully immutable model (every domain object, including `ValidationRequest`, as a record, with transitions modeled as static factory methods returning a new instance).

**Chosen:** rich entity (`ValidationRequest`) + immutable value objects (`DocumentMetadata`, `ValidationResult`).

**Why:**
- Avoids the "anemic domain" the assignment explicitly names as an AI suggestion to reject — behavior lives inside the entity, not in an external service mutating public fields.
- `ValidationRequest` has identity and persists its state across separate HTTP calls (create, confirm, get); modeling it as a stateful entity with behavior is more natural than reconstructing immutable instances at every step.

## Port naming and not-found handling

**Alternatives considered (naming):** the mixed naming already used in `docs/service/architecture.md` (`ValidationRequestRepository`, `JobPublisher`, `JobConsumer`, `DocumentStoragePort`), or a uniform `Port` suffix on every port interface.

**Chosen (naming):** mixed naming, as already documented in `docs/service/architecture.md`.

**Alternatives considered (not-found):** `Optional<T>` in the port's return type, with the use case deciding what to do with an empty result; or the adapter throwing the domain "not found" exception directly, with the port's return type being the non-optional domain object.

**Chosen (not-found):** the adapter throws the domain exception directly.

**Why:**
- Naming: repository/publisher/consumer names are self-explanatory about the port's role; adding "Port" everywhere is repetitive without adding information.
- Not-found: a missing `requestId` is never a valid business outcome for these lookups (always a 404), so `Optional` would only add boilerplate without real flexibility — centralizing "not found → exception" in the adapter avoids repeating that decision in every use case.

## Adapter naming and domain↔persistence mapping

**Alternatives considered (naming):** a technology prefix (`JpaValidationRequestRepository`, `KafkaJobPublisher`, `S3DocumentStorageAdapter`), or a uniform `Adapter` suffix (`ValidationRequestRepositoryJpaAdapter`, etc.).

**Chosen (naming):** technology prefix.

**Alternatives considered (mapping):** manual mapper methods (`toDomain()`/`toEntity()`) written by hand in each adapter, or MapStruct-generated mapping code.

**Chosen (mapping):** manual mappers.

**Why:**
- Naming: the technology behind an adapter is readable at a glance from its class name, without opening the file — useful for a reviewer moving quickly through the codebase.
- Mapping: manual mapping avoids adding a new dependency and annotation-processor setup to the Gradle build; with a small domain (few fields per object), hand-written mapping is just as fast to write and easier to debug (plain Java, not generated code).

## Application layer: one class per use case

**Alternatives considered:** one class per use case (`CreateValidationUseCase`, `ConfirmUploadUseCase`, `GetValidationUseCase`, `ProcessValidationUseCase`, each with a single public method and only the ports it needs), or a single `ValidationService` with one method per use case.

**Chosen:** one class per use case.

**Why:**
- Isolated testability: each use case is tested with only the fakes of the ports it actually needs, without pulling in dependencies belonging to other use cases or standing up one large service with everything wired in.
- Single responsibility: each class has one clear purpose and input/output, which reads more clearly and scales better than a `ValidationService` that keeps growing with every new use case.

## Error handling: pure domain exceptions + boundary translation to ResponseStatusException/ProblemDetail

**Alternatives considered:** domain exceptions extending Spring's `ResponseStatusException` directly (gets automatic conversion to `ProblemDetail` for free, since any exception implementing Spring's `ErrorResponse` interface — which `ResponseStatusException` does — is converted automatically, but couples the `domain` package to `org.springframework.web.server`); or plain domain exceptions (`RuntimeException` subclasses with zero Spring dependency) with translation to `ResponseStatusException`/`ProblemDetail` happening at the boundary (`adapter/in/web`).

**Chosen:** plain domain exceptions + boundary translation.

**Why:**
- Consistency with the hexagonal-architecture rule already established in `docs/service/architecture.md` § 4.1 and `docs/design-trade-offs.md § Internal service architecture` ("domain has no dependency on Spring, JPA, Kafka, or the AWS SDK") — extending a Spring exception class from `domain` would break that rule on its first practical test.
- Keeps domain-level tests (e.g. asserting `ValidationRequest.confirmUpload()` throws on an invalid transition) as pure unit tests with no `org.springframework.*` import and no Spring context.

**Additional rule:** the `ProblemDetail` response is extended with a custom `errors` property — a list of `{ field, message }` — when the failure is a multi-field validation error. This uses `ProblemDetail`'s standard extensibility mechanism (RFC 7807 `properties`) for exactly the case it's designed for, rather than inventing a free-text convention inside `detail`.

## Controllers: domain model exposed directly (no dedicated DTOs)

**Alternatives considered:** dedicated request/response DTOs (records) in `adapter/in/web`, with `jakarta.validation` annotations on the DTOs and an explicit mapper between DTO and domain, keeping `domain/model` free of web/serialization concerns; or exposing `domain/model` classes directly as the request/response bodies, with Jackson (`@JsonProperty`) and `jakarta.validation` annotations placed directly on the domain classes.

**Chosen:** expose `domain/model` directly.

**Why:**
- Less code and less time spent within the 24h timebox: no DTO classes and no manual DTO↔domain mapper to write and maintain for every endpoint.

**Explicitly accepted trade-off:** this is a deliberate, conscious exception to the "domain has no dependency on external frameworks" rule established for the rest of the codebase (see `Internal service architecture` and `Error handling` above, where the same tension was resolved in favor of purity). Here, `domain/model` classes import Jackson and `jakarta.validation` annotations for HTTP serialization/validation purposes. Unlike the error-handling case, this exception was made knowingly in exchange for implementation speed, not reverted — it is called out explicitly so it doesn't read as an inconsistency: the codebase keeps the domain pure everywhere except in this one, acknowledged spot.

## Testing conventions: full pyramid with hand-written fakes for use-case tests

**Alternatives considered:** a full test pyramid — pure JUnit unit tests for the domain (no Spring), use-case tests against hand-written fakes of their ports (e.g. an in-memory `ValidationRequestRepository` implementation), `@WebMvcTest` for controllers, `@DataJpaTest` (H2) for the JPA repository adapter, and plain unit tests with Mockito for the Kafka/storage adapters — or the same pyramid but using Mockito mocks instead of hand-written fakes for the use-case tests.

**Chosen:** full pyramid with hand-written fakes for use-case tests.

**Why:**
- Behavior-focused tests: a fake (e.g. `InMemoryValidationRequestRepository`) lets a test assert on the actual resulting state (what got saved, what status it has) instead of verifying exact interactions with `verify(...)`, making the tests more resistant to internal refactors of the production code that don't change observable behavior.

**Test method naming:** `should_<expectedBehavior>_when_<condition>()`, e.g. `should_throwException_when_confirmingAlreadyQueuedRequest()`.

**Alternatives considered (naming):** `should_expectedBehavior_when_condition()` method names, or short method names paired with a descriptive `@DisplayName`.

**Chosen (naming):** `should_expectedBehavior_when_condition()`.

**Why:**
- Self-explanatory without opening the test body: a reviewer scanning the test list (IDE, CI report) sees exactly what behavior each test covers directly from the method name, with no dependency on remembering to add a `@DisplayName` to every test.

## Event/messaging design: single thin event, no separate "completed" event

**Alternatives considered:** two Kafka topics/events (one for "processing requested", one for "processing completed", mirroring accept/finish as two messages), each carrying either the full document/result payload ("fat" events) or just the `validationRequestId` ("thin" events); or a single "processing requested" event with the completion signal handled entirely by the consumer writing directly to Postgres (no second event needed, since there's no other consumer to notify).

**Chosen:** a single event, `validation.processing-requested`, with a thin payload (`{ validationRequestId }`); the "finish work" side of the accept/finish separation is handled structurally (the Kafka consumer processes and writes the final state to Postgres) rather than via a second published event. Consumption is made idempotent by treating an invalid state transition (e.g. a duplicate delivery arriving after the first was already processed) as a safe no-op rather than an error. Serialization is plain JSON (Spring Kafka's `JsonSerializer`/`JsonDeserializer`), not Avro/Schema Registry.

**Why:**
- Explicit priority given by the user for this round of decisions: favor implementation speed while still meeting the assignment's requirements and keeping the design robust, rather than exploring every option in depth.
- A second "completed" event has no consumer to justify it — nothing else in this slice needs to react to completion — so it would be pure ceremony; the accept/finish separation the assignment asks for is already satisfied structurally (separate code paths: HTTP handler enqueues, Kafka consumer processes).
- A thin payload keeps Postgres as the single source of truth (the consumer always re-reads current state instead of trusting a possibly-stale copy carried in the message) and avoids serializing/versioning a larger domain payload.
- JSON avoids adding Schema Registry infrastructure for a single, simple event shape, which would be disproportionate overhead for a 24h timebox.

Full event catalog and payload schema: `docs/service/events.md`. Kafka-specific configuration (topic, consumer group, serialization, duplicate handling): `docs/service/kafka.md`.

## Upload flow: confirm does not touch storage; file size is discovered during processing

**Alternatives considered:** the `confirm` endpoint calling `DocumentStoragePort.sizeOf(storageKey)` synchronously to discover and persist the real file size before transitioning to `QUEUED`; or `confirm` staying storage-free (pure state transition + event publish) and the Kafka consumer discovering the size itself, right before evaluating the stub verdict rule.

**Chosen:** `confirm` stays storage-free; size discovery happens in the Kafka consumer during processing.

**Why:**
- Explicit priority given by the user for this round: favor implementation speed while meeting the requirements and staying robust. Keeping `confirm` free of storage I/O keeps it a trivial, fast state transition, and avoids `ConfirmUploadUseCase` needing a dependency on `DocumentStoragePort` at all.
- The file size is only ever needed for one thing — the deterministic verdict rule (`docs/business-rules.md` § 5) — which already runs during processing; fetching it there avoids fetching it twice or plumbing it through an extra step.
- Handles the "client confirmed without actually uploading" edge case for free: if the object doesn't exist in MinIO when the consumer checks, that's treated as size `0`, which already falls under the existing "empty file → FAIL" rule — no special-case code needed.

Full sequence, wire shapes, and edge cases: `docs/service/upload-flow.md`.

## SDK implementation approach: hand-written vs generated from OpenAPI

**Alternatives considered:** generating the SDK (types and/or client) from an OpenAPI spec of the backend using a tool such as `openapi-typescript`, `orval`, or `openapi-generator`; a hybrid where only the request/response types are generated and the client stays hand-written; or writing the whole SDK by hand, with no code generation step at all.

**Chosen:** fully hand-written, no code generation.

**Why:**
- Explicit priority given by the user for this round: favor implementation speed within the ~24h timebox. Code generation adds a tool to the pipeline (a maintained OpenAPI spec, a generator dependency, a generation step wired into the build) for a slice with a public surface of five methods — disproportionate overhead here.
- The assignment lists OpenAPI/Swagger as "optional" polish, not a requirement, so skipping it doesn't cost anything against the stated evaluation criteria.
- Writing it by hand gives full control over the ergonomics of the public API (naming, typed error shape, polling helper) exactly as specified by the assignment's "minimum SDK surface", rather than accepting whatever shape a generator produces.

## SDK client architecture: factory function + native `fetch` + typed error mirroring the backend

**Alternatives considered:** a `createClient(options)` factory function returning a plain object of bound methods (closures over `baseUrl`/`headers`), or an OOP design with two classes — a `HttpClient` class encapsulating `fetch` + header merging + error parsing, and a `ValidationClient` class built on top of it exposing `startValidation`/`upload`/`getValidation`/`waitForCompletion`, with `createClient(options)` kept as a thin public facade returning `new ValidationClient(...)`; `axios`/`node-fetch` as the HTTP layer, or Node's native `fetch` (available globally since Node 18); a generic/untyped error, or a dedicated `ValidationApiError` carrying the HTTP status and the parsed Problem Details body; request/response types hand-designed independently of the backend, or types that mirror the backend's actual JSON shape (which — per `docs/design-trade-offs.md § Controllers` — is `domain/model` serialized directly, not a separate DTO contract).

**Chosen:** factory function returning a plain object of closures (not classes), native `fetch`, dedicated `ValidationApiError`, types mirroring the backend's actual response shape.

**Why:**
- The two-class design was discussed explicitly and reconsidered before confirming the original choice. The deciding reason: the assignment's own "minimum SDK surface" sketch is written literally as `createClient(options)` → `client.startValidation(...)` — the shape of a factory returning a plain object, not `new Client(options)`. Matching that sketch as closely as possible was the tie-breaker.
- Secondary reasons surfaced during the discussion, in favor of keeping closures over classes: no `this`-binding failure mode (a class method loses `this` if passed around or destructured unless written as an arrow-function class field — closures can't have this bug structurally); less code for the timebox (no constructor, no separate `HttpClient` class whose only consumer would be `ValidationClient`, no `this.http.request(...)` indirection); simpler test mocking (`vi.fn()` on a plain object vs. deciding how to mock a class instance/prototype).
- A factory function also avoids `axios`/`node-fetch` as dependencies (Node 18+ ships `fetch` globally).
- A dedicated error type gives SDK consumers a single place to inspect `status` and the Problem Details body (`title`, `detail`, `errors`), directly reusing the shape already defined in `docs/service/code_rules.md` § 5, so the SDK doesn't invent a second error convention.

## SDK polling: exponential backoff + `AbortSignal`, no external retry library

**Alternatives considered:** a fixed polling interval for `waitForCompletion`, or exponential backoff with a max-attempts/timeout; implementing it with a dependency like `p-retry`, or a small hand-written loop using `setTimeout`.

**Chosen:** exponential backoff with a configurable timeout, hand-written (no `p-retry`), with `AbortSignal` support so a caller can cancel an in-flight wait.

**Why:**
- Explicit priority given by the user for this round: favor implementation speed while meeting the requirements. A short hand-written loop is simpler to reason about than pulling in a retry library for one call site, and covers the "optional polish" (retry/backoff, abort signal) the assignment calls out as valued.

## SDK testing: Vitest with `fetch` mocked directly, no MSW

**Alternatives considered:** Mock Service Worker (MSW) to intercept HTTP calls at the network layer, or mocking the global `fetch` directly (`vi.stubGlobal('fetch', ...)`).

**Chosen:** mock `fetch` directly.

**Why:**
- Same "fewer dependencies within the timebox" reasoning already applied on the backend side (see `Integration testing strategy` above) — MSW is a well-regarded tool, but adds a dependency and setup for a client this small, where a stubbed `fetch` per test is already simple and sufficient.

## SDK module format: pure ESM source, dual build only at the Vite output boundary

**Alternatives considered:** allowing whichever module syntax feels convenient at each source file, relying on transpilation to sort it out, or enforcing ESM-only syntax (`import`/`export`) throughout `src/`, with CJS output produced exclusively by Vite's build step, never hand-written.

**Chosen:** ESM-only source; the dual ESM/CJS output is exclusively Vite's responsibility.

**Why:**
- The assignment explicitly names "wrong module format" as an example of an AI suggestion to reject in `AI_USAGE.md`; enforcing ESM-only source is a direct, concrete guard against exactly that failure mode, rather than leaving it to be caught in review.

## Root package.json and dev orchestration

**Alternatives considered (scope of the root `package.json`):** declaring `workspaces: ["sdk"]` so the root shares a single `node_modules`/lockfile with `sdk/` and can run `npm run <script> -w sdk`; or a plain, scripts-only root `package.json` with no `workspaces` field, delegating to `service/` (Gradle) and `sdk/` (npm) via `cd`/`--prefix`.

**Chosen:** plain, scripts-only root `package.json`, no `workspaces`.

**Why:**
- `sdk/` is the only real JS/TS package in the repo — `workspaces` exists to share/hoist dependencies across multiple JS packages, and with just one there's nothing to hoist, so it would add configuration with no real benefit.
- The repo is polyglot (Java/Gradle for `service/`, TypeScript/npm for `sdk/`); declaring `workspaces` would visually suggest a multi-package JS monorepo to a reader, which isn't what this is.

**Alternatives considered (running `dev` across infra + service + SDK):** a hand-written bash script backgrounding each process with `&` (and a `trap`/`kill` to tear them down together); or `concurrently` as a small devDependency to run and label multiple processes from one `npm run dev`.

**Chosen:** `concurrently`.

**Why:**
- Better developer experience: each process's logs are prefixed and colored (`infra`/`service`/`sdk`), so it's immediately clear which process printed what — relevant to the assignment's "Communication" evaluation criterion, not just to the author.
- More robust failure handling: `concurrently` can be configured to stop every process if one fails, whereas a hand-rolled `&`/`trap` script is easy to get wrong and can leave orphaned processes running.
- It's the de-facto standard tool for this in the Node ecosystem — a reviewer doesn't need to read custom bash to understand what `npm run dev` does.

## Toolchain setup: documented prerequisites + `.nvmrc`, no Makefile

**Alternatives considered:** a Makefile with a `make setup`/`make install-deps` target that installs Java and Node via the OS package manager (`brew`, `apt`, ...); or documenting the required toolchain versions in the README's § Prerequisites and letting each developer install them with their own tool of choice (`sdkman`, `brew`, `asdf`, `nvm`, the official installers), with `.nvmrc` pinning the recommended Node version for `nvm` users.

**Chosen:** documented prerequisites + `.nvmrc`, no Makefile.

**Why:**
- Installing runtimes themselves (as opposed to project dependencies) is OS-specific — the install command for Java/Node differs between macOS, Debian/Ubuntu, and Windows — so a `make setup` target would need per-OS branches to be reliable, and would likely fail silently on whatever OS wasn't anticipated. That's exactly the kind of extra infrastructure the assignment asks to avoid ("quality over quantity"; Terraform/ECS-style tooling is explicitly out of scope).
- `package.json` was already chosen as the single orchestration entrypoint for this repo (see above); a Makefile that just aliased the same npm scripts (`make dev` → `npm run dev`) would be a second entrypoint doing the same job, working against the simplicity that decision was made for.
- `.nvmrc` is the standard, zero-execution way to pin a Node version — it only declares a version for `nvm`/`fnm`/`asdf` to read, so it carries none of the cross-platform risk a Makefile install step would.
