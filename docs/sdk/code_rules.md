# Code rules (SDK)

Status: living document. This file lives under `docs/sdk/` — it is specific to the TypeScript SDK (`sdk/`). The backend has its own equivalent, `docs/service/code_rules.md`. It describes implementation-level conventions for the SDK, with examples. It assumes familiarity with `docs/sdk/architecture.md` (structure, public API surface) and `docs/business-rules.md` (the domain rules the SDK's types and errors reflect). It does not repeat the reasoning behind each choice — the rationale and discarded alternatives live in `README.md § Design trade-offs`, indexed by the same section names used here.

## 1. Client: factory function, not a class

`createClient` returns a plain object of bound functions — no `new`, no instance state beyond a closure over `options`.

```typescript
// src/client.ts
export function createClient(options: ClientOptions): ValidationClient {
  const baseUrl = options.baseUrl.replace(/\/$/, "");
  const headers = { "Content-Type": "application/json", ...options.headers };

  return {
    startValidation: (input) => startValidation(baseUrl, headers, input),
    upload: (requestId, data) => upload(baseUrl, requestId, data),
    getValidation: (requestId) => getValidation(baseUrl, headers, requestId),
    waitForCompletion: (requestId, opts) => waitForCompletion(baseUrl, headers, requestId, opts),
  };
}
```

## 2. HTTP layer: native `fetch`, one internal `request()` helper

No `axios`, no `node-fetch`. A single internal helper centralizes base URL joining, headers, and error parsing.

```typescript
// src/internal/http.ts
export async function request<T>(url: string, init: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  if (!response.ok) {
    const problem = await response.json().catch(() => undefined);
    throw new ValidationApiError(response.status, problem);
  }
  return response.status === 204 ? (undefined as T) : response.json();
}
```

## 3. Errors: `ValidationApiError` mirroring the backend's Problem Details shape

```typescript
// src/errors.ts
export interface ProblemDetailsBody {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  errors?: Array<{ field: string; message: string }>;
}

export class ValidationApiError extends Error {
  readonly status: number;
  readonly body?: ProblemDetailsBody;

  constructor(status: number, body?: ProblemDetailsBody) {
    super(body?.detail ?? `Request failed with status ${status}`);
    this.name = "ValidationApiError";
    this.status = status;
    this.body = body;
  }
}
```

This mirrors, on the client side, exactly the shape produced by the backend's `@RestControllerAdvice` (`docs/service/code_rules.md` § 5) — including the `errors` field for field-level validation failures.

## 4. Types: mirror the backend's actual response shape

Because the backend exposes `domain/model` directly (see `README.md § Design trade-offs → Controllers`), the SDK's types are written to match that JSON as it actually is on the wire, not an idealized/independent contract.

```typescript
// src/types.ts
export type ValidationStatus = "PENDING_UPLOAD" | "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED";

export interface ValidationResult {
  verdict: "PASS" | "FAIL";
  fields: Record<string, unknown>;
  reason: string | null;
}

export interface ValidationRequestDto {
  requestId: string;
  status: ValidationStatus;
  result?: ValidationResult;
}

export interface StartValidationInput {
  filename: string;
  contentType: string;
}

export interface StartValidationResponse {
  requestId: string;
  status: ValidationStatus;
  uploadUrl: string;
}
```

## 5. Polling: exponential backoff + `AbortSignal`, no retry library

```typescript
// src/client.ts (excerpt)
export interface WaitForCompletionOptions {
  timeoutMs?: number;
  initialDelayMs?: number;
  maxDelayMs?: number;
  signal?: AbortSignal;
}

async function waitForCompletion(
  baseUrl: string,
  headers: HeadersInit,
  requestId: string,
  opts: WaitForCompletionOptions = {},
): Promise<ValidationRequestDto> {
  const { timeoutMs = 30_000, initialDelayMs = 250, maxDelayMs = 5_000, signal } = opts;
  const deadline = Date.now() + timeoutMs;
  let delay = initialDelayMs;

  while (true) {
    const current = await getValidation(baseUrl, headers, requestId);
    if (current.status === "COMPLETED" || current.status === "FAILED") return current;
    if (Date.now() + delay > deadline) throw new Error(`waitForCompletion timed out after ${timeoutMs}ms`);

    await sleep(delay, signal);
    delay = Math.min(delay * 2, maxDelayMs);
  }
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener("abort", () => {
      clearTimeout(timer);
      reject(new DOMException("Aborted", "AbortError"));
    });
  });
}
```

## 6. Testing: Vitest, `fetch` mocked directly (no MSW)

```typescript
// tests/client.test.ts
import { describe, it, expect, vi, beforeEach } from "vitest";
import { createClient } from "../src/client";

describe("createClient", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  it("should_returnValidationRequest_when_getValidationSucceeds", async () => {
    (fetch as unknown as vi.Mock).mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ requestId: "abc-123", status: "COMPLETED" }),
    });

    const client = createClient({ baseUrl: "https://api.local" });
    const result = await client.getValidation("abc-123");

    expect(result.status).toBe("COMPLETED");
  });

  it("should_throwValidationApiError_when_serverReturnsProblemDetails", async () => {
    (fetch as unknown as vi.Mock).mockResolvedValue({
      ok: false,
      status: 404,
      json: async () => ({ title: "Not Found", status: 404, detail: "Validation request not found" }),
    });

    const client = createClient({ baseUrl: "https://api.local" });
    await expect(client.getValidation("missing")).rejects.toThrow("Validation request not found");
  });
});
```

Test method naming follows the same `should_<expectedBehavior>_when_<condition>()` convention as the backend (`docs/service/code_rules.md` § 8), for consistency across the monorepo.

## 7. Module format: pure ESM source

All source files in `src/` use `import`/`export` exclusively — never `require(...)` or `module.exports` by hand. The dual ESM/CJS output is produced only by Vite's library-mode build (`vite.config.ts`); nothing in `src/` is aware that a CJS build exists.

```typescript
// ✅ correct — src/index.ts
export { createClient } from "./client";
export type { ValidationRequestDto, ValidationStatus, StartValidationInput } from "./types";
export { ValidationApiError } from "./errors";
```

```typescript
// ❌ never do this in src/ — this is exactly the "wrong module format"
// mistake the assignment calls out as an AI suggestion to reject
module.exports = { createClient };
```

## 8. Naming conventions

Beyond the per-file naming already shown above, the SDK follows standard TypeScript naming conventions — the same baseline any reviewer would expect from a well-kept TS package:

- **Booleans** (variables, properties, and options fields) are named as a predicate, prefixed with `is`, `has`, `can`, or `should` — never a bare noun or adjective. Examples: `isCompleted`, `hasResult`, `shouldRetry`. This applies to option flags too (e.g. an option to skip a check would be `skipValidation`'s boolean form named `shouldSkipValidation`, not `skip` or `validation`).
- **Functions** are named as verbs or verb phrases (`createClient`, `waitForCompletion`), never as nouns.
- **Types/interfaces** are `PascalCase`; **functions, variables, and object properties** are `camelCase`; **module-level constants** are `UPPER_SNAKE_CASE` (e.g. `DEFAULT_TIMEOUT_MS`) when they represent a fixed configuration value, `camelCase` when they're a computed/derived value.
- Names spell words out — no abbreviations beyond ones already established as domain vocabulary (`dto`, `id`). `req`, `opts` as a full identifier (vs. a destructured local shorthand), and similar are not used in public-facing names.

This is on top of what the assignment mandates directly for the SDK: **TypeScript strict mode** (`tsconfig.json` — `"strict": true`, see `docs/sdk/architecture.md` § 3) and a public API that is "small, intentional, documented" — both non-negotiable per the assignment's SDK requirements table.

| Element | Convention | Example |
|---|---|---|
| Source file | kebab-case | `client.ts`, `errors.ts`, `internal/http.ts` |
| Type / interface | PascalCase | `ValidationRequestDto`, `WaitForCompletionOptions` |
| Function | camelCase | `createClient`, `waitForCompletion` |
| Boolean variable / property | `is`/`has`/`can`/`should` prefix | `isCompleted`, `hasResult`, `shouldRetry` |
| Constant (fixed value) | `UPPER_SNAKE_CASE` | `DEFAULT_TIMEOUT_MS` |
| Error class | `<Domain>Error` suffix | `ValidationApiError` |
| Test file | `<module>.test.ts` | `client.test.ts` |
| Test method | `should_<expectedBehavior>_when_<condition>()` | `should_throwValidationApiError_when_serverReturnsProblemDetails()` |

Only `src/index.ts` exports the public API; internal helpers (e.g. `src/internal/http.ts`) are never re-exported from it.

## 9. Restrictions for AI coding assistants

This section exists so that any AI coding assistant working on `sdk/` — regardless of which one — stays inside the decisions already made in this document and in `README.md § Design trade-offs`, instead of silently substituting a "more idiomatic" alternative. These are hard restrictions, not suggestions:

**SDK design**
- `src/` is pure ESM (`import`/`export`) — never `require`/`module.exports` by hand; the dual ESM/CJS output is Vite's responsibility exclusively (§ 7).
- No HTTP client dependency (`axios`, `node-fetch`, etc.) is added — only the native `fetch` API (§ 2).
- The client stays a factory function (`createClient` returning a plain object of closures) — it is never turned into a `class`/`new Client()` (§ 1; the classes-vs-closures alternative was already discussed and rejected, see `README.md § Design trade-offs → SDK client architecture`).
- Every API error is thrown as `ValidationApiError` carrying the HTTP status and Problem Details body — never a raw `fetch` rejection or a generic `Error` (§ 3).
- `waitForCompletion` keeps exponential backoff and `AbortSignal` support — no fixed-interval polling, and no external retry library (`p-retry` or similar) is added (§ 5).
- `tsconfig.json` keeps `"strict": true` at all times — never relaxed to silence a type error.

**Testing**
- Tests mock `fetch` directly (`vi.stubGlobal("fetch", ...)`) — Mock Service Worker (MSW), `nock`, or similar are not introduced (§ 6).
- Test method names always follow `should_<expectedBehavior>_when_<condition>()` (§ 6, § 8).

**Cross-cutting**
- Any new dependency, library, or piece of infrastructure is flagged as a question to the human before being added — never introduced silently because it's "commonly used" or "more idiomatic".
- Any deviation from a decision already documented in `README.md § Design trade-offs` or in this file is raised as an explicit question — never silently substituted.
- All documentation stays in English.
- Existing entries in `docs/**/*.md` are not rewritten to "clean them up" — only additive edits or changes explicitly requested by the human are made.
