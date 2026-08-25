# Phase 0 — Research: Service core flows

**Feature**: `002-service-core-flows` | **Date**: 2026-08-25

Every architectural decision this feature needs is already settled in `docs/` and
`README.md § Design trade-offs`. This file therefore records only the points the
documentation left open (or where two docs disagreed), the decision taken, and why.
Nothing here re-opens a settled decision.

## R-001 — Where the idempotency key is persisted

**Decision**: A nullable `idempotency_key` column on `validation_request`, with a unique
index. No separate table, no TTL column, no stored body hash.

**Rationale**: `README.md § Idempotency-Key concrete rules` fixes "no expiration" and
"conflicting body ignored", which removes every reason a separate record would exist. A
column plus a unique index enforces FR-024 at the database level with no join. Postgres and
H2 both allow multiple `NULL`s under a unique index, so requests created without a key are
unaffected.

**Alternatives considered**: A dedicated `validation_idempotency_key` table (key → request
id). Rejected: an extra table and join to support TTL/body-comparison features the docs
explicitly decided against (YAGNI, constitution scope discipline).

## R-002 — How `ValidationResult.fields` is persisted

**Decision**: Serialized JSON in a text column (`result_fields`), serialized and
deserialized by hand in the persistence mapper using the Jackson `ObjectMapper` already on
the classpath.

**Amended during implementation**: the column is `varchar(4000)`, not `clob`. Liquibase's `clob`
resolves to `text` on PostgreSQL and `CLOB` on H2, and Hibernate has no single mapping that
`ddl-auto: validate` accepts against both (`SqlTypes.CLOB` expects `oid` on PostgreSQL,
`SqlTypes.LONGVARCHAR` expects `varchar` on H2) — the service failed to start against the real
database until the type was changed. `varchar` is resolved identically by both, and the stubbed
payload is one filename.

**Rationale**: `fields` is a stub (`{"filename": "..."}`), is never queried, filtered, or
indexed, and must round-trip identically on PostgreSQL and on H2 — the test database, which is what
decides the column type (see the amendment above).

**Alternatives considered**: `jsonb` (rejected: PostgreSQL-only, reintroduces exactly the
dialect divergence `README.md § Integration testing strategy` already flags as the accepted
weak spot, for queryability nothing uses); a normalized key/value table (rejected:
over-engineering for a single stubbed field).

## R-003 — What builds the schema in persistence tests

**Decision**: Liquibase runs in the test profile against H2 in PostgreSQL mode.
`service/src/test/resources/application-test.yml` switches from `ddl-auto: create-drop` +
`liquibase.enabled: false` to `ddl-auto: validate` + Liquibase enabled.

**Rationale**: FR-023/SC-007 require the schema to be reproducible from the migrations
alone. Letting Hibernate generate the test schema means the changelog is never executed by
the suite and can silently drift from the entities; running Liquibase makes every
`@DataJpaTest` a regression test for the migrations, and `ddl-auto: validate` makes any
entity/changelog mismatch fail loudly. `@DataJpaTest` includes Liquibase auto-configuration,
so no extra wiring is needed.

**Constraint this imposes**: every changeset must use Liquibase's portable types
(`uuid`, `varchar`, `bigint`) and no PostgreSQL-only construct — which is what R-002
already concluded independently. `clob` turned out not to qualify (see the amendment in R-002).

**Alternatives considered**: Keeping `ddl-auto: create-drop` (rejected: leaves the changelog
untested); Testcontainers with a real PostgreSQL (rejected outright — the constitution and
`README.md § Integration testing strategy` forbid introducing it).

## R-004 — Concurrency guard on `confirm`

**Decision**: None. No `@Version` field, no version column, no pessimistic lock.

**Rationale**: `docs/service/events.md` § 4 and `docs/business-rules.md` § 6 describe the
status machine itself as the repeat-safety mechanism, and the constitution states
"state-machine guards, not luck, enforce this". Adding optimistic locking would introduce a
column, a new failure mode (`OptimisticLockException`) and a new Problem Details mapping that
no document describes — extra surface, which the constitution classifies as a defect.

**Residual risk accepted**: two truly simultaneous `confirm` calls on the same request could
each read `PENDING_UPLOAD` and both publish. The consumer's own status guard still ensures the
document is processed at most once, so the observable outcome is unchanged; only a duplicate
message is possible. Optimistic locking is the natural next step if this ever matters.

## R-005 — Conflict: the confirm response body

**Conflict**: `docs/service/upload-flow.md` § 2.3 specifies `202` with
`{ "requestId": ..., "status": "QUEUED" }`; the controller snippet in
`docs/service/code_rules.md` § 6 shows `ResponseEntity<Void>` / `.accepted().build()`.

**Decision (approved by the human)**: the body wins. Confirm returns `202` with
`{ requestId, status }`. `docs/service/code_rules.md` § 6 is corrected in the same change —
an approved amendment to a non-negotiable document, not a silent deviation.

**Rationale**: `upload-flow.md` is the document that defines endpoint shapes, and
`docs/business-rules.md` § 6 corroborates it ("returns success with the current status").
Without a body, a repeated confirm has no way to report the current status, which is the
observable behavior the idempotency rule exists to provide.

## R-006 — Conflict: what an idempotent create replay returns

**Conflict**: `docs/business-rules.md` § 6 says a replay "returns the original `requestId`
and response", and the original response contained an `uploadUrl` — but presigned URLs
expire and are not persisted, so the literal original URL cannot be reproduced.

