# Phase 0 — Research: Project skeleton and `docker/` local environment

All open questions from the Technical Context are resolved below. Nothing here re-opens a
decision already recorded in `README.md § Design trade-offs`; where a decision exists, it is
adopted as-is and only the concrete version/image is chosen.

## R1 — Location of the Docker assets

- **Decision**: a top-level `docker/` folder holding `docker-compose.yml`, `.env` and a short
  `README.md`. No Docker asset anywhere else in the repository.
- **Rationale**: explicit user requirement. It also keeps the repository root readable and gives
  a single, obvious home for any future Docker asset.
- **Alternatives considered**: compose file at the repository root (the current documented state —
  rejected by the user's instruction); `infra/` or `deploy/` as the folder name (rejected: the user
  named the folder).
- **Consequence**: `docs/service/architecture.md` § 3, `README.md` (repo layout + how to run),
  `docs/service/kafka.md` § 5 and constitution IX all state "root" today and must be updated in
  this change.

## R2 — Kafka image and mode

- **Decision**: `apache/kafka:3.9.0` running in **KRaft** mode, single node, single broker.
- **Rationale**: KRaft removes ZooKeeper, so the local environment stays at exactly three
  long-running services, matching FR-002. `apache/kafka` is the Apache-published image, so no
  third-party redistribution is introduced.
- **Alternatives considered**: `confluentinc/cp-kafka` + `cp-zookeeper` (rejected: a fourth
  container and a deprecated topology); `bitnami/kafka` (rejected: the Bitnami catalog changed
  distribution terms in 2025, adding avoidable churn).
- **Topic creation**: none needed — `docs/service/kafka.md` § 5 already relies on
  `auto.create.topics.enable` for `validation.processing-requested` in this slice, which is the
  image's default. No topic bootstrap step is added.

## R3 — Bucket bootstrap for MinIO

- **Decision**: `minio/minio` for the server plus a one-shot `minio/mc` container that waits for
  the server, creates the `validation-documents` bucket if missing, and exits (`restart: "no"`).
- **Rationale**: FR-004 forbids a manual provisioning step, and the presigned `PUT` in
  `docs/service/upload-flow.md` § 2.2 targets an existing bucket. The official MinIO server image
  offers no "default bucket" option, so a bootstrap step is unavoidable; `mc` is MinIO's own CLI,
  not a new piece of infrastructure.
- **Alternatives considered**: creating the bucket by hand in the MinIO console (rejected: breaks
  FR-004 and SC-001); an image with a `DEFAULT_BUCKETS` env var (rejected: swaps a maintained
  official image for a less predictable one); creating the bucket lazily from the backend at
  startup (rejected: puts infrastructure provisioning inside the application, and the backend is
  meant to be startable against any pre-provisioned S3-compatible storage).

## R4 — Persistence of local state

- **Decision**: one named volume per service — `postgres-data`, `kafka-data`, `minio-data` —
  declared in the compose file's `volumes:` block.
- **Rationale**: FR-003/SC-002. Named volumes survive `docker compose down` and are removed
  explicitly with `docker compose down -v`, which gives the documented "reset" action.
- **Alternatives considered**: bind mounts into `docker/data/` (rejected: leaks
  root-owned files into the working tree and pollutes `git status`); no volumes (rejected: fails
  FR-003).

## R5 — Configuration override mechanism

- **Decision**: a committed `docker/.env` holding development defaults (ports, credentials, bucket
  name, database name), consumed by the compose file through `${VAR:-default}` interpolation.
- **Rationale**: FR-006 requires overridable values with working defaults, and constitution VIII
  explicitly prescribes "local values live in compose/env files with safe defaults". Committing the
  file is what makes `docker compose up` work with zero steps on a clean clone.
- **Alternatives considered**: `.env.example` + a manual copy step (rejected: breaks FR-004's
  "no manual pre-step"); hardcoded values in the compose file (rejected: breaks FR-006).
- **Note**: these are development-only credentials, not secrets. No real credential is ever
  committed.

## R6 — Health signals and startup ordering

- **Decision**: every long-running service declares a `healthcheck`; the `mc` bootstrap declares
  `depends_on: minio: condition: service_healthy`.
- **Rationale**: FR-005 and the "backend started before dependencies are ready" edge case. It also
  makes the bootstrap deterministic instead of relying on a sleep.
- **Alternatives considered**: `depends_on` without conditions (rejected: only orders container
  *start*, not readiness); a retry loop inside the bootstrap (rejected: reimplements what
  `healthcheck` already provides).

## R7 — Backend build toolchain

- **Decision**: Java 21, Spring Boot 3.5.x, Gradle wrapper (Groovy DSL, `build.gradle` /
  `settings.gradle`), single module. The skeleton is generated from Spring Initializr so the
  wrapper is the official one.
- **Rationale**: `docs/service/architecture.md` § 4.2 names `build.gradle`/`settings.gradle`
  explicitly (Groovy DSL, single module). Java 21 is the current LTS and is the JDK installed
  locally. Generating from Initializr avoids hand-crafting a `gradle-wrapper.jar`.
- **Starters pulled**: `web`, `validation`, `data-jpa`, `kafka`, `liquibase`, `postgresql`,
  `h2` (test) — every one of them already named in the docs. `software.amazon.awssdk:s3` is added
  manually for the storage adapter, per `docs/service/architecture.md` § 4.3.
- **Alternatives considered**: Maven (rejected: the docs name Gradle files); Kotlin DSL (rejected:
  the docs name `build.gradle`, not `build.gradle.kts`); a hand-written wrapper (rejected: the
  wrapper JAR is a binary that should come from the official generator).

## R8 — SDK build toolchain

- **Decision**: TypeScript 5.x with `"strict": true`, Vite 6 in library mode emitting ESM + CJS +
  `.d.ts`, Vitest for tests, zero runtime dependencies.
- **Rationale**: fixed by `docs/sdk/architecture.md` § 3/§ 6 and `docs/sdk/code_rules.md` § 6/§ 7.
  Nothing here is a new decision — only the versions are chosen.
- **Alternatives considered**: none; the bundler, the test runner and the module strategy are all
  settled decisions in `README.md § Design trade-offs`.

## R9 — Scope of the skeleton's code

- **Decision**: the skeleton contains the Spring Boot application class, an empty Liquibase master
  changelog, an `application.yml` pointing at the `docker/` endpoints, and — on the SDK side — the
  documented files with placeholder-but-typed content that compiles. Package directories that will
  hold business code are created with `.gitkeep`.
- **Rationale**: FR-007/FR-008 demand a buildable skeleton, and FR-013 forbids inventing scope. A
  file that declares a fake endpoint or a fake domain type would violate FR-013 and constitution II.
- **Alternatives considered**: generating stub use cases and controllers "to save time later"
  (rejected: invented scope); creating no directories at all (rejected: FR-007 requires the
  documented tree to exist).
