# Quickstart — Service core flows

**Feature**: `002-service-core-flows` | **Date**: 2026-08-25

How to run and exercise what this feature builds. Commands are run from the repository root
unless stated otherwise.

## 1. Start the local infrastructure

```bash
cd docker && docker compose up -d
```

Brings up `postgres` (5432), `kafka` (9092), `minio` (9000 API / 9001 console) and the
short-lived `minio-init` that creates the `validation-documents` bucket. Wait until all three
report healthy: `docker compose ps`.

## 2. Run the service

```bash
cd service && ./gradlew bootRun
```

The service is not containerized — it runs on the host and reads the same defaults the
compose file uses. Liquibase applies the changelog on startup, so the schema is created on
first run.

## 3. Run the tests

```bash
cd service && ./gradlew test
```

No container needs to be running: persistence tests use H2 in PostgreSQL mode with the real
Liquibase changelog applied, and Kafka/MinIO are replaced by fakes and mocks.

## 4. Walk the full flow

```bash
API_KEY=local-dev-api-key   # the default in application.yml

# 1. Create — returns requestId + uploadUrl
curl -sS -X POST http://localhost:8080/api/v1/validations \
  -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-key-1" \
  -d '{"filename":"invoice.pdf","contentType":"application/pdf"}'

# 2. Upload the bytes straight to MinIO with the returned uploadUrl
curl -sS -X PUT "<uploadUrl>" \
  -H "Content-Type: application/pdf" \
  --data-binary @invoice.pdf

# 3. Confirm — accepts the work and returns immediately
curl -sS -X POST http://localhost:8080/api/v1/validations/<requestId>/confirm \
  -H "X-Api-Key: $API_KEY"

# 4. Read — poll until COMPLETED
curl -sS http://localhost:8080/api/v1/validations/<requestId> \
  -H "X-Api-Key: $API_KEY"
```

## 5. Things worth trying

| Try | Expected |
|---|---|
| Repeat step 1 with the same `Idempotency-Key` | same `requestId`, no second request, a fresh `uploadUrl` |
| Repeat step 3 | `202` with the current status, no second processing run |
| Create with `"contentType":"image/png"`, upload, confirm | `COMPLETED` with `verdict: FAIL`, `reason: "unsupported content type"` |
| Confirm without ever uploading | `COMPLETED` with `verdict: FAIL`, `reason: "empty file"` |
| Upload a file over 15 MB | `COMPLETED` with `verdict: FAIL`, `reason: "file too large"` |
| Omit `X-Api-Key` | `401` Problem Details |
| Use an unknown `requestId` | `404` Problem Details |
| Send `{"filename":""}` | `400` Problem Details with `errors[]` |

## 6. Reset

```bash
cd docker && docker compose down -v   # wipes postgres, kafka and minio state
```
