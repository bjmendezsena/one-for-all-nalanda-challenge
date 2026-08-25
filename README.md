# one-for-all-nalanda-challenge

Fullstack Engineer Sr — Technical Assessment. Spring Boot service (`service/`) + TypeScript SDK (`sdk/`) implementing the asynchronous document validation flow.

## Architecture

The system validates business documents asynchronously. A client:
1. Requests a validation → receives a `requestId` and a presigned upload URL.
2. Uploads the document bytes directly to object storage using that URL.
3. Confirms the upload → the backend enqueues a processing event.
4. Processing happens asynchronously (extraction is a deterministic stub).
5. The client polls the validation status, or uses the SDK's `waitForCompletion` helper.

Two artifacts are delivered from one monorepo:
- `service/` — the Java/Spring Boot backend implementing the API and the async processing pipeline.
- `sdk/` — the TypeScript client library that wraps the HTTP API for Node/bundler consumers.

For the detailed backend architecture (hexagonal layers, package structure, ports/adapters, infrastructure), see [`docs/service/architecture.md`](docs/service/architecture.md); for the SDK's architecture, see [`docs/sdk/architecture.md`](docs/sdk/architecture.md). For the reasoning behind each design decision, see § Design trade-offs below.

### Repository layout

`docs/` holds only documentation shared between `service/` and `sdk/` (currently just the business rules); each artifact's own detailed docs live under `docs/service/` and `docs/sdk/` respectively.

```
one-for-all-nalanda-challenge/
├── README.md
├── AI_USAGE.md
├── package.json                # root orchestration scripts only (dev/build/test) — not a publishable package
├── .nvmrc                       # pins the recommended Node version
├── .gitignore
├── docker/                     # every Docker asset lives here — nowhere else
│   ├── docker-compose.yml      # postgres + kafka + minio for local development
│   ├── .env                    # local defaults: ports, credentials, bucket name
│   └── README.md
├── docs/
│   ├── business-rules.md      # shared: domain/business rules
│   ├── service/                # service-specific documentation
│   │   ├── architecture.md
│   │   ├── code_rules.md
│   │   ├── events.md
│   │   ├── kafka.md
│   │   └── upload-flow.md
│   └── sdk/                    # SDK-specific documentation
│       ├── architecture.md
│       └── code_rules.md
├── service/                    # Java / Spring Boot backend (Gradle wrapper committed)
├── sdk/                        # TypeScript client library
├── specs/                      # spec-kit feature artifacts (spec / plan / tasks per feature)
└── .specify/                   # spec-kit configuration, templates and the constitution
```

## Prerequisites

- **Java 21** (JDK) — to build/run `service/`. The Gradle wrapper (`./gradlew`) is committed, so a separate Gradle install is not needed.
- **Node.js ≥ 18** — required for the SDK's native `fetch` usage and its tooling (Vite, Vitest) and for the root orchestration scripts. `.nvmrc` at the repo root pins the recommended version (`nvm use` picks it up automatically).
- **Docker + Docker Compose** (the `docker compose` v2 CLI) — to run PostgreSQL, Kafka, and MinIO locally via `docker/docker-compose.yml`. Every Docker asset in this repository lives under `docker/`; see `docs/service/architecture.md` § 3.

## How to run the service + DB

From the repo root:

```bash
npm run docker:up      # starts postgres, kafka, minio (docker/docker-compose.yml)
npm run dev:service    # cd service && ./gradlew bootRun
```

Or start everything (infra + service + SDK watch build) in one terminal with `npm run dev` (see § Root package.json below).

The first `docker compose up` also creates the `validation-documents` bucket in MinIO, so no manual
provisioning step is needed. Ports, credentials and the bucket name come from `docker/.env` and can
be overridden there without editing the compose file; the service reads the same values with the
same defaults. `npm run docker:down` stops the environment while keeping its data in named volumes;
`docker compose -f docker/docker-compose.yml down -v` wipes it.

To run the service's own automated tests: `npm run test:service` (`./gradlew test`, against H2 — see § Design trade-offs → Integration testing strategy). They need no running infrastructure.

The same two commands from inside `service/`, without the root npm wrapper:

```bash
cd service
./gradlew bootRun   # runs the API on http://localhost:8080, applying the Liquibase changelog on startup
./gradlew test      # the whole suite: domain, use cases, controller, persistence, messaging, storage
```

`./gradlew test` is green with nothing running: persistence tests use H2 in PostgreSQL mode with the
real changelog applied, and Kafka and MinIO are replaced by hand-written fakes and mocks.

### Verifying the environment is up

```bash
cd docker
docker compose ps -a
```

Expected: `postgres`, `kafka` and `minio` report `healthy`, and the one-shot `minio-init` shows
`Exited (0)` — it created the bucket and finished.

| What to check | Command | Expected |
|---|---|---|
| The bucket was created | `docker compose logs minio-init --no-log-prefix` | `bucket validation-documents ready` |
| PostgreSQL accepts connections | `docker compose exec -T postgres psql -U validation -d validation -c '\conninfo'` | connection details for database `validation` |
| Kafka is reachable | `docker compose exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092` | the broker's supported API versions |
| Local state survives a restart | `docker compose down && docker compose up -d` | data written before the restart is still there |

The backend connects to all three on startup, so `npm run dev:service` succeeding against a running
environment is itself an end-to-end check of the wiring.