**Decision (approved by the human)**: the replay re-signs an upload URL over the already
persisted `storageKey` and `contentType`, and returns it alongside the original `requestId`
and current status. No second `ValidationRequest` is created.

**Rationale**: the scenario the key protects against is a client retry after a timeout
(`README.md § Idempotency strategy`); returning upload instructions the client cannot use
would defeat it. The resource is unchanged — only the signature is fresh.

## R-007 — Inconsistency: `ValidationRequestRepository.save` signature

**Inconsistency**: `docs/service/code_rules.md` § 2 declares `save(ValidationRequest)`, while
§ 4 calls `repository.save(request, idempotencyKey)`.

**Decision**: the port declares both overloads —
`save(ValidationRequest)` and `save(ValidationRequest, String idempotencyKey)`. Both snippets
in the document then compile as written; no snippet has to change.

**Rationale**: the idempotency key belongs to the persistence record, not to the domain
entity (which the docs model without it), so passing it at save time is the shape the
documentation already implies. `ConfirmUploadUseCase` uses the single-argument overload;
`CreateValidationUseCase` uses the two-argument one.

## R-008 — Presigned URL expiry

**Decision**: 15 minutes, exposed as a configuration property with that default alongside
the other storage properties in `application.yml`.

**Rationale**: no document specifies a value. 15 minutes is the common default for a
single-object upload — long enough for a large document on a slow link, short enough that a
leaked URL is not a lasting exposure. It is a configuration knob that already has a home in
the existing `storage.*` block, not new surface.

## R-009 — Distinguishing "no object" from "storage is broken" during processing

**Decision**: a missing object (the storage backend reports the key does not exist) is
reported as size `0`, which the deterministic rule turns into `verdict: FAIL`,
`reason: "empty file"`. Any other storage failure propagates and the request ends `FAILED`.

**Rationale**: `docs/service/upload-flow.md` § 4 states this explicitly for the
"confirmed but never uploaded" case. Treating a genuine outage the same way would report a
conclusive business answer the system never actually computed, which
`docs/business-rules.md` § 2 forbids by making `FAILED` distinct from `verdict: FAIL`.

## R-010 — API key filter scope

**Decision**: a single `OncePerRequestFilter` registered for `/api/v1/**`, comparing the
`X-Api-Key` header against a configured value (`security.api-key`, safe local default), and
rejecting a missing or wrong key with `401` as a Problem Details body written through the
same central mapping as every other error.

**Rationale**: `README.md § Authentication` fixes the stub as "a filter/interceptor requiring
a configured `X-Api-Key` header, returning 401 if missing/incorrect". Spring Security is not
introduced — no new dependency, per constitution IX. Scoping to `/api/v1/**` keeps the filter
off anything that is not the documented API surface.

## R-011 — Request correlation in logs

**Decision**: the same filter chain places a correlation id in the SLF4J MDC for the life of
the request — taken from an `X-Request-Id` header when the client sends one, otherwise
generated — and clears it afterwards. The Kafka consumer puts the `validationRequestId` in
the MDC for the life of the message.

**Rationale**: constitution VIII requires requests to be correlated in structured logs.
MDC + the Logback already bundled with Spring Boot needs no new dependency. Request bodies are
never logged, per the same principle.

## R-012 — No new dependencies

**Confirmation**: this feature adds no dependency, library, or infrastructure.
`S3Presigner` ships inside `software.amazon.awssdk:s3`, Jackson comes with
`spring-boot-starter-web`, MDC/Logback comes with `spring-boot-starter`, and the API key
filter is a plain servlet filter. Constitution IX is satisfied without a question to the
human.

## R-013 — Conflict: is the document metadata field `final`?

**Conflict**: `docs/service/code_rules.md` § 1 declares
`private final DocumentMetadata document;`, while `docs/service/upload-flow.md` § 2.1 states
that `sizeInBytes` is `0` at creation because the file has not been uploaded yet, and § 2.4
has the size discovered during processing. A `final` field cannot carry a size discovered later.

**Decision (approved by the human)**: the discovered size is recorded on the request and
persisted. The field stops being `final`, and `ValidationRequest` exposes
`recordDiscoveredSize(long)` — a transition method of the aggregate, not a public setter, so
the no-anemic-model rule still holds. `docs/service/code_rules.md` § 1 is corrected in the same
change (approved amendment to a non-negotiable document).

**Rationale**: keeping `final` would leave `DocumentMetadata.sizeInBytes` — a field the same
non-negotiable document requires — permanently `0`, which contradicts `upload-flow.md` § 2.4
and makes the persisted `size_in_bytes` column meaningless.

**Alternatives considered**: keeping the size as a local variable inside the processing use
case (rejected: the domain field and its column would never hold anything); threading the size
through `complete(result, size)` (rejected: it still requires dropping `final`, and it
overloads a method whose job is the status transition).

## R-014 — Where the two servlet filters live

**Decision**: `ApiKeyFilter` and `CorrelationIdFilter` live in `adapter/in/web`, registered by
`config/WebFilterConfig`.

**Rationale**: `docs/service/architecture.md` § 4.4 explicitly allows the auth stub in either
`config` or `adapter/in/web`. Choosing `adapter/in/web` means their tests land in the
`adapter/in/web` test package the architecture document already defines in § 4.2, rather than
inventing a `config` test package the documented test tree does not contain (constitution IV).
