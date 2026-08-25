# Architecture (service)

Status: living document. This file lives under `docs/service/` — it is specific to the backend (`service/`). The SDK has its own equivalent, `docs/sdk/architecture.md`. This file describes the current state of the service's architecture and structure (the "what" and "how"). It does not repeat the reasoning behind each choice — the rationale, discarded alternatives, and trade-offs for every decision referenced here live in `README.md § Design trade-offs`, indexed by the same names used in this document.

## 1. Purpose of this document

This document is the single source of truth for:
- the backend's architecture (style, layers, boundaries)
- the internal package structure of `service/`
- how `service/` relates to the local infrastructure (`docker/`)

It intentionally does NOT cover (see "Related documents" below for where each topic lives instead):
- what the system does end-to-end, from a user/client perspective (the request/response narrative) → `README.md`
- the top-level repository layout (which files/folders exist at the repo root) → `README.md`
- the SDK's architecture and structure → `docs/sdk/architecture.md`
- business rules and validation logic (shared between service and SDK) → `docs/business-rules.md`
- implementation-level coding conventions (how classes are actually written, with examples) → `docs/service/code_rules.md`
- the event catalog and Kafka topic/message design → `docs/service/events.md` and `docs/service/kafka.md`
- the exact upload/presigned-URL sequence → `docs/service/upload-flow.md`
- why a decision was made over its alternatives → `README.md § Design trade-offs`

## 2. Related documents

| Document | Content |
|---|---|
| `README.md` | Project overview, how to run, § Design trade-offs (decision + alternatives + rationale) |
| `docs/business-rules.md` | Domain/business rules — shared between `service/` and `sdk/` |
| `docs/service/architecture.md` | This file — backend architecture and structure |
| `docs/service/code_rules.md` | Backend implementation-level coding conventions per layer, with examples |
| `docs/service/events.md` | Event catalog: names, payloads, producers, consumers |
| `docs/service/kafka.md` | Kafka-specific implementation: topics, partitioning, consumer groups, serialization |
| `docs/service/upload-flow.md` | Step-by-step document upload sequence (presigned URL + confirm) |
| `docs/sdk/architecture.md` | SDK architecture and structure |
| `docs/sdk/code_rules.md` | SDK implementation-level coding conventions |
| `AI_USAGE.md` | AI tool usage disclosure required by the assessment |

## 3. Local infrastructure (`docker/`)

Every Docker asset in this repository lives under `docker/` — no compose file, `Dockerfile`, or Docker-related script exists anywhere else, and any Docker asset added in the future goes there too.

```
docker/
├── docker-compose.yml   # the three local dependencies + the bucket bootstrap
├── .env                 # local defaults: ports, credentials, database and bucket names
└── README.md            # start / logs / stop / reset, pointing back to this section
```

`docker/docker-compose.yml` brings up every dependency the service needs locally, as a single `docker compose up`. The service itself is **not** containerized: it runs from the IDE or from `./gradlew bootRun` on the host and connects to these containers.

| Service | Role | Local endpoint | Persisted in | Talks to it via |
|---|---|---|---|---|
| `postgres` | Primary datastore for validation request state | `localhost:5432` | volume `postgres-data` | JPA / Spring Data (service) |
| `kafka` | Async messaging broker: decouples "accept work" from "finish work" | `localhost:9092` | volume `kafka-data` | Spring Kafka (service) |
| `minio` | S3-compatible object storage for uploaded document bytes | `localhost:9000` (API), `localhost:9001` (console) | volume `minio-data` | AWS S3 SDK (`S3Client` / `S3Presigner`) (service) |

Kafka runs in KRaft mode, so there is no ZooKeeper container. Each of the three services declares a healthcheck, so readiness is observable rather than guessed.

