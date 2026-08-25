# Changelog

All notable changes to this package are documented here. This project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-08-25

### Added

- Package setup: build (Vite library mode, ESM + CJS + `.d.ts`), TypeScript strict mode, Vitest.
- `createClient({ baseUrl, apiKey, headers? })` — a factory returning a plain object of functions,
  no class and no instance state. `X-Api-Key` is sent on every API call.
- `startValidation(input, callOptions?)` — `POST /api/v1/validations`, forwarding an optional
  `Idempotency-Key`.
- `upload(target, data, contentType?)` — presigned `PUT` straight to object storage (the API key is
  deliberately not attached), followed by `POST /api/v1/validations/{requestId}/confirm`. The
  confirm is skipped when the upload is rejected.
- `getValidation(requestId)` — `GET /api/v1/validations/{requestId}`.
- `waitForCompletion(requestId, options?)` — polls until `COMPLETED` or `FAILED`, with exponential
  backoff (250 ms → ×2 → 5 s ceiling), a 30 s budget, and `AbortSignal` support.
- `ValidationApiError` carrying the HTTP status and the RFC 7807 Problem Details body, thrown for
  every rejection including the storage upload.
- Runnable example in `examples/validate-document.mjs` (`npm run example`) and usage docs in
  `README.md`.
