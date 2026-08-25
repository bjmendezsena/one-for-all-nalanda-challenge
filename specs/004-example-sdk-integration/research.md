# Phase 0 research: Example project integrating the SDK

**Feature**: `004-example-sdk-integration` | **Date**: 2026-08-25

Every finding below was verified against the code in this repository, not assumed.

## R-001 — How an external consumer must resolve the SDK

**Decision**: `example/` gets its own `package.json` declaring
`"@nalanda/validation-sdk": "file:../sdk"`, and imports the library **by package name**.

**Rationale**: `sdk/package.json` publishes `files: ["dist"]`, `main`/`module`/`types` and an
`exports` map. Only a package-name import through `node_modules` exercises that map. The existing
`sdk/examples/validate-document.mjs` imports `../dist/index.js` by relative path, so it bypasses the
manifest entirely — it is a usage snippet, not an integration.

**Alternatives considered**:
- Relative import of `../sdk/dist/index.js` — what the current snippet does; proves nothing about
  packaging (rejected, and the reason this feature exists).
- npm workspaces at the repo root — would change how `sdk/` itself installs and builds; out of scope
  for a demo (rejected).

**Consequence**: `npm run build:sdk` is a prerequisite of the example's run, because `file:` resolves
to `sdk/dist`, which is git-ignored.

## R-002 — Language and toolchain of the example

**Decision**: TypeScript compiled with `tsc` to `example/dist/`, run with `node`. Two devDependencies:
`typescript` and `@types/node`.

**Rationale**: FR-001a requires the published `.d.ts` to be exercised, which only a typed consumer
does. Node 20 (`.nvmrc`) has no native type stripping (that arrived in 22.6), so a compile step is
required. `typescript` is already the SDK's devDependency — same approved stack, no new technology.
`@types/node` is genuinely new to the repository and is unavoidable: the example needs `process`,
`node:fs/promises` and `node:crypto` under `"strict": true`.

**Alternatives considered**:
- Plain `.mjs` — no build step, but leaves the published type declarations untested (rejected by
  FR-001a).
- `tsx`/`ts-node` — an extra runtime dependency for no gain over `tsc` (rejected).

## R-003 — Scenario runner without a test framework

**Decision**: A ~60-line runner in `example/src/runner.ts`. A `Scenario` is `{ id, title, covers,
expected, run() }`; the runner executes them sequentially, catches per-scenario failures, prints an
aligned report, and returns the counts that decide the exit code.

**Rationale**: The clarification session settled on this. It keeps `example/` free of a test-framework
dependency, and keeps the report readable as a demo rather than as test output. Sequential execution
also keeps the report deterministic (SC-005) and avoids hammering a single local backend.

**Alternatives considered**:
- Vitest against the live service — free reporting, but adds a dependency and collides with the SDK's
  documented unit-test style (`docs/sdk/code_rules.md` § 6: `fetch` is mocked) (rejected).
- `node:test` — built in, but its TAP output is test output, not a demo report, and it would still
  need a compile step plus reporter wiring (rejected).

## R-004 — Blocking defect: a successful response with no body

**Decision**: Fix `sdk/src/internal/http.ts` as part of this feature.

**Finding**: `request()` treats only `204` as body-less:

```typescript
return response.status === NO_CONTENT_STATUS ? (undefined as T) : ((await response.json()) as T);
```

An S3/MinIO `PUT` object answers **`200` with an empty body** (`docs/service/upload-flow.md` § 1
shows `200 OK`). Verified on the repo's Node 20.19.4:

```
new Response(null, { status: 200 }).json()
→ SyntaxError: Unexpected end of JSON input
```

So `client.upload()` rejects with a `SyntaxError` on the `PUT`, the `confirm` never runs, and the
request stays in `PENDING_UPLOAD`. US1 and US2 could never pass.

**Why it was never caught**: `sdk/tests/client.test.ts:106` mocks the storage `PUT` as
`jsonResponse(200, {})` — a JSON body where the real one is empty.

**Fix**: `request()` returns `undefined` when the response carries no body to parse, for any success
status, not only `204`. This is a defect fix: it adds no operation, option, type or endpoint, and no
call that succeeds today changes its result.

**Governance consequence**: `docs/sdk/code_rules.md` § 2 embeds the buggy snippet verbatim. Per the
constitution's Governance section, editing a code-rules file is an amendment, not an inline
implementation edit — so it is bundled with the amendment in R-006.

## R-005 — Exact backend behaviors the scenarios assert

Read from the service source, so scenarios assert what the system really does:

