# Contract — the local environment interface

This feature exposes no HTTP contract. The contract it does establish is between the `docker/`
environment and the backend that connects to it: the endpoints, credentials and resource names a
developer (or `service/`) can rely on after `docker compose up`.

## Endpoints offered

| Service | Protocol | Local endpoint | Readiness signal |
|---|---|---|---|
| `postgres` | PostgreSQL wire | `localhost:${POSTGRES_PORT}` (default `5432`) | `pg_isready` healthcheck |
| `kafka` | Kafka | `localhost:${KAFKA_PORT}` (default `9092`) | broker API-versions probe healthcheck |
| `minio` | S3 (HTTP) | `http://localhost:${MINIO_API_PORT}` (default `9000`) | MinIO `/minio/health/live` healthcheck |
| `minio` console | HTTP (humans) | `http://localhost:${MINIO_CONSOLE_PORT}` (default `9001`) | n/a |

## Resources guaranteed after startup

- Database `${POSTGRES_DB}` exists and accepts `${POSTGRES_USER}` / `${POSTGRES_PASSWORD}`.
  Schema objects are **not** created here — Liquibase owns the schema (constitution IX).
- Bucket `${MINIO_BUCKET}` exists.
- Topic `validation.processing-requested` is **not** pre-created: it is auto-created on first use,
  as already documented in `docs/service/kafka.md` § 5.

## Operations offered

| Intent | Command (run from `docker/`) |
|---|---|
| Start everything | `docker compose up -d` |
| Follow logs | `docker compose logs -f` |
| Stop, keep data | `docker compose down` |
| Stop and wipe data | `docker compose down -v` |

## Compatibility rules

- Adding a Docker asset means adding it under `docker/` — never elsewhere.
- Adding a service to the compose file means documenting it in `docs/service/architecture.md` § 3
  in the same change.
- Changing a default port, credential or resource name means changing `docker/.env`, the backend's
  `application.yml` defaults and the architecture document together.
