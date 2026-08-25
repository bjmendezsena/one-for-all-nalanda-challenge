---
description: Implement a feature end-to-end via the spec-kit pipeline (specify -> clarify loop -> plan -> tasks -> analyze loop -> summary -> checklist -> implement), verifying every step against the project documentation and asking the user on any ambiguity/inconsistency.
argument-hint: <feature description>
model: opencode-go/kimi-k3
---

You orchestrate a feature from a one-line request to a working implementation using
the project's **spec-kit skills**, running **in this conversation** so you can ask the
user and continue across turns. **`docs/` is the single source of truth** (with
`README.md § Design trade-offs` as the authoritative record of *why* each decision was
made); the spec is authoritative for scope; the constitution governs. **Never invent scope.**

## Feature request

$ARGUMENTS

If empty, ask the user for the feature description before doing anything else.

## Project shape (know this before you plan anything)

This repository is a **two-artifact monorepo** — there is no frontend, no `shared/`
package, no app/packages workspace:

| Artifact | What it is | Where |
| --- | --- | --- |
| `service/` | Java / Spring Boot backend: REST API + asynchronous validation pipeline (Kafka consumer), hexagonal (ports & adapters), single Gradle module | `service/src/main/java/<base-package>/{domain,application,adapter,config}` |
| `sdk/` | TypeScript client library wrapping the HTTP API (dependency-free, native `fetch`, Vite library mode) | `sdk/src/` (+ `sdk/src/internal/`) |
| `docs/` | The documentation: `docs/business-rules.md` is shared; `docs/service/**` and `docs/sdk/**` are per-artifact | `docs/` |
| local infra | `postgres`, `kafka`, `minio` via one `docker-compose.yml` at the repo root | `docker-compose.yml` |

The two artifacts share exactly one contract: the HTTP API and the business rules in
`docs/business-rules.md`. A change to that contract touches **both** sides and both docs.

## How to use the documentation (READ THIS)

- **Do NOT read all of `docs/`.** Use the **Documentation Map** below to jump **only** to
  the doc(s) relevant to the current step, by path.
- **Each spec-kit skill already carries its own scoped "Documentation Context"** section
  (only what that role needs) - rely on it. This map is the master index for locating
  anything else or for the orchestration decisions in this command.
- If a doc conflicts with a spec-kit artifact, **the doc wins** - surface it as a question.
- `docs/` says *what/how*; `README.md § Design trade-offs` says *why*, and records the
  alternative that was already rejected. A decision recorded there is **settled**: to
  deviate, ask the user - never substitute silently.

## Documentation Map (go straight to what you need)

### Governance & scope

| Doc              | What you'll find                                                            | Path                                          |
| ---------------- | --------------------------------------------------------------------------- | --------------------------------------------- |
| Constitution     | Governing principles & acceptance gates                                     | `.specify/memory/constitution.md`             |
| Assessment brief | The original scope and hard requirements of the technical assessment        | `fullstack-engineer-technical-assessment.pdf` |
| Project overview | What the system does end-to-end, repo layout, how to run, § Design trade-offs | `README.md`                                   |

### Shared (service + SDK)

| Doc            | What you'll find                                                                                        | Path                     |
| -------------- | ------------------------------------------------------------------------------------------------------- | ------------------------ |
| Business rules | Domain concepts, status machine, endpoint semantics, input validation, verdict rule, idempotency, errors | `docs/business-rules.md` |

### Service (`service/`)

| Doc                                  | What you'll find                                                                        | Path                           |
| ------------------------------------ | --------------------------------------------------------------------------------------- | ------------------------------ |
| Service architecture                 | Hexagonal style, dependency rule, package structure, ports/adapters, local infra, scope  | `docs/service/architecture.md` |
| Service code rules (NON-NEGOTIABLE)  | Per-layer implementation conventions with examples + § 9 restrictions for AI assistants  | `docs/service/code_rules.md`   |
| Event catalog                        | `ProcessingRequested`: payload, producer, consumer, why there is no "completed" event    | `docs/service/events.md`       |
| Kafka                                | Topic, key, consumer group, serialization, partitioning, duplicate handling              | `docs/service/kafka.md`        |
| Upload flow                          | Create → presigned PUT → confirm sequence, request/response shapes, status edge cases    | `docs/service/upload-flow.md`  |

### SDK (`sdk/`)

| Doc                             | What you'll find                                                                       | Path                       |
| ------------------------------- | -------------------------------------------------------------------------------------- | -------------------------- |
| SDK architecture                | `sdk/` structure, public API surface, boundary with the backend                        | `docs/sdk/architecture.md` |
| SDK code rules (NON-NEGOTIABLE) | Client/http/errors/types/polling/testing conventions + § 9 restrictions for AI assistants | `docs/sdk/code_rules.md`   |

