# one-for-all-nalanda-challenge — agent context

Two artifacts, one monorepo: `service/` (Java / Spring Boot backend, hexagonal) and
`sdk/` (TypeScript client library). Local infra (`postgres`, `kafka`, `minio`) comes up
with one `docker compose up` from the repo root. There is **no frontend** and no shared
workspace package — the only contract between the two artifacts is the HTTP API plus
`docs/business-rules.md`.

## Sources of truth

- `docs/` — what the system does and how it is built (`docs/business-rules.md` is shared;
  `docs/service/**` and `docs/sdk/**` are per-artifact).
- `README.md § Design trade-offs` — **why** each decision was made, and which alternative
  was rejected. A decision recorded there is settled: ask before deviating.
- `.specify/memory/constitution.md` — governing principles and acceptance gates.

## Non-negotiable

`docs/service/code_rules.md` and `docs/sdk/code_rules.md` are **mandatory and strictly
binding** for every code change in their artifact, including each file's § 9
"Restrictions for AI coding assistants" (constitution III). A violation is a rejected
change, not a trade-off.

## Spec-kit

Features are built through spec-kit: `/feature <description>` orchestrates
specify → clarify → plan → tasks → analyze → summary → checklist → implement, with review
gates. Individual steps are also available as skills (`speckit-specify`, `speckit-plan`,
…). Feature artifacts live in `specs/<###-feature-name>/`.

<!-- SPECKIT START -->
Active feature plan: `specs/003-sdk-client/plan.md`
<!-- SPECKIT END -->