### Testing MinIO

MinIO is the S3-compatible object storage the presigned upload flow targets
(see [`docs/service/upload-flow.md`](docs/service/upload-flow.md)). Three ways to exercise it,
from the quickest to the most faithful:

**1. The console (visual check)** — <http://localhost:9001>, credentials `minioadmin` / `minioadmin`
(or whatever `docker/.env` sets). The `validation-documents` bucket should be listed and browsable.

**2. `mc`, MinIO's own CLI** — already present inside the `minio` container. Point it at the server
once, then use it like any S3 client:

```bash
cd docker

# one-time per container start: register the local server under the alias "local"
docker compose exec -T minio mc alias set local http://localhost:9000 minioadmin minioadmin

# list, upload, download, remove
docker compose exec -T minio mc ls local/validation-documents
echo "hello" > /tmp/probe.txt && docker compose cp /tmp/probe.txt minio:/tmp/probe.txt
docker compose exec -T minio mc cp /tmp/probe.txt local/validation-documents/probe.txt
docker compose exec -T minio mc cat local/validation-documents/probe.txt   # → hello
docker compose exec -T minio mc rm  local/validation-documents/probe.txt
```

**3. A real presigned URL (the flow the service actually uses)** — generate a signed URL inside the
container and use it from the host, exactly as a client would:

```bash
cd docker
docker compose exec -T minio mc share download --expire 5m local/validation-documents/probe.txt
curl -s "<the printed URL>"        # → hello, HTTP 200
```

If that returns the file contents from outside the container, the signing, the endpoint and the
port mapping are all correct — which is what the backend's `S3Presigner` depends on. Once the
upload endpoints exist, the same check is done through the API instead: `POST /api/v1/validations`
returns the presigned `PUT` URL, and the bytes go straight to MinIO
(see [`docs/service/upload-flow.md`](docs/service/upload-flow.md)).

## The HTTP API

Base path `/api/v1`, JSON in and out, and **every** endpoint requires the header
`X-Api-Key: <configured value>` (default `local-dev-api-key`, from `security.api-key` in
`application.yml`; override with the `API_KEY` environment variable). Every error is an RFC 7807
Problem Details body (see § Design trade-offs → API error format). The full wire contract lives in
[`docs/service/upload-flow.md`](docs/service/upload-flow.md) and
[`docs/business-rules.md`](docs/business-rules.md).

| Endpoint | Does | Success |
|---|---|---|
| `POST /api/v1/validations` | Registers the intent to validate a document and signs an upload URL | `201` with `{ requestId, status, uploadUrl }` |
| `POST /api/v1/validations/{requestId}/confirm` | Accepts the work: moves `PENDING_UPLOAD → QUEUED` and publishes the processing event | `202` with `{ requestId, status }` |
| `GET /api/v1/validations/{requestId}` | Reads the current status, and the result once it exists | `200` with `{ requestId, status, result? }` |

Errors: `400` (blank/missing `filename` or `contentType`, malformed body — carries an `errors[]`
extension naming the offending fields), `401` (missing or wrong `X-Api-Key`), `404` (unknown
`requestId`), `409` (illegal status transition), `502` (storage backend failure), `500` (anything
unexpected, generic message only).

### Idempotency

`POST /api/v1/validations` accepts an optional `Idempotency-Key` header. Concretely
(see § Design trade-offs → Idempotency-Key concrete rules):

- **Never expires** — a key stays valid as long as its `ValidationRequest` exists.
- **Same key seen again** → the **original** `requestId` and its **current** status; no second
  request is created.
- **Same key, different body** → still the original resource; the new body is neither compared nor
  used.
- **The `uploadUrl` is re-signed on every reply**, including a replay: presigned URLs expire and are
  never persisted, so the client always receives a URL it can actually use, over the same stored
  storage key.

`confirm` needs no key: repeating it is safe by construction. It returns `202` with the current
status and publishes nothing unless the request actually moved out of `PENDING_UPLOAD`.

### Walking the flow

With the infrastructure up (`npm run docker:up`) and the service running (`npm run dev:service`):

```bash
API_KEY=local-dev-api-key

# 1. Create — returns requestId + uploadUrl
curl -sS -X POST http://localhost:8080/api/v1/validations \
  -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-key-1" \
  -d '{"filename":"invoice.pdf","contentType":"application/pdf"}'

# 2. Upload the bytes straight to MinIO — the service never sees them
curl -sS -X PUT "<uploadUrl>" -H "Content-Type: application/pdf" --data-binary @invoice.pdf

# 3. Confirm — accepts the work and returns immediately
curl -sS -X POST http://localhost:8080/api/v1/validations/<requestId>/confirm -H "X-Api-Key: $API_KEY"

# 4. Read — poll until COMPLETED
curl -sS http://localhost:8080/api/v1/validations/<requestId> -H "X-Api-Key: $API_KEY"
```

A PDF between 1 byte and 15 MB ends `COMPLETED` with `verdict: PASS`. Any other content type,
an empty file (including one that was confirmed but never uploaded), or one over 15 MB ends
`COMPLETED` with `verdict: FAIL` and a specific `reason` — a conclusive answer, which is why it is
not the same thing as status `FAILED` (see [`docs/business-rules.md`](docs/business-rules.md) § 2).

