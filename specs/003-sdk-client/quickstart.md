# Quickstart: using the SDK

Assumes the local environment is up (`npm run docker:up`) and the service is running on
`http://localhost:8080` with `API_KEY=local-dev-api-key`, per `README.md`.

```bash
npm run install:sdk
npm run build:sdk
npm run test:sdk
```

## End-to-end usage

```typescript
import { readFile } from "node:fs/promises";
import { createClient, ValidationApiError } from "@nalanda/validation-sdk";

const client = createClient({
  baseUrl: "http://localhost:8080",
  apiKey: process.env.API_KEY ?? "local-dev-api-key",
});

try {
  const started = await client.startValidation(
    { filename: "invoice.pdf", contentType: "application/pdf" },
    { idempotencyKey: "demo-key-1" },
  );

  await client.upload(started, await readFile("invoice.pdf"), "application/pdf");

  const finished = await client.waitForCompletion(started.requestId);
  console.log(finished.status, finished.result?.verdict, finished.result?.reason);
} catch (error) {
  if (error instanceof ValidationApiError) {
    console.error(error.status, error.body?.detail, error.body?.errors);
  } else {
    throw error;
  }
}
```

A PDF between 1 byte and 15 MB ends `COMPLETED` with `verdict: "PASS"`. Any other content type,
an empty file, or one over 15 MB ends `COMPLETED` with `verdict: "FAIL"` and a specific `reason`
(`docs/business-rules.md` § 5).

## Cancelling a wait

```typescript
const controller = new AbortController();
setTimeout(() => controller.abort(), 5_000);

await client.waitForCompletion(requestId, { signal: controller.signal });
```

## Verifying the surface

```bash
npm --prefix sdk run typecheck   # strict mode, no errors
npm --prefix sdk run build       # dist/index.js + dist/index.cjs + dist/index.d.ts
npm --prefix sdk run test        # Vitest, no infrastructure required
```
