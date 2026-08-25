# AI usage

This document discloses how AI tools were used while building this project, per the assignment's "AI usage requirements". It is written in the first person, from the candidate's perspective.

## 1. Which AI tools were used, and for which parts

**Research, documentation, and architecture/technology decisions — Claude Code Desktop (Cowork).** The entire investigation phase — reading and breaking down the assignment PDF, designing the hexagonal architecture, deciding the persistence/messaging/storage stack, writing every file under `docs/`, and working through every entry in `README.md § Design trade-offs` — was done with Claude Code Desktop. It was chosen for this phase because its agentic workflow is pleasant to use, highly usable, and very efficient specifically for iterative research and documentation: proposing options, discussing trade-offs, and writing them down as the conversation progresses.

**Coding (both `service/` and `sdk/`) — Claude Code (CLI), and occasionally OpenCode.** For the implementation phase itself, both tools are used because they are efficient at code generation and execution:

- **Claude Code (CLI)**, running **Claude Opus 5**, is the primary coding tool.
- **OpenCode** is used occasionally. Its main advantage is that it allows switching freely between different underlying models — in this project, it was used to run **DeepSeek**.
- Coding is driven with **spec-kit**, GitHub's framework for Spec-Driven Development (SDD). It is used because it keeps the LLM on rails and prevents hallucination: it forces a spec and a task breakdown before any code is generated, which gives full context and full control over what is being built. It also makes it possible to review each task individually before it runs, catch ambiguities early, and hold the generated code to a consistent quality bar, rather than accepting whatever a single large, unreviewed prompt produces.
- The **GitHub MCP server** is used to create commits and pull requests directly from the coding session.
- A custom slash command, **`/feature`**, was written to keep the spec-kit workflow consistent and repeatable every time a new feature is implemented — the same spec → plan → task → review → implement sequence, every time, instead of improvising the process per feature.

Because the architecture, business rules, and coding conventions are already fully specified in `docs/` before any code is written (`docs/business-rules.md`, `docs/service/*`, `docs/sdk/*`), the choice of model or tool for the coding phase matters less than it otherwise would: any model driven by spec-kit against this documentation should converge on comparably consistent code quality, because it is following the same spec rather than inventing its own design.

**Observed difference between models (Claude Opus 5 via Claude Code vs. DeepSeek via OpenCode):** the practical difference was speed, not quality. Claude Code with Opus 5 is noticeably faster, follows the existing context more reliably, and executes actions (edits, commands, tool calls) more quickly. DeepSeek via OpenCode is slower to work with, and its output needs a bit more review to confirm the existing context and conventions were actually understood before proceeding.

**Result:** 0% of the code in this project was hand-written. The large majority of the time budget went into documentation, research, and decision-making (the `docs/` folder and `README.md § Design trade-offs`); once that groundwork was in place, generating the actual code was comparatively fast.

## 2. AI suggestions rejected

**Anemic domain model.** Left unguided, the default AI-generated shape for `ValidationRequest` would have been a plain data holder — public getters/setters and status changed by assigning a field from outside — with all behavior (status transitions, validation of legal transitions) living in a separate service class. This was explicitly rejected in favor of a rich domain model: `ValidationRequest` owns its own transitions through methods (`confirmUpload()`, `startProcessing()`, `complete(result)`, `fail()`) and enforces valid state transitions itself, throwing `InvalidStatusTransitionException` when one is attempted out of order. This is not a stylistic preference — the assignment explicitly names avoiding an anemic domain as a signal of backend judgment, and lists it by name as an example of an AI suggestion worth rejecting. See `README.md § Design trade-offs → Domain model style` and `docs/service/code_rules.md` § 1.

**Wrong module format in the SDK.** The default/easiest AI-generated shape for a "dual ESM+CJS" TypeScript package tends to mix module syntax inside `src/` itself (e.g. a stray `module.exports` or `require(...)` alongside `import`/`export`, "to be safe" for both targets) and lean on the bundler to paper over the inconsistency. This was explicitly rejected: `src/` is pure ESM only (`import`/`export` exclusively), and the dual ESM/CJS/`.d.ts` output is produced exclusively by Vite's library-mode build — nothing hand-written in `src/` is aware a CJS build even exists. The assignment names "wrong module format" by name as an example of an AI suggestion to reject, which is exactly this failure mode. See `README.md § Design trade-offs → SDK module format` and `docs/sdk/code_rules.md` § 7.

## 3. Verifying the ESM/CJS dual build

Two layers of verification were run, not just one — building the package is not the same as proving a real consumer can actually load it in both module systems.

**Layer 1 — build the SDK and load both entry points directly.** From `sdk/`:

```bash
npm install
npm run build        # vite build && tsc --project tsconfig.build.json
```

This produced `dist/index.js` (ESM), `dist/index.cjs` (CJS), and the `.d.ts` files (`dist/index.d.ts` plus one per module). Then each entry point was loaded directly, in its own module system, and the exports compared:

```bash
node -e "const sdk = require('./dist/index.cjs'); console.log(Object.keys(sdk));"
# → CJS exports: [ 'ValidationApiError', 'createClient' ]

node --input-type=module -e "import * as sdk from './dist/index.js'; console.log(Object.keys(sdk));"
# → ESM exports: [ 'ValidationApiError', 'createClient' ]
```

Both loaded successfully and exposed the identical public API. `npm run typecheck` (`tsc --noEmit`) and `npm test` (`vitest run`) were also run at this point — clean typecheck, 30/30 tests passing across the three test files.

**Layer 2 — install the package as a real consumer would, and import it by name.** `sdk/examples/validate-document.mjs` imports the SDK by a relative path (`../dist/index.js`), which does not exercise the package's `exports` map, its `files` allowlist, or the published `.d.ts` — exactly the three things that break when a library is packaged wrong. `example/` exists specifically to close that gap: it's a separate package (`@nalanda/validation-sdk-example`) that depends on the SDK via `"@nalanda/validation-sdk": "file:../sdk"` and imports it **by package name** (`import { createClient, ValidationApiError } from "@nalanda/validation-sdk"`), the same way an npm consumer would after `npm install`.

```bash
cd example
npm install
# → node_modules/@nalanda/validation-sdk resolves to ../../sdk (the file: dependency)

npm run typecheck     # tsc --noEmit, resolving types through the package's "exports"."types" condition
npm run build         # tsc
node dist/main.js
```

`typecheck` and `build` both succeeded — TypeScript resolved the SDK's public types through `package.json`'s `exports` map exactly as a real consumer's tooling would, not through a relative path into `src/`. Running the compiled example got as far as attempting its first HTTP call and failed with `Cannot reach http://localhost:8080 — the example needs a running backend` (a `fetch` connection error) — which confirms the import itself resolved and ran; the only missing piece was the live backend, which is outside the scope of this specific check (see `example/README.md` for running the example end-to-end against the real stack).

Together, these two layers confirm the dual build works both in isolation (direct `require`/`import` of the built files) and as an installed dependency (resolution through the `exports` map, the way `npm install` would actually deliver it to a consumer).

## 4. What I would not trust AI to own without human review

Anything related to the product itself: the decisions, not the execution of them. Concretely — the architecture, the choice of technologies, the methodology used to build the project, and every trade-off recorded in `README.md § Design trade-offs`. All of that was driven interactively, option by option, with a human decision and a human-stated reason behind each one (see the process described at the top of this project's design conversations) — an AI tool was never left to pick an architecture, a technology, or a methodology on its own and simply informed after the fact. Execution — writing the code that implements an already-decided design — is where AI tools did the large majority of the work; deciding what that design should be was not delegated.