## How to build/test the SDK

From the repo root:

```bash
npm run install:sdk    # npm --prefix sdk install
npm run build:sdk      # npm --prefix sdk run build  → dual ESM+CJS+.d.ts via Vite
npm run test:sdk       # npm --prefix sdk run test   → Vitest
```

See [`sdk/README.md`](sdk/README.md) for install/usage as a consumer would use it.

### Root `package.json`

The root `package.json` holds only orchestration scripts (`dev`, `build`, `test`, `docker:up`/`docker:down`) that delegate to `service/` (Gradle) and `sdk/` (npm) — it is not itself a publishable package and has no runtime dependencies of its own, only `concurrently` as a dev dependency to run `docker compose up` + the service + the SDK watch build together under `npm run dev`. See § Design trade-offs → Root package.json and dev orchestration.

## Design trade-offs

This section records the architecture decisions made during design, including the discarded alternative and the actual reason behind the choice (not just "what was done", but "why").

### Async processing mechanism: real Kafka via docker-compose

**Alternatives considered:** real Kafka via Testcontainers/docker-compose, or a custom abstraction (`JobPublisher`/`JobConsumer`) with an in-memory/`@Async` implementation for local mode.

**Chosen:** real Kafka, run as one more service in `docker-compose`.

**Why:**
- Infrastructure consistency: since the local environment already spins up several services via `docker-compose` (Postgres, MinIO), adding Kafka doesn't add real orchestration cost — the whole environment comes up with a single `docker compose up`.
- With no meaningful operational overhead added, we prefer to maximize production fidelity (a real messaging mechanism with a producer/consumer, instead of just a "Kafka-ready" interface) without penalizing the timebox.

_(Initial decision: the custom abstraction was chosen first for simplicity and low effort, since the assignment offers it as an equally valid alternative. It was reversed after deciding to also run MinIO alongside Postgres via docker-compose, since at that point the local environment already requires container orchestration anyway.)_

### Document byte storage: MinIO (S3-compatible) via docker-compose

**Alternatives considered:** local filesystem (store the file on disk/volume and reference it with a storage key), or an S3-compatible stub such as MinIO.

**Chosen:** MinIO, as an additional service in `docker-compose` alongside Postgres.

**Why:**
- Since the project already spins up several services via `docker-compose`, adding MinIO doesn't add real orchestration cost (one more container in the same `docker compose up`).
- It enables a real "presigned URL" flow, faithful to how it would work with S3/AWS in production, instead of simulating it with a custom upload endpoint — stronger signal on backend judgment for the storage/upload-gateway part.

### Document upload flow: real presigned URL (MinIO) + confirm endpoint

**Alternatives considered:** a real presigned URL against MinIO (the client uploads directly to the bucket) with a `.../confirm` endpoint so the backend learns the upload finished, or a custom `PUT .../content` endpoint where the backend acts as a proxy and forwards the bytes to MinIO internally.

**Chosen:** real presigned URL (MinIO) + `POST .../confirm`.

**Why:**
- Consistency with the MinIO decision: the whole point of using MinIO was to be able to issue real presigned URLs; using the backend as a byte proxy would negate that benefit.
- Better architecture: the backend never receives or forwards the file bytes, it only generates the signed URL and reacts to the confirmation — less load and less API surface, closer to a real production design where heavy traffic (the file) never passes through the business service.
- The `confirm` endpoint stops being optional in this design: since the upload happens outside the backend's view (directly to MinIO), it's the only way for the backend to know it can move the status to `QUEUED` and publish the event to Kafka.

### Idempotency strategy: Idempotency-Key header (create) + state-machine guards (confirm/upload)

**Alternatives considered:** a client-supplied `Idempotency-Key` header on `POST /validations` combined with state guards on `confirm`/`upload`, or relying only on state-machine guards (each `POST` always creates a new resource; only the state transitions are made safe to repeat).

**Chosen:** `Idempotency-Key` header on create + state-machine guards on confirm/upload.

**Why:**
- Robustness against real network retries: in a flow with presigned URLs and separate calls (create, confirm), a client timeout/retry is a realistic scenario. Without an `Idempotency-Key`, a retried `POST /validations` would create a duplicate resource; without state guards, a retried `confirm` would re-publish the event to Kafka and cause the document to be processed twice.
- It's explicitly requested by the assignment, which asks for "idempotent create/upload behaviour where it matters (document your rules)" — an explicit `Idempotency-Key` is the standard, easy-to-document way to demonstrate this in the API.

**Rules (to document in the API section once implemented):**
- `POST /api/v1/validations` accepts an optional `Idempotency-Key` header. If a request with the same key was already processed, the endpoint returns the original `requestId` and response instead of creating a new resource.
- `POST /api/v1/validations/{requestId}/confirm` checks the current status before transitioning: if the resource is no longer in `PENDING_UPLOAD` (i.e. `confirm` was already called), it returns success with the current status instead of re-publishing to Kafka.

### Persistence & migrations: JPA/Hibernate + Liquibase

**Alternatives considered:** JPA/Hibernate (code-first entities, DB schema derived from Java classes) vs jOOQ (schema-first via SQL migrations + generated typesafe query code) for the ORM layer; Liquibase vs Flyway for schema migrations.

**Chosen:** JPA/Hibernate for persistence, Liquibase for migrations.