| Behavior | Verified in | Result |
|---|---|---|
| Create response | `ValidationController.create` | `201`, `{ requestId, status, uploadUrl }` |
| Confirm response | `ValidationController.confirm` | `202`, `{ requestId, status }` |
| Blank `filename`/`contentType` | `CreateValidationCommand` (`@NotBlank`) | `400`, `detail: "Validation failed"`, `errors: [{ field, message }]` |
| Unknown `requestId` | `ApiExceptionHandler.handleNotFound` | `404` Problem Details |
| Missing/invalid `X-Api-Key` | `ApiKeyFilter` | `401`, `detail: "Invalid X-Api-Key header"` |
| Confirm replay | `docs/service/upload-flow.md` § 4 | no-op, returns current status |
| Confirm without uploading | `docs/service/upload-flow.md` § 4 | size `0` → `verdict: FAIL, reason: "empty file"` |

**Trap found**: `@PathVariable UUID requestId` means a non-UUID path segment raises
`MethodArgumentTypeMismatchException`, which falls through to `handleUnexpected` → **`500`, not
`404`**. The not-found scenario therefore uses a well-formed but unused UUID
(`crypto.randomUUID()`), never a string like `"missing"`.

*(Note, out of scope for this feature: `sdk/README.md` § Errors illustrates the 404 case with
`client.getValidation("missing")`, which would actually return 500. Flagged, not changed.)*

## R-006 — Reconciling `example/` with the governing documents

**Decision**: Amend `.specify/memory/constitution.md` (1.1.0 → 1.2.0, MINOR), `CLAUDE.md` and
`README.md` in this same change, recognising `example/` as a third, non-publishable artifact whose
purpose is to consume the SDK as an external consumer. The same amendment updates the
`docs/sdk/code_rules.md` § 2 snippet required by R-004, and adds `example/README.md` to the
constitution's Documentation Index.

**Rationale**: Constitution principle I — code and docs never drift. Adding a top-level directory the
governing documents deny would put the repository in violation on the day it lands.

**Alternatives considered**:
- Put it in `sdk/examples/` — no governance change, but it stops being an integration (see R-001)
  (rejected by the user during clarification).
- Leave the docs alone — fastest, violates principle I (rejected by the user during clarification).

## R-007 — Producing the document set without committing binaries

**Decision**: The valid PDF is the committed `example/justificante.pdf` (~1 KB). The empty document is
a zero-byte buffer built at run time. The oversized document is a buffer of `15 MiB + 1` bytes
allocated at run time.

**Rationale**: FR-014. `docs/business-rules.md` § 5 fixes the ceiling at 15 × 1024 × 1024 bytes and
the rule reads `contentType` plus `sizeInBytes` only — content is irrelevant, so a filled buffer is a
faithful oversized document. A ~16 MB `PUT` to a local MinIO is a couple of seconds, well inside
SC-004.

## R-008 — Keeping runs repeatable

**Decision**: Every idempotency key is `example-<crypto.randomUUID()>`, minted per run.

**Rationale**: FR-015. `docs/business-rules.md` § 6 gives idempotency keys no TTL, so a fixed key
would make the second run replay the first run's request — already `COMPLETED` — and the scenario
asserting `PENDING_UPLOAD` would report a false failure.

## R-009 — The presigned URL signs the `Content-Type`

**Finding**: `S3DocumentStorageAdapter.createPresignedUpload` builds the request with
`.contentType(contentType)` before presigning:

```java
var putObjectRequest = PutObjectRequest.builder()
        ...
        .contentType(contentType)
```

With the content type set on the request, SigV4 includes `content-type` in `SignedHeaders`, so a
`PUT` that omits it or sends a different value is rejected by MinIO — the bytes are never stored and
`confirm` is never reached. Probed against the running stack, the two rejections differ:

| Client sends | MinIO answers |
|---|---|
| A *different* `Content-Type` (e.g. a `string` body, which `undici` types as `text/plain`) | `403` `SignatureDoesNotMatch` |
| *No* `Content-Type` at all | `400` `AccessDenied` — "There were headers present in the request which were not signed" |

Both are XML, so `ValidationApiError.body` is `undefined` either way. Scenario 14 omits the header,
so it asserts **`400`** — the value the system actually returns, not the `403` first assumed.

**Consequence for the scenarios**: every uploading scenario must pass the *same* content type it
declared at creation. Scenario 2 declares `image/png`, so it uploads with `image/png`; the resulting
`FAIL` comes from the business rule at processing time, not from storage.

**Consequence for coverage**: the SDK's documented "omit `contentType`" path cannot succeed against
this backend. Rather than drop it from coverage, scenario 14 covers it with the rejection as its
*expected* outcome. That scenario doubles as the only coverage of a documented SDK behavior nothing
else reaches: storage answers XML rather than Problem Details, so `ValidationApiError.body` is
`undefined` and the message falls back to `Request failed with status 400`
(`sdk/README.md` § Errors).

**Alternatives considered**:
- Make `upload()` derive the content type from the target — removes the trap, but changes the SDK's
  public surface, a decision already settled in `docs/sdk/code_rules.md` (rejected by the user).
- Drop the option from coverage and relax SC-002 — cheaper, but loses both the coverage and a real
  gotcha worth documenting (rejected by the user).
