# one-for-all-nalanda-challenge

Fullstack Engineer Sr — Technical Assessment. Spring Boot service (`service/`) + TypeScript SDK (`sdk/`) implementing the asynchronous document validation flow, plus a runnable integration example (`example/`) that consumes the SDK as an external consumer would.

## Table of contents

- [Architecture](#architecture)
  - [Repository layout](#repository-layout)
- [Prerequisites](#prerequisites)
- [Quick start](#quick-start)
- [Endpoints and local services](#endpoints-and-local-services)
- [Design trade-offs](#design-trade-offs)
- [What I'd do with another day](#what-id-do-with-another-day)
- [AI usage](#ai-usage)

## Architecture

The system validates business documents asynchronously. A client:
1. Requests a validation → receives a `requestId` and a presigned upload URL.
2. Uploads the document bytes directly to object storage using that URL.
3. Confirms the upload → the backend enqueues a processing event.
4. Processing happens asynchronously (extraction is a deterministic stub).
5. The client polls the validation status, or uses the SDK's `waitForCompletion` helper.

Three artifacts are delivered from one monorepo:
- `service/` — the Java/Spring Boot backend implementing the API and the async processing pipeline.
- `sdk/` — the TypeScript client library that wraps the HTTP API for Node/bundler consumers.
- `example/` — a small, non-publishable project that installs the SDK from its own manifest and
  imports it by package name, then asserts every documented behavior against a running backend. It
  is the only thing in the repository that exercises the SDK's packaging, `exports` map and
  published type declarations. See [`example/README.md`](example/README.md).

For the detailed backend architecture (hexagonal layers, package structure, ports/adapters, infrastructure), see [`docs/service/architecture.md`](docs/service/architecture.md); for the SDK's architecture, see [`docs/sdk/architecture.md`](docs/sdk/architecture.md). For the reasoning behind each design decision, see [§ Design trade-offs](#design-trade-offs) below.

### Repository layout

`docs/` holds only documentation shared between `service/` and `sdk/`, or that spans the whole monorepo (business rules, and every design trade-off); each artifact's own detailed docs live under `docs/service/` and `docs/sdk/` respectively.

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
│   ├── business-rules.md       # shared: domain/business rules
│   ├── design-trade-offs.md    # shared: every design decision, alternatives + rationale
│   ├── service/                 # service-specific documentation
│   │   ├── architecture.md
│   │   ├── running-locally.md
│   │   ├── api.md
│   │   ├── code_rules.md
│   │   ├── events.md
│   │   ├── kafka.md
│   │   └── upload-flow.md
│   └── sdk/                     # SDK-specific documentation
│       ├── architecture.md
│       ├── building-and-testing.md
│       └── code_rules.md
├── service/                    # Java / Spring Boot backend (Gradle wrapper committed)
├── sdk/                        # TypeScript client library
├── example/                    # integration example: installs the SDK and asserts it end to end
│   ├── justificante.pdf        # the valid sample document
│   ├── src/                    # config, documents, runner, scenarios, entrypoint
│   └── README.md
├── specs/                      # spec-kit feature artifacts (spec / plan / tasks per feature)
└── .specify/                   # spec-kit configuration, templates and the constitution
```

## Prerequisites

- **Java 21** (JDK) — to build/run `service/`. The Gradle wrapper (`./gradlew`) is committed, so a separate Gradle install is not needed.
- **Node.js ≥ 18** — required for the SDK's native `fetch` usage and its tooling (Vite, Vitest) and for the root orchestration scripts. `.nvmrc` at the repo root pins the recommended version (`nvm use` picks it up automatically).
- **Docker + Docker Compose** (the `docker compose` v2 CLI) — to run PostgreSQL, Kafka, and MinIO locally via `docker/docker-compose.yml`. Every Docker asset in this repository lives under `docker/`; see `docs/service/architecture.md` § 3.

## Quick start

```bash
npm run docker:up                              # postgres, kafka, minio
npm run dev:service                            # the API on http://localhost:8080
npm run install:sdk && npm run build:sdk && npm run test:sdk
```

Or everything at once, in one terminal: `npm run dev` (infra + service + SDK watch build, via `concurrently`).

Full guides, extracted here to keep this README short:

| Guide | Covers |
|---|---|
| [`docs/service/running-locally.md`](docs/service/running-locally.md) | Starting the infrastructure and the service, verifying the environment, testing MinIO directly |
| [`docs/service/api.md`](docs/service/api.md) | The HTTP API: endpoints, auth, error catalog, idempotency rules, a full curl walkthrough |
| [`docs/sdk/building-and-testing.md`](docs/sdk/building-and-testing.md) | Building, testing, and typechecking the SDK, and running its bundled example |
| [`example/README.md`](example/README.md) | Running the external-consumer integration example (14 scenarios against a live backend) |

The root `package.json` holds only orchestration scripts (`dev`, `build`, `test`, `docker:up`/`docker:down`) that delegate to `service/` (Gradle) and `sdk/` (npm) — it is not itself a publishable package. See [`docs/design-trade-offs.md` § Root package.json and dev orchestration](docs/design-trade-offs.md#root-packagejson-and-dev-orchestration).

## Endpoints and local services

Once the infrastructure is up (`npm run docker:up`) and the service is running (`npm run dev:service`), this is what's reachable for testing — the fastest way to poke at the system before reading anything else.

### Service API

Base URL `http://localhost:8080/api/v1`. Every endpoint requires the header `X-Api-Key: local-dev-api-key` (default value from `security.api-key` in `application.yml`; override with the `API_KEY` environment variable).

| Method | Endpoint | Does |
|---|---|---|
| `POST` | `/api/v1/validations` | Registers the intent to validate a document and returns `{ requestId, status, uploadUrl }` |
| `POST` | `/api/v1/validations/{requestId}/confirm` | Confirms the upload and enqueues async processing |
| `GET` | `/api/v1/validations/{requestId}` | Reads the current status, and the result once it exists |

Full reference — error catalog, `Idempotency-Key` semantics, a complete curl walkthrough of the three-step flow: [`docs/service/api.md`](docs/service/api.md).

### MinIO (object storage)

| | |
|---|---|
| API endpoint | `http://localhost:9000` |
| Console (web UI) | `http://localhost:9001` |
| Credentials | `minioadmin` / `minioadmin` |
| Bucket | `validation-documents` (created automatically on the first `docker compose up`) |

Credentials and ports come from `docker/.env` and can be overridden there; the service reads the same
values. The service never proxies the document bytes — the client uploads directly to MinIO using the
presigned URL returned by `POST /api/v1/validations`. For the console/CLI/presigned-URL walkthroughs, see
[`docs/service/running-locally.md`](docs/service/running-locally.md) § Testing MinIO.

## Design trade-offs

Every architecture, business, and code decision made on this project is recorded in [`docs/design-trade-offs.md`](docs/design-trade-offs.md), including the discarded alternatives and the actual reason behind each choice — not just "what was done", but "why". It covers, among others: async processing and messaging design, document storage and the upload flow, idempotency, persistence and migrations, the API error format, the internal (hexagonal) service architecture, domain model style, testing strategy on both sides, the SDK's implementation approach and client design, and the monorepo's tooling (root `package.json`, toolchain setup).

## What I'd do with another day

_(pending)_

## AI usage

See [`AI_USAGE.md`](AI_USAGE.md).