**Why:**
- Prior hands-on experience with both JPA/Hibernate and Liquibase, which reduces execution risk inside a 24h timebox.
- It's the de facto standard for Spring Boot projects, which keeps the codebase approachable for any teammate reviewing it.
- It's explicitly what the assignment asks for ("JPA / Spring Data is fine", "PostgreSQL + Liquibase"), so no deviation needs to be justified.

jOOQ (schema-first codegen, closer to a Prisma-like workflow) was considered but not chosen: it would add an extra dependency and generation step for no additional signal, since the assignment already names JPA/Spring Data as sufficient.

### API error format: RFC 7807 Problem Details

**Alternatives considered:** RFC 7807/9457 Problem Details (`application/problem+json` with `type`/`title`/`status`/`detail`/`instance`), or a custom internal error convention — the assignment explicitly allows either ("Consistent error body (problem details or a small internal convention)").

**Chosen:** RFC 7807 Problem Details.

**Why:**
- Spring Boot 3.x supports it natively via `org.springframework.http.ProblemDetail`, including out-of-the-box enrichment for `jakarta.validation` failures — no custom error class, serializer, or bespoke documentation to write.
- It's a real, recognizable standard rather than an invented shape a reviewer has to learn from scratch by reading the code.
- It fits the SDK requirement of "clear error type(s) with HTTP status + body": the SDK just parses this standard shape (`status`, `title`, `detail`) into its own TypeScript error class, with no custom mapping needed.
- `ProblemDetail` can still be extended with extra properties (e.g. a structured list of field validation errors via `setProperty(...)`) if a specific need arises, so it isn't a limiting choice.

### SDK bundler: Vite (library mode)

**Alternatives considered:** Vite in library mode (`build.lib`, Rollup under the hood), or tsup (a bundler purpose-built for TypeScript libraries, minimal config).

**Chosen:** Vite (library mode).

**Why:**
- It's what the assignment itself suggests ("Vite (or equivalent) producing dual ESM + CJS + .d.ts"), so no deviation needs to be justified.
- Prior hands-on experience with Vite and its ecosystem, which reduces configuration risk inside a 24h timebox.

### Authentication: static API key stub

**Alternatives considered:** no authentication at all (the assignment marks full auth as out of scope and stubs as optional, "if present"), or a minimal static API key stub via header.

**Chosen:** static API key stub (a filter/interceptor requiring a configured `X-Api-Key` header, returning 401 if missing/incorrect).

**Why:**
- It's a small amount of code (a single filter) that adds extra signal on backend judgment — shows security was considered — without diverting meaningful time from the higher-weight parts of the evaluation (async design, SDK).

### Integration testing strategy: H2 in-memory + mocks/fakes for Kafka/MinIO

**Alternatives considered:** Testcontainers (real Postgres/Kafka/MinIO containers spun up per test run), or an in-memory H2 database for repository tests combined with mocks/fakes for the `JobPublisher`/Kafka and MinIO interactions.

**Chosen:** H2 in-memory + mocks/fakes.

**Why:**
- Maximum simplicity for the test suite: no Docker dependency to run tests, faster feedback loop, less moving parts to set up within the 24h timebox.

**Trade-off accepted:** this is a deliberate departure from full production fidelity. H2 is not 100% SQL-compatible with PostgreSQL (dialect differences can hide real bugs, e.g. around Postgres-specific column types or constraints), and mocking Kafka/MinIO means the tests don't exercise the real serialization/network behavior of those integrations — only the business logic around them. Given the 24h timebox, this was accepted in exchange for a simpler, faster, Docker-independent test suite; Testcontainers remains the natural next step ("what I'd do with another day", see below) to close this gap.

### Internal service architecture: hexagonal (ports & adapters) across all integrations

**Alternatives considered:** a traditional layered architecture (`Controller → Service → Repository`, with JPA repositories, `KafkaTemplate`, and the S3 SDK client used directly inside business logic), or a hexagonal architecture where the domain/application layer depends only on its own ports (`ValidationRequestRepository`, `JobPublisher`, `DocumentStoragePort`) and every concrete integration (JPA/Postgres, Kafka, MinIO/S3) is implemented as an adapter behind those ports.

**Chosen:** hexagonal architecture, applied consistently to all three integrations (persistence, messaging, and object storage) — not only to messaging.

**Why:**
- Testability: business/domain logic can be tested against fakes or in-memory implementations of each port, without needing Postgres, Kafka, or MinIO running — independent from (and complementary to) the H2+mocks integration tests, which still exercise the adapters themselves.
- It's a strong, consistent signal on the highest-weight evaluation criteria ("API & domain design", "Async / messaging design: clean boundaries; Kafka-ready thinking"): separating domain from infrastructure across every integration demonstrates that judgment explicitly, rather than only in the messaging piece the assignment calls out by name.

**Note on MinIO/S3 specifically:** MinIO itself already provides a protocol-level abstraction — it implements the S3 API, so the same AWS S3 SDK client (`S3Client`/`S3Presigner`) works unmodified against MinIO, real AWS S3, or any other S3-compatible provider (just by changing endpoint/credentials). That alone is enough to move between S3-compatible backends. It does **not**, however, cover a provider with a fundamentally different API (e.g. Cloudinary, native Google Cloud Storage, Azure Blob Storage, or plain local disk), and it doesn't stop AWS SDK types (`PutObjectPresignRequest`, `S3Presigner`, ...) from leaking into domain/service code if called directly. A dedicated `DocumentStoragePort` was kept anyway, for consistency with the rest of the hexagonal design and so domain-level tests don't depend on any AWS SDK type — not because the S3 protocol itself needed extra abstraction to swap vendors.

