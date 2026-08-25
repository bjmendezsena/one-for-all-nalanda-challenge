# Phase 0 research: SDK client

No `NEEDS CLARIFICATION` remained in the Technical Context — the stack, bundler, test runner,
error shape, polling strategy and module format were all settled before this feature (see
`README.md § Design trade-offs`). What follows records the decisions this feature still had to
make, each one either taken from a doc or cleared with the human.

## D-001 — Where the confirm call lives

**Decision**: `upload(target, data, contentType?)` performs the presigned `PUT` and then the
`POST /api/v1/validations/{requestId}/confirm`. No separate confirm operation is exposed.

**Rationale**: `docs/sdk/architecture.md` § 4 lists five entries and none of them is a confirm
operation, while `docs/service/upload-flow.md` § 2.3 makes the confirm call mandatory to leave
`PENDING_UPLOAD`. The upload operation is the only point at which the SDK knows the bytes were
sent, which is exactly the signal confirm represents. Cleared with the human and recorded in
`spec.md § Clarifications`.

**Alternatives considered**: exposing `confirmUpload(requestId)` as a sixth operation (more
explicit, 1:1 with the HTTP flow, but grows the documented surface); keeping the literal
`upload(requestId, data)` signature (impossible — the client holds no state and
`GET /api/v1/validations/{id}` does not return `uploadUrl`, so the upload destination has to be
passed in).

## D-002 — Shape of `ClientOptions`

**Decision**: `{ baseUrl: string; apiKey: string; headers?: Record<string, string> }`.

**Rationale**: `ApiKeyFilter` rejects every request without a valid `X-Api-Key` with `401`, so a
client built without a credential can never work — making it required fails fast at the type
level instead of at runtime. The optional `headers` map matches the `...options.headers` spread
in the `docs/sdk/code_rules.md` § 1 example. Cleared with the human.

**Alternatives considered**: `{ baseUrl, apiKey }` only (drops the documented `headers` spread);
`{ baseUrl, headers }` with the consumer setting `X-Api-Key` by hand (matches the § 6 test
literally but pushes a contract detail onto the consumer that the SDK exists to encapsulate).

## D-003 — How the idempotency key is supplied

**Decision**: an optional second argument,
`startValidation(input, callOptions?: { idempotencyKey?: string })`.

**Rationale**: `docs/sdk/code_rules.md` § 4 defines the SDK's types as a mirror of the wire, and
`Idempotency-Key` is a header, not a body field — putting it inside `StartValidationInput` would
break that mirror. Cleared with the human.

**Alternatives considered**: a field on `StartValidationInput` (simpler call site, breaks § 4);
not supporting it (leaves a documented business rule, `docs/business-rules.md` § 6, unreachable
through the SDK).

## D-004 — The presigned upload does not carry the API credential

**Decision**: the `PUT` to `uploadUrl` sends only `Content-Type`; no `X-Api-Key`, no merged
`options.headers`.

**Rationale**: `docs/service/upload-flow.md` § 2.2 states the backend is not involved in that
request at all — it goes straight to object storage, which does not know the API key. The URL
already carries its own signature, and S3-compatible signatures cover the headers sent with the
request, so adding unrelated headers risks invalidating it. Sending the credential to a third
party would also violate constitution VIII.

**Alternatives considered**: reusing the same header map for every request (simpler code, wrong
and leaky).

## D-005 — Failures of the presigned upload

**Decision**: a non-OK `PUT` response throws `ValidationApiError` with the storage response's
status and whatever body could be parsed; the confirm is not attempted.

**Rationale**: constitution V requires every error a consumer sees to be `ValidationApiError`,
with no exception for the storage hop. Skipping the confirm keeps the request in
`PENDING_UPLOAD` rather than queueing processing for bytes that were never stored — which is the
honest state, and the same place the client would be if it had never called `upload`. Note the
storage error body will not be Problem Details (MinIO answers XML); `ValidationApiError` degrades
to the status plus its fallback message, which D-006 already covers.

**Alternatives considered**: confirming anyway (would produce a misleading `verdict: FAIL,
reason: "empty file"` later, hiding a transport failure behind a business verdict).

## D-006 — Unparsable or absent error bodies

**Decision**: `request()` parses the body with `.catch(() => undefined)` and
`ValidationApiError` falls back to `Request failed with status ${status}` when there is no
`detail`.

**Rationale**: this is exactly the helper and the constructor shown in `docs/sdk/code_rules.md`
§ 2 and § 3. It covers `401` bodies, MinIO's XML errors and empty bodies without a second code
path.

## D-007 — Deterministic tests for the backoff

**Decision**: Vitest fake timers (`vi.useFakeTimers()`) drive `waitForCompletion`'s sleeps and
deadline; `fetch` stays stubbed with `vi.stubGlobal`.

**Rationale**: constitution VII requires deterministic tests, and a real 250 ms → 5 000 ms
backoff would make the suite slow and flaky. Fake timers are part of Vitest itself, so no
dependency is added (constitution IX).

**Alternatives considered**: shrinking the delays through the public options and sleeping for
real (still non-deterministic, and it would not exercise the default values).

## D-008 — Empty successful bodies

**Decision**: `request()` returns `undefined` when the response status is `204`.

**Rationale**: taken verbatim from `docs/sdk/code_rules.md` § 2. No current endpoint returns
`204`, but the helper is the single call site and the rule already specifies the behaviour, so it
is implemented as written rather than invented later.
