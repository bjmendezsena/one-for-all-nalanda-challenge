# Running the service locally

Status: living document. This file lives under `docs/service/`. It was extracted out of `README.md § How to run the service + DB` once the README grew too long. It covers starting the local infrastructure, running the service against it, and verifying everything is actually wired correctly. For the architecture behind these pieces, see `docs/service/architecture.md` § 3; for why each dependency exists, see `docs/design-trade-offs.md`.

## Starting it

From the repo root:

```bash
npm run docker:up      # starts postgres, kafka, minio (docker/docker-compose.yml)
npm run dev:service    # cd service && ./gradlew bootRun
```

Or start everything (infra + service + SDK watch build) in one terminal with `npm run dev` (see `docs/design-trade-offs.md § Root package.json and dev orchestration`).

The first `docker compose up` also creates the `validation-documents` bucket in MinIO, so no manual
provisioning step is needed. Ports, credentials and the bucket name come from `docker/.env` and can
be overridden there without editing the compose file; the service reads the same values with the
same defaults. `npm run docker:down` stops the environment while keeping its data in named volumes;
`docker compose -f docker/docker-compose.yml down -v` wipes it.

To run the service's own automated tests: `npm run test:service` (`./gradlew test`, against H2 — see `docs/design-trade-offs.md § Integration testing strategy`). They need no running infrastructure.

The same two commands from inside `service/`, without the root npm wrapper:

```bash
cd service
./gradlew bootRun   # runs the API on http://localhost:8080, applying the Liquibase changelog on startup
./gradlew test      # the whole suite: domain, use cases, controller, persistence, messaging, storage
```

`./gradlew test` is green with nothing running: persistence tests use H2 in PostgreSQL mode with the
real changelog applied, and Kafka and MinIO are replaced by hand-written fakes and mocks.

## Verifying the environment is up

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

## Testing MinIO

MinIO is the S3-compatible object storage the presigned upload flow targets
(see [`docs/service/upload-flow.md`](upload-flow.md)). Three ways to exercise it,
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
(see [`docs/service/upload-flow.md`](upload-flow.md)).