### Package organization: layer-first (domain / application / adapter / config)

**Alternatives considered:** organizing packages by layer at the top level (`domain/`, `application/`, `adapter/in/web`, `adapter/out/{persistence,messaging,storage}`, `config/`), or by feature with hexagonal layers nested inside each feature (`validation/{domain,application,adapter}/...`).

**Chosen:** layer-first.

**Why:**
- It's the simplest and most readable option given there is a single domain (validation request) in this slice — feature-first would add nesting with no real benefit right now.
- It also makes the hexagonal architecture visible immediately at the top package level, without a reviewer needing to open a feature folder to see the domain/application/adapter split.

### Gradle module structure: single module

**Alternatives considered:** a single Gradle module for `service/` (hexagonal separation enforced only at the Java package level), or a multi-module Gradle build with `domain`/`application`/`infrastructure` as separate subprojects, each with its own `build.gradle`, so the compiler enforces that `domain` cannot depend on `infrastructure`.

**Chosen:** single module.

**Why:**
- Consistent with the assignment's explicit warning against "incomplete microservices cosplay" and with this being a "thin vertical slice" — multi-module for a single small service would go in the opposite direction.

### Deterministic verdict rule for the processing stub: supported content-type + file size range

**Alternatives considered:** a filename convention (e.g. filename contains "fail" → FAIL), a size-only threshold, or a combination of supported content-type + file size range.

**Chosen:** combination of supported content-type + file size range. Concretely: only `application/pdf` is a supported content-type; a file must be strictly larger than 0 bytes and no larger than 15 MB to pass. Outside either condition, the stub returns `FAIL` with a clear `reason`; otherwise it returns `PASS`.

**Why:**
- It resolves the assignment's two explicit stub requirements ("fail clearly on empty files or unsupported content types" and produce a `PASS`/`FAIL` verdict) with one coherent rule, instead of treating them as unrelated mechanisms.
- Restricting to PDF-only reflects a more realistic business rule than an arbitrary filename convention: business documents (invoices, contracts) are, in practice, almost always PDF.
- The 0–15 MB range is a realistic size boundary for individual business documents (comfortably covers a multi-page scanned PDF) and is trivial to test deterministically (empty file → FAIL, oversized generated file → FAIL, normal PDF → PASS).

### Idempotency-Key concrete rules: no TTL, conflicting body ignored

**Alternatives considered (TTL):** no expiration (the key stays valid for as long as the `ValidationRequest` exists), or a short TTL (e.g. 24h, mirroring how some real APIs like Stripe scope idempotency keys).

**Chosen (TTL):** no expiration.

**Alternatives considered (key reused with a different body):** return the original resource, ignoring the new body; or reject with `409 Conflict` if the new body doesn't match the original (requires storing/comparing the original body or its hash).

**Chosen (conflicting body):** return the original resource, ignoring the new body.

**Why:**
- Simplicity within the 24h timebox: no expiration column, no cleanup job, and no body-comparison logic are needed — less code and less surface for bugs, for a problem (avoiding duplicate resources from network retries) that these simpler rules already solve.
- The scenario being protected against (a client retry after a timeout) happens within seconds/minutes, not days, so an indefinite key is practically equivalent to a short TTL for this use case, without the extra bookkeeping.
- In practice, a reused key with a slightly different body is almost always a retry with some non-deterministic field (a timestamp, an internal client request id), not a genuine new request — returning the original resource matches what the client actually expects.

### Domain model style: rich entity + immutable value objects

**Alternatives considered:** a rich domain model (`ValidationRequest` as a mutable entity with identity, exposing behavior methods — `confirmUpload()`, `startProcessing()`, `complete(result)`, `fail()` — that enforce the status machine internally; `DocumentMetadata`/`ValidationResult` as immutable Java records with no identity), or a fully immutable model (every domain object, including `ValidationRequest`, as a record, with transitions modeled as static factory methods returning a new instance).

**Chosen:** rich entity (`ValidationRequest`) + immutable value objects (`DocumentMetadata`, `ValidationResult`).

**Why:**
- Avoids the "anemic domain" the assignment explicitly names as an AI suggestion to reject — behavior lives inside the entity, not in an external service mutating public fields.
- `ValidationRequest` has identity and persists its state across separate HTTP calls (create, confirm, get); modeling it as a stateful entity with behavior is more natural than reconstructing immutable instances at every step.

### Port naming and not-found handling

**Alternatives considered (naming):** the mixed naming already used in `docs/service/architecture.md` (`ValidationRequestRepository`, `JobPublisher`, `JobConsumer`, `DocumentStoragePort`), or a uniform `Port` suffix on every port interface.

**Chosen (naming):** mixed naming, as already documented in `docs/service/architecture.md`.

**Alternatives considered (not-found):** `Optional<T>` in the port's return type, with the use case deciding what to do with an empty result; or the adapter throwing the domain "not found" exception directly, with the port's return type being the non-optional domain object.

