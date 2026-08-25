# Nalanda SDK integration example

A small project that installs [`@nalanda/validation-sdk`](../sdk) from its own manifest and imports
it **by package name**, then asserts every documented behavior against a running backend.

That is the whole point. `sdk/examples/validate-document.mjs` imports `../dist/index.js` by relative
path — a file, not a package — so it exercises neither the `exports` map, nor the `files: ["dist"]`
allowlist, nor the published `.d.ts`. Those three are what break when a library is published, and
this project is the only thing in the repository that checks them.

It is a demonstration **and** an acceptance harness: every scenario declares the outcome it expects,
compares it with what happened, and the process exits non-zero if any scenario failed.

## Prerequisites

Once, to link the SDK into this project:

```bash
npm run install:example
```

Then, from the repository root:

```bash
npm run docker:up      # postgres, kafka, minio
npm run dev:service    # the backend on http://localhost:8080
```

## Running it

```bash
npm run example
```

That one command rebuilds `sdk/dist` (which is git-ignored, so it may not exist yet), compiles this
project, and runs every scenario — so it always runs against the current SDK, never a stale build.
If the backend is down it names the commands that start it instead of failing with a stack trace.

From inside `example/`, without rebuilding the SDK:

```bash
npm start        # tsc, then node dist/main.js
npm run typecheck
```

Point it elsewhere with the environment — both default to the documented local values:

```bash
BASE_URL=http://localhost:9090 API_KEY=my-key npm run example
```

| Variable | Default |
|---|---|
| `BASE_URL` | `http://localhost:8080` |
| `API_KEY` | `local-dev-api-key` |

| Exit code | Meaning |
|---|---|
| `0` | Every scenario passed |
| `1` | A scenario failed, or the API was unreachable |

## What it covers

Fourteen scenarios: the full public surface of the SDK, and every business rule in
[`docs/business-rules.md`](../docs/business-rules.md) that the SDK mirrors.

| # | Scenario | Covers | Expected |
|---|---|---|---|
| 1 | `happy-path` | `startValidation` + `upload` + `waitForCompletion` | `COMPLETED` / `PASS` |
| 2 | `verdict-unsupported-type` | business rule § 5.1 | `COMPLETED` / `FAIL` / `"unsupported content type"` |
| 3 | `verdict-empty` | business rule § 5.2, lower bound | `COMPLETED` / `FAIL` / `"empty file"` |
| 4 | `verdict-too-large` | business rule § 5.2, upper bound | `COMPLETED` / `FAIL` / `"file too large"` |
| 5 | `idempotent-create` | business rules § 6, same key different body | the original `requestId` |
| 6 | `read-before-completion` | `getValidation` | current status, no result |
| 7 | `wait-timeout` | `timeoutMs` / `initialDelayMs` / `maxDelayMs` | a plain `Error` naming the timeout |
| 8 | `wait-aborted` | `signal` | a `DOMException` named `AbortError` |
| 9 | `read-unknown` | `ValidationApiError.status` / `.body.detail` | `404` with a specific reason |
| 10 | `create-invalid-input` | `ValidationApiError.body.errors` | `400` naming the field |
| 11 | `confirm-replay` | business rules § 6, confirm replay | a safe no-op |
| 12 | `wrong-api-key` | `ClientOptions.apiKey` | `401` with a specific reason |
| 13 | `client-options` | `ClientOptions.baseUrl` / `.headers` | the call succeeds |
| 14 | `upload-without-content-type` | `upload`'s optional `contentType`, non-JSON error body | `400`, `body` undefined, request left `PENDING_UPLOAD` |

The `UploadData` union is spread across scenarios — `Uint8Array`, `string`, `Blob` — so it is
exercised rather than described. Every exported type is named in a signature or an assertion, so a
regression in the published declarations fails `tsc` before anything runs.

### Two things worth knowing

**A `FAIL` verdict is not a failed request.** Scenarios 2–4 all end `COMPLETED`: the check ran and
reached a conclusive answer. Status `FAILED` means the system could not complete the check at all
(`docs/business-rules.md` § 2). The report says so on every failing verdict.

**The presigned URL signs the `Content-Type`.** The backend builds the presign with
`.contentType(...)`, so a `PUT` that omits it or sends a different value is rejected by storage
before the bytes are stored — that is scenario 14, and it is why every other uploading scenario
passes the same content type it declared at creation.

## Documents

| Document | Source |
|---|---|
| valid | `justificante.pdf`, committed, 1026 bytes |
| empty | built at run time, 0 bytes |
| oversized | built at run time, `15 MiB + 1` bytes |

Only the small valid PDF is committed; the other two are generated, so no large binary lives in git.
The verdict rule reads the declared content type and the size only, never the content, so a filled
buffer is a faithful oversized document.

## Layout

```
example/
├── package.json      # "@nalanda/validation-sdk": "file:../sdk"
├── tsconfig.json     # strict
├── justificante.pdf  # the valid sample document
└── src/
    ├── main.ts       # config → preflight → run → report → exit code
    ├── config.ts     # BASE_URL / API_KEY with documented defaults
    ├── documents.ts  # the document set
    ├── runner.ts     # Scenario shape, sequential execution, the report
    └── scenarios.ts  # the fourteen scenarios
```

There is no test framework: the runner is ~100 lines and the report is written for a reader, not for
a CI parser. See `docs/design-trade-offs.md § Example harness` for why.

`example/` follows [`docs/sdk/code_rules.md`](../docs/sdk/code_rules.md), and reaches the SDK only
through its public entrypoint — never `sdk/src/internal/**`, never a relative path into `sdk/dist`.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Cannot reach http://localhost:8080` before any scenario | Backend not started | `npm run dev:service` |
| `Cannot find module '@nalanda/validation-sdk'` | Not installed here | `npm run install:example` |
| `ENOENT .../sdk/dist/index.js` | Ran `npm --prefix example start`, which skips the SDK build | `npm run example` from the root, or `npm run build:sdk` first |
| Every waiting scenario times out | Kafka or the consumer is down, so nothing leaves `QUEUED` | `npm run docker:up`, then check the backend log |
| Scenarios 1–4 and 11 fail with a JSON parse error | An SDK older than `0.1.1` | See `sdk/CHANGELOG.md` — the presigned `PUT` answers `200` with an empty body |

## Not part of `npm test`

`npm test` at the root must stay runnable with nothing else up. This example needs a live backend, so
it is invoked on its own with `npm run example`.
