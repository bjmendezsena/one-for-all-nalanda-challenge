---
description: 'Task list for feature 001-project-skeleton-docker'
---

# Tasks: Project skeleton and `docker/` local environment

**Input**: Design documents from `/specs/001-project-skeleton-docker/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: This feature contains **no business logic**, so there is no behavior to unit-test.
The completion criterion is that each artifact's test task runs and passes on the empty skeleton
(`./gradlew build`, `npm test`), proving the toolchain is wired correctly. No placeholder test
asserting a fake behavior is written (constitution II, FR-013).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1 = local environment, US2 = buildable skeletons, US3 = documentation

## Path Conventions

- Docker assets: `docker/` (new — every Docker file lives here and nowhere else)
- Service: `service/src/main/java/com/nalanda/validation/...`, tests in `service/src/test/java/...`
- SDK: `sdk/src/...`, tests in `sdk/tests/`
- Docs: `docs/service/architecture.md`, `README.md`, `.specify/memory/constitution.md`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: create the folders the rest of the work writes into, and confirm the toolchain.

- [x] T001 Create the `docker/` folder at the repository root
- [x] T002 [P] Verify the local toolchain meets the plan: Java 21 (`java -version`), Node 20+ (`node -v`), Docker Compose v2+ (`docker compose version`)
- [x] T003 [P] Add a repository-root `.gitignore` covering `service/build/`, `service/.gradle/`, `sdk/node_modules/`, `sdk/dist/`, and `.DS_Store`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: fix the configuration values every later task depends on. Nothing here is
story-specific; US1 and US2 both read these values.

- [x] T004 Create `docker/.env` with the development defaults from `data-model.md` § "Local environment configuration" (`POSTGRES_DB/USER/PASSWORD/PORT`, `KAFKA_PORT`, `MINIO_ROOT_USER/PASSWORD`, `MINIO_API_PORT`, `MINIO_CONSOLE_PORT`, `MINIO_BUCKET`), with a header comment stating these are development-only values

**Checkpoint**: the configuration contract in `contracts/local-environment.md` is now pinned.

---

## Phase 3: User Story 1 — Bring up the whole local environment with one command (Priority: P1) 🎯 MVP

**Goal**: `cd docker && docker compose up -d` starts `postgres`, `kafka` and `minio`, all healthy,
with persistent named volumes and the `validation-documents` bucket already created.

**Independent test**: run the command on a clean clone; all three services report healthy and the
`minio-init` bootstrap exits `0`. Write data, `docker compose down`, `docker compose up -d`, read
it back.

- [x] T005 [US1] Create `docker/docker-compose.yml` and define the `postgres` service: image `postgres:16-alpine`, env from `docker/.env` via `${VAR:-default}` interpolation, port mapping `${POSTGRES_PORT:-5432}:5432`, named volume `postgres-data:/var/lib/postgresql/data`, and a `pg_isready` healthcheck
- [x] T006 [US1] Add the `kafka` service to `docker/docker-compose.yml`: image `apache/kafka:3.9.0` in single-node KRaft mode (no ZooKeeper), listeners exposing `${KAFKA_PORT:-9092}` to the host and an internal controller listener, named volume `kafka-data:/var/lib/kafka/data`, and a broker-readiness healthcheck
- [x] T007 [US1] Add the `minio` service to `docker/docker-compose.yml`: image `minio/minio`, `server /data --console-address ":9001"`, root credentials from `docker/.env`, port mappings for `${MINIO_API_PORT:-9000}` and `${MINIO_CONSOLE_PORT:-9001}`, named volume `minio-data:/data`, and the `/minio/health/live` healthcheck
- [x] T008 [US1] Add the one-shot `minio-init` bootstrap to `docker/docker-compose.yml`: image `minio/mc`, `depends_on: minio: condition: service_healthy`, `restart: "no"`, creating `${MINIO_BUCKET}` idempotently (`mc mb --ignore-existing`) and exiting
- [x] T009 [US1] Declare the `postgres-data`, `kafka-data` and `minio-data` named volumes in the compose file's top-level `volumes:` block
- [x] T010 [US1] Verify US1 end to end: `cd docker && docker compose up -d`, confirm all three services reach `healthy` and `minio-init` exited `0`, confirm the bucket exists, then `docker compose down` + `docker compose up -d` and confirm the Postgres data survived

**Checkpoint**: the local environment is usable on its own — US1 is a shippable increment.

---

## Phase 4: User Story 2 — Buildable skeletons matching the documented architecture (Priority: P2)

**Goal**: `service/` and `sdk/` exist with exactly the structure their architecture documents
describe, both build and both test tasks pass with no business logic present.

**Independent test**: on a clean clone, `cd service && ./gradlew build` and
`cd sdk && npm install && npm test` both succeed.

### Backend (`service/`)

- [x] T011 [US2] Generate the Spring Boot skeleton into `service/` (Gradle Groovy DSL, Java 21, Spring Boot 3.5.x, group `com.nalanda`, base package `com.nalanda.validation`) with starters `web`, `validation`, `data-jpa`, `kafka`, `liquibase`, `postgresql` and `h2` (test scope) — every one already named in `docs/service/architecture.md`
- [x] T012 [US2] Add the `software.amazon.awssdk:s3` dependency to `service/build.gradle` (required by the `DocumentStoragePort` adapter, `docs/service/architecture.md` § 4.3) and pin the AWS SDK BOM
- [x] T013 [P] [US2] Create the main package tree under `service/src/main/java/com/nalanda/validation/` exactly as `docs/service/architecture.md` § 4.2 defines — `domain/model`, `domain/port`, `application`, `adapter/in/web`, `adapter/out/persistence`, `adapter/out/messaging`, `adapter/out/storage`, `config` — each empty directory kept with a `.gitkeep`. Add no class beyond the generated `ValidationServiceApplication`
- [x] T014 [P] [US2] Create the test package tree under `service/src/test/java/com/nalanda/validation/` exactly as `docs/service/architecture.md` § 4.2 defines — `domain`, `application`, `adapter/in/web`, `adapter/out/persistence`, `adapter/out/messaging`, `adapter/out/storage` — each empty directory kept with a `.gitkeep`
- [x] T015 [US2] Write `service/src/main/resources/application.yml` with the local values from `data-model.md` § "Backend configuration mapping": datasource against `localhost:${POSTGRES_PORT}`, `spring.kafka.bootstrap-servers`, consumer group `validation-service`, the `JsonSerializer`/`JsonDeserializer` settings from `docs/service/kafka.md` § 3, the Liquibase changelog path, and the storage endpoint/bucket/credentials. Every value reads an environment variable with the `docker/.env` default as its fallback
- [x] T016 [P] [US2] Create the empty Liquibase master changelog at `service/src/main/resources/db/changelog/db.changelog-master.yaml` (a `databaseChangeLog` with no changeset yet — the schema is added by the feature that needs it)
- [x] T017 [P] [US2] Create `service/src/test/resources/application-test.yml` configuring the H2 in-memory datasource for tests, per `README.md § Design trade-offs → Integration testing strategy` (no Testcontainers)
- [x] T018 [US2] Make the generated `service/src/test/java/com/nalanda/validation/ValidationServiceApplicationTests.java` pass without a running database, broker or storage — the empty skeleton must build green offline
- [x] T019 [US2] Verify the backend: `cd service && ./gradlew build` succeeds

### SDK (`sdk/`)

- [x] T020 [P] [US2] Create `sdk/package.json`: name, version `0.1.0`, `"type": "module"`, an `exports` map covering both `import` and `require` plus `types`, scripts `build`/`test`/`typecheck`, **zero runtime dependencies**, and dev dependencies `typescript`, `vite`, `vitest` only
- [x] T021 [P] [US2] Create `sdk/tsconfig.json` with `"strict": true` (never relaxed, `docs/sdk/code_rules.md` § 9), ESNext modules, `declaration` output
- [x] T022 [P] [US2] Create `sdk/vite.config.ts` in library mode emitting ESM + CJS + `.d.ts` from `src/index.ts`, per `docs/sdk/architecture.md` § 3
- [x] T023 [US2] Create the source files named in `docs/sdk/architecture.md` § 3 — `sdk/src/index.ts`, `sdk/src/client.ts`, `sdk/src/types.ts`, `sdk/src/errors.ts`, `sdk/src/internal/http.ts` — as compiling placeholders that declare no endpoint, no DTO field and no business rule. `src/index.ts` re-exports only the public surface; `src/internal/*` is never re-exported
- [x] T024 [P] [US2] Create `sdk/tests/` and `sdk/examples/` (kept with `.gitkeep` while empty), plus `sdk/README.md` (install + usage placeholder) and `sdk/CHANGELOG.md` starting at `0.1.0`
- [x] T025 [US2] Verify the SDK: `cd sdk && npm install && npm test && npm run build` all succeed and `dist/` contains the ESM, CJS and `.d.ts` outputs

**Checkpoint**: both artifacts build green from a clean clone — US2 is a shippable increment.

---

## Phase 5: User Story 3 — Document the environment in `docs/` (Priority: P3)

**Goal**: no document anywhere still places the compose file at the repository root, and the
backend architecture document fully describes `docker/`.

**Independent test**: start, use and reset the environment following only the documentation;
`grep -rn "docker-compose" README.md docs .specify` shows only `docker/`-relative references.

- [x] T026 [US3] Update `docs/service/architecture.md` § 1 (the bullet naming the local-infrastructure artifact) and rewrite § 3 "Local infrastructure": retitle it around `docker/`, list the folder's contents (`docker-compose.yml`, `.env`, `README.md`), extend the service table with each service's local endpoint and its named volume, note the one-shot bucket bootstrap, state the start/stop/reset commands, and state the rule that every future Docker asset lives under `docker/`
- [x] T027 [P] [US3] Update `docs/service/kafka.md` § 5: the broker runs from `docker/docker-compose.yml`, not a root compose file (additive correction only — no other content touched)
- [x] T028 [P] [US3] Update `README.md` § Repository layout: replace the root `docker-compose.yml` entry with the `docker/` folder and add `service/`, `sdk/`, `specs/` and `.specify/` to the tree as they now exist
- [x] T029 [US3] Fill `README.md` § "How to run the service + DB" and § "How to build/test the SDK" with the commands from `quickstart.md` (they are currently `_(pending)_`)
- [x] T030 [P] [US3] Create `docker/README.md`: what each service is, its local endpoint and credentials, where its data lives, and the start/logs/stop/reset commands — pointing to `docs/service/architecture.md` § 3 as the authoritative description
- [x] T031 [US3] Amend `.specify/memory/constitution.md`: principle IX's "One `docker-compose.yml` at the repo root" becomes the `docker/` folder, the Documentation Index row for "Local infrastructure" points at `docker/docker-compose.yml`, and the version is bumped to 1.1.0 with an updated Sync Impact Report and "Last Amended" date
- [x] T032 [US3] Verify SC-005: `grep -rn "docker-compose" README.md docs .specify CLAUDE.md` shows no reference that places the compose **file** at the repository root. Prose in `README.md § Design trade-offs` that names docker-compose as the chosen *mechanism* is left untouched (constitution I: existing trade-off entries are not rewritten)

**Checkpoint**: documentation and reality agree — constitution I is satisfied.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T033 Re-read the full diff against the constitution gates in `plan.md` § Constitution Check, confirming no endpoint, DTO, status, event or business rule was introduced (FR-013)
- [x] T034 Confirm FR-001 mechanically: no Docker asset exists outside `docker/` (`find . -name 'Dockerfile*' -o -name 'docker-compose*' -o -name '*.dockerfile' | grep -v '^./docker/'` returns nothing)
- [x] T035 Run the full quickstart from a clean state one final time: `docker compose up -d` → `./gradlew build` → `npm test` → `docker compose down -v`

---

## Dependencies

```text
Phase 1 (T001-T003)
   └─> Phase 2 (T004)
         ├─> Phase 3 / US1 (T005-T010)          [independently shippable]
         ├─> Phase 4 / US2 (T011-T025)          [independently shippable; T015 reads T004's values]
         └─> Phase 5 / US3 (T026-T032)          [describes US1's result; needs US1 done]
                └─> Phase 6 (T033-T035)
```

- **US1 → US3**: the documentation describes the environment US1 creates, so US1 lands first.
- **US2 is independent of US1**: the skeletons build offline; only *running* the backend needs the
  environment.
- **T031 (constitution amendment) is deliberately last within US3**, so the governing text is
  amended once the reality it describes exists.

## Parallel Execution Examples

- **Phase 1**: T002 and T003 in parallel.
- **Phase 4**: T013, T014, T016, T017 in parallel (distinct paths) once T011 lands; T020, T021,
  T022, T024 in parallel throughout — the SDK block and the backend block are fully independent.
- **Phase 5**: T027, T028 and T030 in parallel (distinct files).

## Implementation Strategy

1. **MVP = US1**: the local environment alone already delivers value — a developer can run
   Postgres, Kafka and MinIO with one command.
2. **US2** turns the repository into something buildable, which is what every later feature builds on.
3. **US3** closes the loop required by constitution I: docs and reality never drift.

Total: **35 tasks** — US1: 6, US2: 15, US3: 7, setup/foundational: 4, polish: 3.
