# Building and testing the SDK

Status: living document. This file lives under `docs/sdk/`. It was extracted out of `README.md § How to build/test the SDK` once the README grew too long. For the SDK's structure and public API surface, see `docs/sdk/architecture.md`; for install/usage as a consumer would use it, see [`sdk/README.md`](../../sdk/README.md); for running the external-consumer integration check, see [`example/README.md`](../../example/README.md).

From the repo root:

```bash
npm run install:sdk    # npm --prefix sdk install
npm run build:sdk      # npm --prefix sdk run build  → dual ESM+CJS+.d.ts via Vite
npm run test:sdk       # npm --prefix sdk run test   → Vitest
```

The test suite mocks `fetch` directly, so it needs neither the infrastructure nor a running
service. Two more scripts run inside `sdk/`:

```bash
npm --prefix sdk run typecheck                          # tsc --noEmit, strict mode
npm --prefix sdk run example -- ./invoice.pdf application/pdf   # builds, then runs the example
```

The example (`sdk/examples/validate-document.mjs`) drives a real document end to end — create,
upload, confirm, wait for the verdict — so it does need the infrastructure up and the service
running (see `docs/service/running-locally.md`). It reads `BASE_URL` and `API_KEY` from the environment,
defaulting to `http://localhost:8080` and `local-dev-api-key`.

The SDK's surface is `createClient(options)` plus `startValidation`, `upload`, `getValidation` and
`waitForCompletion`; every rejection — including the direct upload to object storage — is thrown as
`ValidationApiError` carrying the HTTP status and the Problem Details body. See
[`sdk/README.md`](../../sdk/README.md) for install/usage as a consumer would use it, and
[`docs/sdk/architecture.md`](architecture.md) § 4 for the surface itself.

`sdk/examples/validate-document.mjs` above is a snippet *inside* the package — it imports
`../dist/index.js` by relative path. `example/` is the opposite: a separate project that installs
the SDK from its own manifest (`"@nalanda/validation-sdk": "file:../sdk"`) and imports it by
package name, so it is the only thing in the repository that exercises the `exports` map, the
`files` allowlist and the published `.d.ts`. See [`example/README.md`](../../example/README.md)
for how to run it and the full scenario table — it is deliberately not part of `npm test`, since it
needs a live backend while `npm test` must stay runnable with nothing else up.
