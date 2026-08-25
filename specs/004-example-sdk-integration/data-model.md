# Phase 1 data model: Example project integrating the SDK

**Feature**: `004-example-sdk-integration` | **Date**: 2026-08-25

The example holds no persistent state. These are the in-process shapes its runner works with.

## Scenario

One named, independently runnable demonstration. Declared statically in `example/src/scenarios.ts`.

| Field | Type | Meaning |
|---|---|---|
| `id` | `string` | Stable short identifier used in the report (`happy-path`, `verdict-empty`, …) |
| `title` | `string` | One line describing what the scenario does |
| `covers` | `string` | The SDK surface or business rule this scenario is the evidence for |
| `expected` | `string` | The outcome the scenario asserts, printed before it runs |
| `run` | `(context) => Promise<string>` | Performs the scenario; resolves with the observed outcome, or throws to fail |

A scenario fails by throwing. The message it throws is what the report shows as the observed outcome,
so every assertion states what it wanted and what it got.

## ScenarioContext

Passed to every `run`. Created once per process.

| Field | Type | Meaning |
|---|---|---|
| `client` | `ValidationClient` | The SDK client built from the resolved configuration |
| `config` | `ExampleConfig` | Resolved base URL and API key |
| `documents` | `DocumentSet` | The document set for this run |
| `newIdempotencyKey` | `() => string` | Mints `example-<uuid>` so runs stay repeatable (R-008) |

## ScenarioOutcome

Produced by the runner, one per executed scenario.

| Field | Type | Meaning |
|---|---|---|
| `scenario` | `Scenario` | The scenario this outcome belongs to |
| `passed` | `boolean` | Whether the observed outcome matched the expected one |
| `observed` | `string` | What actually happened, in one line |
| `durationMs` | `number` | Wall-clock time of the scenario |

## RunReport

The ordered outcomes plus the totals that decide the exit code.

| Field | Type | Meaning |
|---|---|---|
| `outcomes` | `ScenarioOutcome[]` | In execution order |
| `passed` | `number` | Count of passing scenarios |
| `failed` | `number` | Count of failing scenarios |

**Rule**: the process exits `0` only when `failed === 0` (FR-010). One failure never stops the
remaining scenarios (FR-011).

## DocumentSet

The documents the scenarios feed to the API (FR-014).

| Field | Type | Source | Purpose |
|---|---|---|---|
| `valid` | `Uint8Array` | `example/justificante.pdf`, committed (~1 KB) | The `PASS` scenario |
| `empty` | `Uint8Array` | Built at run time, length `0` | `verdict: FAIL, reason: "empty file"` |
| `oversized` | `Uint8Array` | Built at run time, `15 × 1024 × 1024 + 1` bytes | `verdict: FAIL, reason: "file too large"` |

The size ceiling is a named constant next to the rule, mirroring `docs/business-rules.md` § 5 — never
an inline literal.

## ExampleConfig

| Field | Type | Environment variable | Default |
|---|---|---|---|
| `baseUrl` | `string` | `BASE_URL` | `http://localhost:8080` |
| `apiKey` | `string` | `API_KEY` | `local-dev-api-key` |

The defaults are the documented local-development values (`service/src/main/resources/application.yml`
line 34). No secret is committed (FR-012).

## Status and verdict vocabulary

The example reuses the SDK's published `ValidationStatus` and `Verdict` types verbatim — it declares
no parallel enum. The three documented failure reasons live as named constants in `scenarios.ts`,
quoting `docs/business-rules.md` § 5:

- `"unsupported content type"`
- `"empty file"`
- `"file too large"`

The report labels a `FAIL` verdict explicitly as a completed check, never as a failed request
(spec US2 acceptance scenario 4).