**Chosen (not-found):** the adapter throws the domain exception directly.

**Why:**
- Naming: repository/publisher/consumer names are self-explanatory about the port's role; adding "Port" everywhere is repetitive without adding information.
- Not-found: a missing `requestId` is never a valid business outcome for these lookups (always a 404), so `Optional` would only add boilerplate without real flexibility — centralizing "not found → exception" in the adapter avoids repeating that decision in every use case.

### Adapter naming and domain↔persistence mapping

**Alternatives considered (naming):** a technology prefix (`JpaValidationRequestRepository`, `KafkaJobPublisher`, `S3DocumentStorageAdapter`), or a uniform `Adapter` suffix (`ValidationRequestRepositoryJpaAdapter`, etc.).

**Chosen (naming):** technology prefix.

**Alternatives considered (mapping):** manual mapper methods (`toDomain()`/`toEntity()`) written by hand in each adapter, or MapStruct-generated mapping code.

**Chosen (mapping):** manual mappers.

**Why:**
- Naming: the technology behind an adapter is readable at a glance from its class name, without opening the file — useful for a reviewer moving quickly through the codebase.
- Mapping: manual mapping avoids adding a new dependency and annotation-processor setup to the Gradle build; with a small domain (few fields per object), hand-written mapping is just as fast to write and easier to debug (plain Java, not generated code).

### Application layer: one class per use case

**Alternatives considered:** one class per use case (`CreateValidationUseCase`, `ConfirmUploadUseCase`, `GetValidationUseCase`, `ProcessValidationUseCase`, each with a single public method and only the ports it needs), or a single `ValidationService` with one method per use case.

**Chosen:** one class per use case.

**Why:**
- Isolated testability: each use case is tested with only the fakes of the ports it actually needs, without pulling in dependencies belonging to other use cases or standing up one large service with everything wired in.
- Single responsibility: each class has one clear purpose and input/output, which reads more clearly and scales better than a `ValidationService` that keeps growing with every new use case.

### Error handling: pure domain exceptions + boundary translation to ResponseStatusException/ProblemDetail

**Alternatives considered:** domain exceptions extending Spring's `ResponseStatusException` directly (gets automatic conversion to `ProblemDetail` for free, since any exception implementing Spring's `ErrorResponse` interface — which `ResponseStatusException` does — is converted automatically, but couples the `domain` package to `org.springframework.web.server`); or plain domain exceptions (`RuntimeException` subclasses with zero Spring dependency) with translation to `ResponseStatusException`/`ProblemDetail` happening at the boundary (`adapter/in/web`).

**Chosen:** plain domain exceptions + boundary translation.

**Why:**
- Consistency with the hexagonal-architecture rule already established in `docs/service/architecture.md` § 4.1 and `README.md § Design trade-offs → Internal service architecture` ("domain has no dependency on Spring, JPA, Kafka, or the AWS SDK") — extending a Spring exception class from `domain` would break that rule on its first practical test.
- Keeps domain-level tests (e.g. asserting `ValidationRequest.confirmUpload()` throws on an invalid transition) as pure unit tests with no `org.springframework.*` import and no Spring context.

**Additional rule:** the `ProblemDetail` response is extended with a custom `errors` property — a list of `{ field, message }` — when the failure is a multi-field validation error. This uses `ProblemDetail`'s standard extensibility mechanism (RFC 7807 `properties`) for exactly the case it's designed for, rather than inventing a free-text convention inside `detail`.

### Controllers: domain model exposed directly (no dedicated DTOs)

**Alternatives considered:** dedicated request/response DTOs (records) in `adapter/in/web`, with `jakarta.validation` annotations on the DTOs and an explicit mapper between DTO and domain, keeping `domain/model` free of web/serialization concerns; or exposing `domain/model` classes directly as the request/response bodies, with Jackson (`@JsonProperty`) and `jakarta.validation` annotations placed directly on the domain classes.

**Chosen:** expose `domain/model` directly.

**Why:**
- Less code and less time spent within the 24h timebox: no DTO classes and no manual DTO↔domain mapper to write and maintain for every endpoint.

**Explicitly accepted trade-off:** this is a deliberate, conscious exception to the "domain has no dependency on external frameworks" rule established for the rest of the codebase (see `Internal service architecture` and `Error handling` above, where the same tension was resolved in favor of purity). Here, `domain/model` classes import Jackson and `jakarta.validation` annotations for HTTP serialization/validation purposes. Unlike the error-handling case, this exception was made knowingly in exchange for implementation speed, not reverted — it is called out explicitly so it doesn't read as an inconsistency: the codebase keeps the domain pure everywhere except in this one, acknowledged spot.

### Testing conventions: full pyramid with hand-written fakes for use-case tests

**Alternatives considered:** a full test pyramid — pure JUnit unit tests for the domain (no Spring), use-case tests against hand-written fakes of their ports (e.g. an in-memory `ValidationRequestRepository` implementation), `@WebMvcTest` for controllers, `@DataJpaTest` (H2) for the JPA repository adapter, and plain unit tests with Mockito for the Kafka/storage adapters — or the same pyramid but using Mockito mocks instead of hand-written fakes for the use-case tests.

**Chosen:** full pyramid with hand-written fakes for use-case tests.