## Which docs each step needs (quick guide)

- **specify** -> assessment brief + `README.md` (overview) + business rules + upload flow + events + constitution. **Not** the implementation details.
- **clarify** -> same product/business context + the hard constraints (business rules, status machine, idempotency) + both architecture docs.
- **plan** -> constitution + `README.md § Design trade-offs` + both architecture docs + both code-rules docs + business rules + events + kafka + upload flow.
- **tasks** -> both architecture docs (where files go) + both code-rules docs (how they're written, test naming) + business rules.
- **analyze** -> constitution + business rules + both architecture and code-rules docs + events + kafka + upload flow + `README.md § Design trade-offs`.
- **checklist** -> assessment brief + `README.md` + business rules + upload flow + events + constitution.
- **implement** -> both code-rules docs (binding) + both architecture docs + business rules + events + kafka + upload flow + `README.md § Design trade-offs`.

## Communication

- Talk to the user in **Spanish**. Spec-kit artifacts stay as generated (English).
- Be concise: summarize what each skill changed and what you need; do not paste full output.
- **Never skip a step** and never start a later step before the current one is resolved.

## Workflow (strict order)

1. **Specify** - invoke `speckit-specify` with the feature request (verbatim). Do not pre-decide implementation details.
2. **Clarify loop** - invoke `speckit-clarify` to find ambiguities, checking the spec against the documentation.
   - No ambiguities -> go to 3.
   - Ambiguities -> **ask the user** (Question Protocol), one batch at a time. Apply answers to the spec, then **run `speckit-clarify` again** to catch anything remaining. **Repeat** until a clarify pass is clean.
3. **Plan** - invoke `speckit-plan`. State explicitly which artifact(s) the feature touches (`service/`, `sdk/`, or both) and whether the HTTP contract changes.
4. **Tasks** - invoke `speckit-tasks`.
5. **Analyze loop** - invoke `speckit-analyze` to check consistency across **spec, plan, tasks AND the documentation**.
   - No inconsistencies -> go to 6.
   - Inconsistencies -> **ask the user** (Question Protocol), apply answers to the affected artifact(s), then **run `speckit-analyze` again**. **Repeat** until clean.
6. **Summary & approval** - give a **clear, detailed but concise** summary: what will be built, the approach/how (which artifact(s), layers/packages touched, whether the API contract or the status machine changes), how it aligns with the docs/constitution, and risks. Ask explicitly: "¿Lanzo la implementación?" and **wait**.
7. **Checklist** - only after approval: invoke `speckit-checklist` to validate the quality of the spec.
8. **Implement** - after the checklist, invoke `speckit-implement` to build the feature per plan and tasks.

## Question Protocol (every clarify/analyze loop)

- Ask **only what's genuinely ambiguous/inconsistent**; group related questions.
- Each question presents **numbered options**, each with a one-line implication.
- **Mark the recommended option** (list it first, tagged "(recomendada)") and say briefly why, grounded in the documentation.
- **Always include a final option** for the user's own answer (e.g. "N) Otra (indícala)").
- After answers: restate how you'll apply them, update the artifact, and re-run the same skill.

## Guardrails

- **Docs win.** If an artifact contradicts `docs/` or the constitution, flag it - never silently follow the artifact.
- **Code rules are non-negotiable** (constitution III): every change to `service/` obeys `docs/service/code_rules.md`, every change to `sdk/` obeys `docs/sdk/code_rules.md`, including each file's § 9 "Restrictions for AI coding assistants". A violation is a rejected change, not a trade-off.
- **No invented scope, endpoints, fields, statuses, or events.** This is a deliberately narrow slice - extra surface is a defect. When unsure, ask.
- **Settled decisions stay settled.** Anything recorded in `README.md § Design trade-offs` (Kafka, MinIO + presigned URLs, Idempotency-Key semantics, Liquibase, RFC 7807, API-key stub, H2 + fakes instead of Testcontainers, factory-function SDK over native `fetch`, Vite library mode) is not re-opened silently - raise it as a question.
- **No new dependency, library, or infrastructure** without asking the human first (constitution IX).
- **Cross-artifact changes:** if the HTTP contract or a business rule changes, the change spans `service/`, `sdk/`, `docs/business-rules.md` and the affected per-artifact docs - all in the same change, or it is incomplete.
- During implement: smallest change in scope, tests as completion criteria (`should_<expectedBehavior>_when_<condition>()`), controlled errors as RFC 7807 Problem Details, docs updated in the same change.
- If a spec-kit skill fails or a prerequisite is missing, stop and report - do not work around the pipeline.
