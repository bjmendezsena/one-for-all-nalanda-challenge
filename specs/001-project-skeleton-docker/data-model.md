# Phase 1 — Data model

This feature introduces **no domain entity**. The backend's domain model
(`ValidationRequest`, `DocumentMetadata`, `ValidationStatus`, `ValidationResult`) is defined in
`docs/business-rules.md` and `docs/service/architecture.md` and is deliberately NOT created here
(FR-013). The only "entities" this feature defines are configuration-level.

## Local environment configuration

| Key (in `docker/.env`) | Default | Consumed by | Meaning |
|---|---|---|---|
| `POSTGRES_DB` | `validation` | `postgres`, `service/` | Database name |
| `POSTGRES_USER` | `validation` | `postgres`, `service/` | Database user |
| `POSTGRES_PASSWORD` | `validation` | `postgres`, `service/` | Database password (development default) |
| `POSTGRES_PORT` | `5432` | host | Host port mapped to Postgres |
| `KAFKA_PORT` | `9092` | host | Host port mapped to the Kafka listener |
| `MINIO_ROOT_USER` | `minioadmin` | `minio`, `service/` | Object storage access key (development default) |
| `MINIO_ROOT_PASSWORD` | `minioadmin` | `minio`, `service/` | Object storage secret key (development default) |
| `MINIO_API_PORT` | `9000` | host, `service/` | Host port for the S3 API |
| `MINIO_CONSOLE_PORT` | `9001` | host | Host port for the MinIO web console |
| `MINIO_BUCKET` | `validation-documents` | `minio` bootstrap, `service/` | Bucket the presigned uploads target |

## Persisted state

| Volume | Attached to | Survives | Removed by |
|---|---|---|---|
| `postgres-data` | `postgres:/var/lib/postgresql/data` | `docker compose down` | `docker compose down -v` |
| `kafka-data` | `kafka:/var/lib/kafka/data` | `docker compose down` | `docker compose down -v` |
| `minio-data` | `minio:/data` | `docker compose down` | `docker compose down -v` |

## Backend configuration mapping

`service/src/main/resources/application.yml` reads the same values, with the `docker/.env`
defaults baked in as its own defaults so the service starts against the local environment with no
extra setup:

| Backend property | Local value |
|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/validation` |
| `spring.kafka.bootstrap-servers` | `localhost:9092` |
| `spring.kafka.consumer.group-id` | `validation-service` (per `docs/service/kafka.md` § 2) |
| `spring.liquibase.change-log` | `classpath:db/changelog/db.changelog-master.yaml` |
| storage endpoint | `http://localhost:9000` |
| storage bucket | `validation-documents` |

State transitions: none — this feature has no lifecycle to model.
