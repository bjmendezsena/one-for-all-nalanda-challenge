---
name: 'speckit-plan'
description: 'Execute the implementation planning workflow using the plan template to generate design artifacts.'
argument-hint: 'Optional guidance for the planning phase'
compatibility: 'Requires spec-kit project structure with .specify/ directory'
metadata:
  author: 'github-spec-kit'
  source: 'templates/commands/plan.md'
user-invocable: true
disable-model-invocation: false
---

<!-- NALANDA-DOC-CONTEXT -->
## Nalanda validation service - Documentation Context (read ONLY what you need)

> **Role of this skill:** Design the technical implementation. This is the heavy step: use the full technical context below (service + SDK).
> `docs/` (plus `README.md` for the rationale behind each decision) is the **single source of truth**; if a doc conflicts with an artifact, the doc wins.
> **Go straight to the specific doc(s) below for this task - do NOT read all of `docs/`.** Anything not listed is out of scope for this step; for the full index see the Documentation Index in `.specify/memory/constitution.md`.
> The code rules (`docs/service/code_rules.md`, `docs/sdk/code_rules.md`) are **non-negotiable**: every code change complies with the rules of its artifact, or it is rejected.

| Doc                                 | What you'll find                                                                                                    | Path                              |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------- | --------------------------------- |
| Constitution                        | Governing principles & acceptance gates                                                                             | `.specify/memory/constitution.md` |
| Project overview & trade-offs       | What the system does end-to-end, repo layout, how to run, § Design trade-offs (decision + alternatives + rationale) | `README.md`                       |
| Service architecture                | Hexagonal layers, dependency rule, package structure, ports/adapters, infra boundaries                              | `docs/service/architecture.md`    |
| Service code rules (NON-NEGOTIABLE) | Backend implementation conventions per layer, with examples                                                         | `docs/service/code_rules.md`      |
| SDK architecture                    | SDK structure, public API surface, mapping to the HTTP API                                                          | `docs/sdk/architecture.md`        |
| SDK code rules (NON-NEGOTIABLE)     | SDK implementation conventions, with examples                                                                       | `docs/sdk/code_rules.md`          |
| Business rules (shared)             | Domain concepts, status machine, validation rules, errors — shared by `service/` and `sdk/`                         | `docs/business-rules.md`          |
| Event catalog                       | Events, payloads, producers, consumers, delivery semantics                                                          | `docs/service/events.md`          |
| Kafka                               | Topic, key, consumer group, serialization, partitioning, duplicate handling                                         | `docs/service/kafka.md`           |
| Upload flow                         | Presigned-upload sequence, request/response shapes, status transitions, edge cases                                  | `docs/service/upload-flow.md`     |
<!-- /NALANDA-DOC-CONTEXT -->

## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Pre-Execution Checks

**Check for extension hooks (before planning)**:

- Check if `.specify/extensions.yml` exists in the project root.
- If it exists, read it and look for entries under the `hooks.before_plan` key
- If the YAML cannot be parsed or is invalid, skip hook checking silently and continue normally
- Filter out hooks where `enabled` is explicitly `false`. Treat hooks without an `enabled` field as enabled by default.
- For each remaining hook, do **not** attempt to interpret or evaluate hook `condition` expressions:
  - If the hook has no `condition` field, or it is null/empty, treat the hook as executable
  - If the hook defines a non-empty `condition`, skip the hook and leave condition evaluation to the HookExecutor implementation
- When constructing slash commands from hook command names, replace dots (`.`) with hyphens (`-`). For example, `speckit.git.commit` → `/speckit-git-commit`.
- For each executable hook, output the following based on its `optional` flag:
  - **Optional hook** (`optional: true`):
    ```
    ## Extension Hooks

    **Optional Pre-Hook**: {extension}
    Command: `/{command}`
    Description: {description}

    Prompt: {prompt}
    To execute: `/{command}`
    ```
  - **Mandatory hook** (`optional: false`):
    ```
    ## Extension Hooks

    **Automatic Pre-Hook**: {extension}
    Executing: `/{command}`
    EXECUTE_COMMAND: {command}

    Wait for the result of the hook command before proceeding to the Outline.
    ```
- If no hooks are registered or `.specify/extensions.yml` does not exist, skip silently

## Outline

