# Quickstart: running the example

**Feature**: `004-example-sdk-integration` | **Date**: 2026-08-25

## Once

```bash
nvm use                 # Node 20, per .nvmrc
npm install             # root
npm run install:sdk
npm run install:example
```

`npm run install:example` resolves `"@nalanda/validation-sdk": "file:../sdk"`, so `sdk/` must exist —
it does not need to be built yet at install time, but it does before the run.

## Every time

Three terminals, or three background processes:

```bash
npm run docker:up       # postgres, kafka, minio
npm run dev:service     # backend on http://localhost:8080
```

Then:

```bash
npm run example         # rebuilds sdk/dist, compiles the example, runs every scenario
```

## What you should see

14 scenarios, all passing, in well under two minutes:

```
14 scenarios: 14 passed, 0 failed  (24.8s)
```

Exit code `0`. Anything else means a scenario's observed outcome did not match its expected one — the
report block for that scenario says which.

## Pointing it somewhere else

```bash
BASE_URL=http://localhost:9090 API_KEY=my-key npm run example
```

## When it will not start

| Symptom | Cause | Fix |
|---|---|---|
| `Cannot reach http://localhost:8080` before any scenario runs | Backend not started | `npm run dev:service` |
| `Cannot find module '@nalanda/validation-sdk'` | SDK not installed into the example | `npm run install:example` |
| `ENOENT .../sdk/dist/index.js` | Ran `npm --prefix example start`, which skips the SDK build | `npm run example` from the root, or `npm run build:sdk` first |
| Every waiting scenario times out | Kafka or the consumer is down, so nothing leaves `QUEUED` | `npm run docker:up`, then check the backend log |

## Verifying the SDK fix that ships with this feature

The presigned-`PUT` defect (research R-004) is covered by a Vitest case in the SDK's own suite:

```bash
npm run test:sdk
```

Reverting `sdk/src/internal/http.ts` must turn `should_returnUndefined_when_responseHasNoBody` red,
and must turn the example's scenarios 1–4 and 11 red as well.