**Why:**
- Behavior-focused tests: a fake (e.g. `InMemoryValidationRequestRepository`) lets a test assert on the actual resulting state (what got saved, what status it has) instead of verifying exact interactions with `verify(...)`, making the tests more resistant to internal refactors of the production code that don't change observable behavior.

**Test method naming:** `should_<expectedBehavior>_when_<condition>()`, e.g. `should_throwException_when_confirmingAlreadyQueuedRequest()`.

**Alternatives considered (naming):** `should_expectedBehavior_when_condition()` method names, or short method names paired with a descriptive `@DisplayName`.

**Chosen (naming):** `should_expectedBehavior_when_condition()`.

**Why:**
- Self-explanatory without opening the test body: a reviewer scanning the test list (IDE, CI report) sees exactly what behavior each test covers directly from the method name, with no dependency on remembering to add a `@DisplayName` to every test.

### Event/messaging design: single thin event, no separate "completed" event

**Alternatives considered:** two Kafka topics/events (one for "processing requested", one for "processing completed", mirroring accept/finish as two messages), each carrying either the full document/result payload ("fat" events) or just the `validationRequestId` ("thin" events); or a single "processing requested" event with the completion signal handled entirely by the consumer writing directly to Postgres (no second event needed, since there's no other consumer to notify).

**Chosen:** a single event, `validation.processing-requested`, with a thin payload (`{ validationRequestId }`); the "finish work" side of the accept/finish separation is handled structurally (the Kafka consumer processes and writes the final state to Postgres) rather than via a second published event. Consumption is made idempotent by treating an invalid state transition (e.g. a duplicate delivery arriving after the first was already processed) as a safe no-op rather than an error. Serialization is plain JSON (Spring Kafka's `JsonSerializer`/`JsonDeserializer`), not Avro/Schema Registry.

**Why:**
- Explicit priority given by the user for this round of decisions: favor implementation speed while still meeting the assignment's requirements and keeping the design robust, rather than exploring every option in depth.
- A second "completed" event has no consumer to justify it — nothing else in this slice needs to react to completion — so it would be pure ceremony; the accept/finish separation the assignment asks for is already satisfied structurally (separate code paths: HTTP handler enqueues, Kafka consumer processes).
- A thin payload keeps Postgres as the single source of truth (the consumer always re-reads current state instead of trusting a possibly-stale copy carried in the message) and avoids serializing/versioning a larger domain payload.
- JSON avoids adding Schema Registry infrastructure for a single, simple event shape, which would be disproportionate overhead for a 24h timebox.

Full event catalog and payload schema: `docs/service/events.md`. Kafka-specific configuration (topic, consumer group, serialization, duplicate handling): `docs/service/kafka.md`.

### Upload flow: confirm does not touch storage; file size is discovered during processing

**Alternatives considered:** the `confirm` endpoint calling `DocumentStoragePort.sizeOf(storageKey)` synchronously to discover and persist the real file size before transitioning to `QUEUED`; or `confirm` staying storage-free (pure state transition + event publish) and the Kafka consumer discovering the size itself, right before evaluating the stub verdict rule.

**Chosen:** `confirm` stays storage-free; size discovery happens in the Kafka consumer during processing.

**Why:**
- Explicit priority given by the user for this round: favor implementation speed while meeting the requirements and staying robust. Keeping `confirm` free of storage I/O keeps it a trivial, fast state transition, and avoids `ConfirmUploadUseCase` needing a dependency on `DocumentStoragePort` at all.
- The file size is only ever needed for one thing — the deterministic verdict rule (`docs/business-rules.md` § 5) — which already runs during processing; fetching it there avoids fetching it twice or plumbing it through an extra step.
- Handles the "client confirmed without actually uploading" edge case for free: if the object doesn't exist in MinIO when the consumer checks, that's treated as size `0`, which already falls under the existing "empty file → FAIL" rule — no special-case code needed.

Full sequence, wire shapes, and edge cases: `docs/service/upload-flow.md`.

### SDK implementation approach: hand-written vs generated from OpenAPI

**Alternatives considered:** generating the SDK (types and/or client) from an OpenAPI spec of the backend using a tool such as `openapi-typescript`, `orval`, or `openapi-generator`; a hybrid where only the request/response types are generated and the client stays hand-written; or writing the whole SDK by hand, with no code generation step at all.

**Chosen:** fully hand-written, no code generation.

**Why:**
- Explicit priority given by the user for this round: favor implementation speed within the ~24h timebox. Code generation adds a tool to the pipeline (a maintained OpenAPI spec, a generator dependency, a generation step wired into the build) for a slice with a public surface of five methods — disproportionate overhead here.
- The assignment lists OpenAPI/Swagger as "optional" polish, not a requirement, so skipping it doesn't cost anything against the stated evaluation criteria.
- Writing it by hand gives full control over the ergonomics of the public API (naming, typed error shape, polling helper) exactly as specified by the assignment's "minimum SDK surface", rather than accepting whatever shape a generator produces.

### SDK client architecture: factory function + native `fetch` + typed error mirroring the backend

**Alternatives considered:** a `createClient(options)` factory function returning a plain object of bound methods (closures over `baseUrl`/`headers`), or an OOP design with two classes — a `HttpClient` class encapsulating `fetch` + header merging + error parsing, and a `ValidationClient` class built on top of it exposing `startValidation`/`upload`/`getValidation`/`waitForCompletion`, with `createClient(options)` kept as a thin public facade returning `new ValidationClient(...)`; `axios`/`node-fetch` as the HTTP layer, or Node's native `fetch` (available globally since Node 18); a generic/untyped error, or a dedicated `ValidationApiError` carrying the HTTP status and the parsed Problem Details body; request/response types hand-designed independently of the backend, or types that mirror the backend's actual JSON shape (which — per `README.md § Design trade-offs → Controllers` — is `domain/model` serialized directly, not a separate DTO contract).

**Chosen:** factory function returning a plain object of closures (not classes), native `fetch`, dedicated `ValidationApiError`, types mirroring the backend's actual response shape.

**Why:**
- The two-class design was discussed explicitly and reconsidered before confirming the original choice. The deciding reason: the assignment's own "minimum SDK surface" sketch is written literally as `createClient(options)` → `client.startValidation(...)` — the shape of a factory returning a plain object, not `new Client(options)`. Matching that sketch as closely as possible was the tie-breaker.
- Secondary reasons surfaced during the discussion, in favor of keeping closures over classes: no `this`-binding failure mode (a class method loses `this` if passed around or destructured unless written as an arrow-function class field — closures can't have this bug structurally); less code for the timebox (no constructor, no separate `HttpClient` class whose only consumer would be `ValidationClient`, no `this.http.request(...)` indirection); simpler test mocking (`vi.fn()` on a plain object vs. deciding how to mock a class instance/prototype).
- A factory function also avoids `axios`/`node-fetch` as dependencies (Node 18+ ships `fetch` globally).
- A dedicated error type gives SDK consumers a single place to inspect `status` and the Problem Details body (`title`, `detail`, `errors`), directly reusing the shape already defined in `docs/service/code_rules.md` § 5, so the SDK doesn't invent a second error convention.

### SDK polling: exponential backoff + `AbortSignal`, no external retry library

**Alternatives considered:** a fixed polling interval for `waitForCompletion`, or exponential backoff with a max-attempts/timeout; implementing it with a dependency like `p-retry`, or a small hand-written loop using `setTimeout`.

**Chosen:** exponential backoff with a configurable timeout, hand-written (no `p-retry`), with `AbortSignal` support so a caller can cancel an in-flight wait.

**Why:**
- Explicit priority given by the user for this round: favor implementation speed while meeting the requirements. A short hand-written loop is simpler to reason about than pulling in a retry library for one call site, and covers the "optional polish" (retry/backoff, abort signal) the assignment calls out as valued.

### SDK testing: Vitest with `fetch` mocked directly, no MSW

**Alternatives considered:** Mock Service Worker (MSW) to intercept HTTP calls at the network layer, or mocking the global `fetch` directly (`vi.stubGlobal('fetch', ...)`).

**Chosen:** mock `fetch` directly.

**Why:**
- Same "fewer dependencies within the timebox" reasoning already applied on the backend side (see `Integration testing strategy` above) — MSW is a well-regarded tool, but adds a dependency and setup for a client this small, where a stubbed `fetch` per test is already simple and sufficient.

### SDK module format: pure ESM source, dual build only at the Vite output boundary

**Alternatives considered:** allowing whichever module syntax feels convenient at each source file, relying on transpilation to sort it out, or enforcing ESM-only syntax (`import`/`export`) throughout `src/`, with CJS output produced exclusively by Vite's build step, never hand-written.

**Chosen:** ESM-only source; the dual ESM/CJS output is exclusively Vite's responsibility.

**Why:**
- The assignment explicitly names "wrong module format" as an example of an AI suggestion to reject in `AI_USAGE.md`; enforcing ESM-only source is a direct, concrete guard against exactly that failure mode, rather than leaving it to be caught in review.

### Root package.json and dev orchestration

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

### Toolchain setup: documented prerequisites + `.nvmrc`, no Makefile

**Alternatives considered:** a Makefile with a `make setup`/`make install-deps` target that installs Java and Node via the OS package manager (`brew`, `apt`, ...); or documenting the required toolchain versions in the README's § Prerequisites and letting each developer install them with their own tool of choice (`sdkman`, `brew`, `asdf`, `nvm`, the official installers), with `.nvmrc` pinning the recommended Node version for `nvm` users.

**Chosen:** documented prerequisites + `.nvmrc`, no Makefile.

**Why:**
- Installing runtimes themselves (as opposed to project dependencies) is OS-specific — the install command for Java/Node differs between macOS, Debian/Ubuntu, and Windows — so a `make setup` target would need per-OS branches to be reliable, and would likely fail silently on whatever OS wasn't anticipated. That's exactly the kind of extra infrastructure the assignment asks to avoid ("quality over quantity"; Terraform/ECS-style tooling is explicitly out of scope).
- `package.json` was already chosen as the single orchestration entrypoint for this repo (see above); a Makefile that just aliased the same npm scripts (`make dev` → `npm run dev`) would be a second entrypoint doing the same job, working against the simplicity that decision was made for.
- `.nvmrc` is the standard, zero-execution way to pin a Node version — it only declares a version for `nvm`/`fnm`/`asdf` to read, so it carries none of the cross-platform risk a Makefile install step would.

## What I'd do with another day

_(pending)_

## AI usage

See `AI_USAGE.md`.