1. **Setup**: Run `.specify/scripts/bash/setup-plan.sh --json` from repo root and parse JSON for FEATURE_SPEC, IMPL_PLAN, SPECS_DIR, BRANCH. For single quotes in args like "I'm Groot", use escape syntax: e.g 'I'\''m Groot' (or double-quote if possible: "I'm Groot").

2. **Load context**: Read FEATURE_SPEC and `.specify/memory/constitution.md`. Load IMPL_PLAN template (already copied).

3. **Execute plan workflow**: Follow the structure in IMPL_PLAN template to:
   - Fill Technical Context (mark unknowns as "NEEDS CLARIFICATION")
   - Fill Constitution Check section from constitution
   - Evaluate gates (ERROR if violations unjustified)
   - Phase 0: Generate research.md (resolve all NEEDS CLARIFICATION)
   - Phase 1: Generate data-model.md, contracts/, quickstart.md
   - Phase 1: Update agent context by running the agent script
   - Re-evaluate Constitution Check post-design

4. **Stop and report**: Command ends after Phase 2 planning. Report branch, IMPL_PLAN path, and generated artifacts.

5. **Check for extension hooks**: After reporting, check if `.specify/extensions.yml` exists in the project root.
   - If it exists, read it and look for entries under the `hooks.after_plan` key
   - If the YAML cannot be parsed or is invalid, skip hook checking silently and continue normally
   - Filter out hooks where `enabled` is explicitly `false`. Treat hooks without an `enabled` field as enabled by default.
   - For each remaining hook, do **not** attempt to interpret or evaluate hook `condition` expressions:
     - If the hook has no `condition` field, or it is null/empty, treat the hook as executable
     - If the hook defines a non-empty `condition`, skip the hook and leave condition evaluation to the HookExecutor implementation
   - When constructing slash commands from hook command names, replace dots (`.`) with hyphens (`-`). For example, `speckit.git.commit` → `/speckit-git-commit`.
   - For each executable hook, output the following based on its `optional` flag:
     - **Optional hook** (`optional: true`):
       ```
       ## Extension Hooks

       **Optional Hook**: {extension}
       Command: `/{command}`
       Description: {description}

       Prompt: {prompt}
       To execute: `/{command}`
       ```
     - **Mandatory hook** (`optional: false`):
       ```
       ## Extension Hooks

       **Automatic Hook**: {extension}
       Executing: `/{command}`
       EXECUTE_COMMAND: {command}
       ```
   - If no hooks are registered or `.specify/extensions.yml` does not exist, skip silently

## Phases

### Phase 0: Outline & Research

1. **Extract unknowns from Technical Context** above:
   - For each NEEDS CLARIFICATION → research task
   - For each dependency → best practices task
   - For each integration → patterns task

2. **Generate and dispatch research agents**:

   ```text
   For each unknown in Technical Context:
     Task: "Research {unknown} for {feature context}"
   For each technology choice:
     Task: "Find best practices for {tech} in {domain}"
   ```

3. **Consolidate findings** in `research.md` using format:
   - Decision: [what was chosen]
   - Rationale: [why chosen]
   - Alternatives considered: [what else evaluated]

**Output**: research.md with all NEEDS CLARIFICATION resolved

### Phase 1: Design & Contracts

**Prerequisites:** `research.md` complete

1. **Extract entities from feature spec** → `data-model.md`:
   - Entity name, fields, relationships
   - Validation rules from requirements
   - State transitions if applicable

2. **Define interface contracts** (if project has external interfaces) → `/contracts/`:
   - Identify what interfaces the project exposes to users or other systems
   - Document the contract format appropriate for the project type
   - Examples: public APIs for libraries, command schemas for CLI tools, endpoints for web services, grammars for parsers, UI contracts for applications
   - Skip if project is purely internal (build scripts, one-off tools, etc.)

3. **Agent context update**:
   - Update the plan reference between the `<!-- SPECKIT START -->` and `<!-- SPECKIT END -->` markers in `CLAUDE.md` to point to the plan file created in step 1 (the IMPL_PLAN path)

**Output**: data-model.md, /contracts/*, quickstart.md, updated agent context file

## Key rules

- Use absolute paths for filesystem operations; use project-relative paths for references in documentation and agent context files
- ERROR on gate failures or unresolved clarifications
