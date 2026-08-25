# Local environment

Everything Docker-related in this repository lives in this folder. The authoritative description of
the environment is `docs/service/architecture.md` § 3 — this file is the operational cheat sheet.

`docker-compose.yml` runs the three dependencies the backend needs locally. The backend itself is
**not** containerized: it runs on the host (`./gradlew bootRun`) and connects to these containers.

| Service | Local endpoint | Credentials | Data lives in |
|---|---|---|---|
| `postgres` | `localhost:5432`, database `validation` | `validation` / `validation` | volume `postgres-data` |
| `kafka` | `localhost:9092` (KRaft, no ZooKeeper) | none | volume `kafka-data` |
| `minio` | `localhost:9000` (S3 API), `localhost:9001` (console) | `minioadmin` / `minioadmin` | volume `minio-data` |

A fourth, short-lived container (`minio-init`) creates the `validation-documents` bucket on first
start and exits — there is no manual provisioning step. The Kafka topic is auto-created on first
use (see `docs/service/kafka.md` § 5).

## Commands

```bash
cd docker

docker compose up -d       # start everything
docker compose ps          # postgres/kafka/minio → healthy, minio-init → exited (0)
docker compose logs -f     # follow the logs
docker compose down        # stop, keeping the data
docker compose down -v     # stop and wipe all local state
```

The same commands are available from the repository root as `npm run docker:up` / `npm run docker:down`.

## Configuration

Ports, credentials, the database name and the bucket name live in `.env` and are read by
`docker-compose.yml` through `${VAR:-default}` interpolation — change them there, never in the
compose file. Those values are development-only defaults, not secrets, which is why `.env` is
committed: a clean clone comes up with `docker compose up` and nothing else.

`service/src/main/resources/application.yml` reads the same environment variables with the same
defaults, so the backend points at this environment out of the box.

## Adding a Docker asset

Any new compose file, `Dockerfile` or Docker-related script goes in this folder, and the service it
adds is documented in `docs/service/architecture.md` § 3 in the same change.
