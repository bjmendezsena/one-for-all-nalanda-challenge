# Quickstart — after this feature is implemented

## 1. Start the local environment

```bash
cd docker
docker compose up -d
docker compose ps          # postgres, kafka, minio → healthy; minio-init → exited (0)
```

The MinIO console is at <http://localhost:9001> (`minioadmin` / `minioadmin`); the
`validation-documents` bucket is already there.

## 2. Build and test the backend

```bash
cd service
./gradlew build
```

To run it against the environment started in step 1:

```bash
./gradlew bootRun
```

## 3. Build and test the SDK

```bash
cd sdk
npm install
npm test
npm run build      # emits ESM + CJS + .d.ts into dist/
```

## 4. Reset the environment

```bash
cd docker
docker compose down -v     # removes the containers AND the persisted volumes
```

## 5. Verify the acceptance criteria

| Criterion | How to check |
|---|---|
| SC-001 | Time `docker compose up -d` on a clean clone until all services are healthy. |
| SC-002 | Write a row / an object, `docker compose down`, `docker compose up -d`, read it back. |
| SC-003 | `./gradlew build` and `npm test` both succeed on a clean clone. |
| SC-004 | Compare the created tree against `docs/service/architecture.md` § 4.2 and `docs/sdk/architecture.md` § 3. |
| SC-005 | `grep -rn "docker-compose" README.md docs .specify` shows no reference placing the compose file at the repository root. |
| SC-006 | Follow `docs/service/architecture.md` § 3 and `docker/README.md` only. |
