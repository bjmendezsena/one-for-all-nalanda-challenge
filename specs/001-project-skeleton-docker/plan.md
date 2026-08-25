# Implementation Plan: Project skeleton and `docker/` local environment

**Branch**: `001-project-skeleton-docker` | **Date**: 2026-08-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-project-skeleton-docker/spec.md`

## Summary

Create the two empty-but-buildable artifacts described by the architecture documents
(`service/`, `sdk/`), move every Docker asset into a new `docker/` folder containing the
compose file that runs `postgres`, `kafka` and `minio` with named volumes, and update the
documentation (`docs/service/architecture.md`, `README.md`, `.specify/memory/constitution.md`)
so the compose file is no longer described as living at the repository root.

**Artifacts touched**: `service/` **and** `sdk/` (skeletons only), plus new `docker/` and the docs.
**HTTP contract**: unchanged. No endpoint, DTO, status, event or business rule is added — the
skeleton carries zero business logic.

## Technical Context

**Language/Version**: Java 21 (`service/`), TypeScript 5.x on Node 20 (`sdk/`)

**Primary Dependencies**:
- `service/`: Spring Boot 3.5.x (web, validation, data-jpa, kafka), Liquibase, PostgreSQL driver,
  AWS SDK v2 `s3`, H2 (test scope) — all already approved in `README.md § Design trade-offs` and
  constitution IX.
- `sdk/`: Vite (library mode), Vitest, TypeScript — dev dependencies only; zero runtime deps.
- `docker/`: `postgres:16-alpine`, `apache/kafka:3.9.0` (KRaft, no ZooKeeper), `minio/minio` +
  a one-shot `minio/mc` bootstrap that creates the bucket and exits.

**Storage**: PostgreSQL (named volume `postgres-data`), MinIO (named volume `minio-data`),
Kafka (named volume `kafka-data`).

**Testing**: JUnit 5 via `./gradlew test` (`service/`); Vitest via `npm test` (`sdk/`). Both must
be green with no business logic present.

**Target Platform**: local developer machine (macOS/Linux) with Docker Compose v2+.

**Project Type**: two-artifact monorepo (backend service + client library) + local infra.

**Performance Goals**: N/A — scaffolding. The only timing target is SC-001 (environment up in
under 5 minutes on a first run).

**Constraints**:
- Every Docker asset lives under `docker/`, nowhere else (user requirement).
- The backend is NOT containerized (clarification, session 2026-08-25).
- No new dependency, library or infrastructure beyond what the docs already approve.
- Local credentials are development defaults committed in `docker/.env`; they are not secrets.

**Scale/Scope**: 3 containers + 1 one-shot bootstrap; ~2 build files, ~1 application class and
the documented package tree in `service/`; ~6 source/config files in `sdk/`.

## Constitution Check

_GATE: evaluated before Phase 0 and re-checked after Phase 1 design._

| Principle | Gate | Verdict |
|---|---|---|
| I — Documentation is the source of truth | Affected docs updated in the same change | **PASS** — `docs/service/architecture.md` § 3 rewritten for `docker/`; `README.md` repo layout + "How to run" updated; constitution IX and the Documentation Index updated. Edits are the ones the user explicitly requested, not a cleanup rewrite. |
| II — Spec-driven | No invented scope | **PASS** — skeleton only: no endpoint, DTO, status, event, or rule (FR-013). |
| III — Code rules non-negotiable | Both `code_rules.md` obeyed | **PASS** — no domain/application code is written, so no rule can be violated by content; the *structure* created is exactly the one both rule files assume (pure `domain/`, JPA only in `adapter/out/persistence`, `sdk/src/internal/` never re-exported, `"strict": true`). |
| IV — Architecture & boundaries | No invented layer/module | **PASS** — the package tree is copied verbatim from `docs/service/architecture.md` § 4.2 and `docs/sdk/architecture.md` § 3. Single Gradle module. |
| V — Explicit contracts & errors | N/A | **N/A** — no request/response path exists yet. |
| VI — Async integrity | N/A | **N/A** — no producer/consumer code yet. |
| VII — Quality & testing | Build + tests green | **PASS** — both test tasks run and pass on the empty skeleton; no Testcontainers, no MSW. |
| VIII — Security & configuration | No secrets committed | **PASS** — only development defaults in `docker/.env` (`minioadmin`, `validation`), which is exactly what this principle prescribes. |
| IX — Approved stack & operations | No new dependency/infrastructure; compose location | **DEVIATION (approved)** — principle IX literally says "One `docker-compose.yml` **at the repo root**". The user explicitly requested the `docker/` folder, so the principle text itself is amended in this change (see Complexity Tracking). No new dependency: the images are the already-approved `postgres`/`kafka`/`minio`, and `minio/mc` is MinIO's own CLI used as a bootstrap step, not a fourth dependency. |

**Post-Phase-1 re-check**: unchanged — the design adds no layer, dependency or contract beyond the
above.

## Project Structure

### Documentation (this feature)

```text
specs/001-project-skeleton-docker/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── local-environment.md   # the docker/ ↔ service/ configuration contract
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
one-for-all-nalanda-challenge/
├── docker/                              # NEW — every Docker asset lives here
│   ├── docker-compose.yml               # postgres + kafka + minio (+ one-shot bucket bootstrap)
│   ├── .env                             # local defaults (ports, credentials, bucket, topic)
│   └── README.md                        # start / stop / reset, pointer to the architecture doc
├── docs/
│   └── service/architecture.md          # § 3 rewritten: local infrastructure lives in docker/
├── README.md                            # repo layout + "How to run" updated
├── .specify/memory/constitution.md      # principle IX + Documentation Index updated
├── service/                             # NEW skeleton — Java / Spring Boot, single Gradle module
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradlew / gradlew.bat / gradle/wrapper/
│   └── src/
│       ├── main/
│       │   ├── java/com/nalanda/validation/
│       │   │   ├── ValidationServiceApplication.java
│       │   │   ├── domain/model/        # (empty, .gitkeep)
│       │   │   ├── domain/port/         # (empty, .gitkeep)
│       │   │   ├── application/         # (empty, .gitkeep)
│       │   │   ├── adapter/in/web/      # (empty, .gitkeep)
│       │   │   ├── adapter/out/persistence/
│       │   │   ├── adapter/out/messaging/
│       │   │   ├── adapter/out/storage/
│       │   │   └── config/
│       │   └── resources/
│       │       ├── application.yml      # points at the docker/ endpoints
│       │       └── db/changelog/db.changelog-master.yaml   # empty master changelog
│       └── test/
│           ├── java/com/nalanda/validation/
│           │   └── ValidationServiceApplicationTests.java  # context-free placeholder
│           └── resources/application-test.yml              # H2
└── sdk/                                 # NEW skeleton — TypeScript client library
    ├── package.json                     # exports map (import + require), zero runtime deps
    ├── tsconfig.json                    # "strict": true
    ├── vite.config.ts                   # library mode, ESM + CJS + .d.ts
    ├── src/
    │   ├── index.ts                     # public surface only
    │   ├── client.ts                    # createClient placeholder
    │   ├── types.ts
    │   ├── errors.ts
    │   └── internal/http.ts             # never re-exported
    ├── tests/
    ├── examples/
    ├── README.md
    └── CHANGELOG.md                     # 0.1.0
```

**Structure Decision**: the trees above are transcribed from `docs/service/architecture.md` § 4.2
and `docs/sdk/architecture.md` § 3 without additions. The only structural element that the
existing docs do not yet name is the `docker/` folder itself, which is the point of this feature
and is documented as part of it. `<base-package>` is resolved to `com.nalanda.validation`.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Constitution IX says the compose file lives at the repository root; this change moves it to `docker/` | Explicit, unambiguous user requirement: "todo lo relacionado con docker debe convivir en una carpeta llamada docker" | Keeping it at the root would satisfy the current constitution text but contradict the user's direct instruction. The constitution governs process and is amendable by the human; the amendment is applied in this same change (principle IX + Documentation Index), so no document is left contradicting reality. |
| A one-shot `minio/mc` bootstrap container in addition to the three services | FR-004 requires zero manual provisioning, and the bucket the backend uploads to must exist before the first presigned `PUT` | MinIO's official image has no "create this bucket on boot" option. The alternatives — a manual console click-through (breaks FR-004) or an image that supports default buckets (a different, less-maintained image) — are worse. The bootstrap runs once, exits, and is part of the object-storage service, not a fourth runtime dependency. |