A fourth, short-lived container (`minio-init`, MinIO's own `mc` CLI) waits for `minio` to report healthy, creates the `validation-documents` bucket if it does not exist, and exits. It is a bootstrap step for the object storage — not a fourth dependency — and it exists so that `docker compose up` needs no manual follow-up before the first presigned upload. No Kafka topic bootstrap is needed: see `docs/service/kafka.md` § 5.

### 3.1 Configuration

Ports, credentials, the database name and the bucket name come from `docker/.env` and are read by the compose file through `${VAR:-default}` interpolation, so any of them can be overridden without editing `docker-compose.yml`. The values committed there are development defaults, not secrets. `service/src/main/resources/application.yml` reads the same environment variables with the same defaults, so the service points at this environment out of the box.

### 3.2 Operating it

| Intent | Command (run from `docker/`) |
|---|---|
| Start everything | `docker compose up -d` |
| Follow logs | `docker compose logs -f` |
| Stop, keep data | `docker compose down` |
| Stop and wipe all local state | `docker compose down -v` |

Because each service's state lives in a named volume, `docker compose down` followed by `docker compose up -d` preserves the data; only `down -v` resets it.

See `README.md § Design trade-offs` for why each of these was chosen as a real dependency instead of a lighter-weight substitute.

## 4. Backend architecture (`service/`)

### 4.1 Architectural style: Hexagonal (ports & adapters)

The service follows hexagonal architecture, applied consistently across all three external integrations (persistence, messaging, storage) — not only messaging.

Dependency rule: dependencies point inward, never outward.

```
adapter (in/web, out/persistence, out/messaging, out/storage)
        │
        ▼ (adapters depend on application + domain)
   application
        │
        ▼ (application depends on domain)
     domain
```

- `domain` has no dependency on Spring, JPA, Kafka, or the AWS SDK. It defines the model and the ports (interfaces) it needs from the outside world. (One explicit, documented exception: see `README.md § Design trade-offs → Controllers`.)
- `application` orchestrates domain objects and ports to implement use cases. It depends on `domain` only.
- `adapter` implements the inbound entrypoint (`in/web`, REST controllers) and the outbound ports (`out/persistence`, `out/messaging`, `out/storage`) using concrete frameworks/libraries. Adapters depend on `application` and `domain`, never the other way around.
- `config` wires adapters to ports (Spring `@Bean` / `@Configuration` classes) and holds cross-cutting Spring configuration (security filter, Kafka config, S3 client config).

Module structure: single Gradle module. The hexagonal boundaries are enforced at the Java package level, not via separate Gradle subprojects.

### 4.2 Package structure

Package organization is layer-first (not feature-first), since this slice has a single domain (validation request).

```
service/
├── build.gradle
├── settings.gradle
└── src/
    ├── main/
    │   ├── java/<base-package>/
    │   │   ├── domain/
    │   │   │   ├── model/        # ValidationRequest, DocumentMetadata, ValidationStatus, ValidationResult
    │   │   │   └── port/         # ValidationRequestRepository, JobPublisher, JobConsumer, DocumentStoragePort
    │   │   ├── application/      # Use cases: CreateValidation, ConfirmUpload, GetValidation, ProcessValidation
    │   │   ├── adapter/
    │   │   │   ├── in/
    │   │   │   │   └── web/      # ValidationController, @RestControllerAdvice (Problem Details mapping)
    │   │   │   └── out/
    │   │   │       ├── persistence/  # JPA entities, Spring Data repositories, adapter implementing ValidationRequestRepository
    │   │   │       ├── messaging/    # Kafka producer/listener, adapter implementing JobPublisher / JobConsumer
    │   │   │       └── storage/      # S3Client/S3Presigner usage, adapter implementing DocumentStoragePort
    │   │   └── config/           # Spring wiring, API key filter, Kafka config, S3 client config
    │   └── resources/
    │       ├── application.yml
    │       └── db/changelog/     # Liquibase changelogs
    └── test/
        └── java/<base-package>/
            ├── domain/            # pure unit tests, no Spring context
            ├── application/       # use case tests against fake ports
            └── adapter/
                ├── in/web/        # @WebMvcTest controller tests
                └── out/
                    ├── persistence/  # @DataJpaTest against H2
                    ├── messaging/    # tests against a mocked/fake Kafka adapter
                    └── storage/      # tests against a mocked/fake storage adapter
```

`<base-package>` is `com.nalanda.validation`, fixed when the Gradle project was scaffolded. The project targets Java 21 with Spring Boot 3.5.x, built by the Gradle wrapper checked in under `service/`.

### 4.3 Ports and adapters

| Port (interface, in `domain/port`) | Adapter (implementation, in `adapter/out/...`) | Backing technology |
|---|---|---|
| `ValidationRequestRepository` | `adapter/out/persistence` | Spring Data JPA + PostgreSQL |
| `JobPublisher` | `adapter/out/messaging` | Spring Kafka producer |
| `JobConsumer` (or a `@KafkaListener`-based adapter invoking an application use case) | `adapter/out/messaging` | Spring Kafka consumer |
| `DocumentStoragePort` | `adapter/out/storage` | AWS S3 SDK (`S3Client`, `S3Presigner`) against MinIO |

Full event names, topics, and payload shapes are defined in `docs/service/events.md` and `docs/service/kafka.md`, not here.

### 4.4 API layer

- REST, versioned under `/api/v1`.
- Inbound entrypoint: `adapter/in/web` (controllers depend on `application` use cases, never directly on `domain/port` implementations or other adapters).
- Errors: RFC 7807 Problem Details (`ProblemDetail`), mapped centrally in a `@RestControllerAdvice`.
- Auth: a stub filter requiring a static `X-Api-Key` header, implemented in `config`/`adapter/in/web`.
- Idempotency: `Idempotency-Key` header handling lives in the `application` layer (the use case checks/stores the key via `ValidationRequestRepository`), not in the controller.

The exact endpoints, request/response shapes, and status machine are defined in `docs/business-rules.md` and `docs/service/upload-flow.md`. Concrete code patterns (domain model, ports, adapters, error handling, controllers, testing) are defined in `docs/service/code_rules.md`.

### 4.5 Persistence

- PostgreSQL, schema-managed via Liquibase changelogs under `src/main/resources/db/changelog/`.
- JPA/Hibernate entities live in `adapter/out/persistence`, separate from the `domain/model` classes — the domain model is not annotated with `@Entity`, keeping the domain layer framework-free.
- Table names are namespaced (e.g. `validation_*`), per the "per-service schema mindset" convention.

## 5. Cross-cutting architectural decisions (index)

The table below is an index into `README.md § Design trade-offs`, where the discarded alternatives and the reasoning for each decision are recorded. This document only states the current, chosen state. SDK-specific decisions (e.g. the SDK bundler) are indexed in `docs/sdk/architecture.md` instead.

| Concern | Chosen approach |
|---|---|
| Async processing | Real Kafka via `docker-compose` |
| Document storage | MinIO (S3-compatible) via `docker-compose` |
| Upload flow | Real presigned URL (MinIO) + `POST .../confirm` |
| Idempotency | `Idempotency-Key` header (create) + state-machine guards (confirm/upload) |
| Persistence & migrations | JPA/Hibernate + Liquibase |
| API error format | RFC 7807 Problem Details |
| Authentication | Static API key stub (`X-Api-Key` header) |
| Integration testing | H2 in-memory + mocks/fakes for Kafka/MinIO |
| Internal service architecture | Hexagonal (ports & adapters), applied to all 3 integrations |
| Package organization | Layer-first (`domain/application/adapter/config`) |
| Gradle module structure | Single module |

## 6. Explicit out-of-scope (per assignment)

- Real AWS MSK IAM, ECS, Terraform.
- A real PDF.js viewer or Amplitude integration.
- Full authentication (API key/JWT stubs are enough).
- Multi-tenant product surface.
- Pixel-perfect UI (no UI is being built — this is an API + SDK slice).
