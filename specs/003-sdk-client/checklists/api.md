# API Requirements Quality Checklist: SDK client for the document validation API

**Purpose**: Validate the quality of the written requirements for the SDK's public contract —
completeness, clarity, consistency, measurability, scenario coverage, and traceability to
`docs/`. This checklist tests the requirements, not the implementation.
**Created**: 2026-08-25
**Feature**: [spec.md](../spec.md)

## Requirement Completeness

- [x] CHK001 - Is every operation of the public surface backed by a requirement that names its inputs and its returned shape? [Completeness, Spec §FR-002..FR-006]
- [x] CHK002 - Are requirements defined for the authentication credential on every call, including the one call that must NOT carry it? [Completeness, Spec §FR-004, §FR-007]
- [x] CHK003 - Is the idempotency-key path specified — both when the caller supplies one and when they do not? [Completeness, Spec §FR-003]
- [x] CHK004 - Are requirements defined for what the library exports and what it must not export? [Completeness, Spec §FR-012]
- [x] CHK005 - Is the documentation that must ship with the change enumerated, rather than left as "update the docs"? [Completeness, Spec §FR-016, §FR-018, §FR-019]
- [x] CHK006 - Are requirements stated for the non-goals — what the library deliberately does not do? [Completeness, Contract §Non-goals]

## Requirement Clarity

- [x] CHK007 - Is the ordering inside the upload operation stated unambiguously (which call runs first, and what suppresses the second)? [Clarity, Spec §FR-005]
- [x] CHK008 - Are the polling defaults given as concrete values rather than adjectives like "reasonable" or "short"? [Clarity, Spec §FR-011, data-model.md §WaitForCompletionOptions]
- [x] CHK009 - Is "final state" defined by naming the exact statuses, so no reader has to infer which ones terminate the wait? [Clarity, Spec §FR-010, business-rules.md §2]
- [x] CHK010 - Is the distinction between a failed call and a negative verdict stated explicitly in the requirements, not just in the shared business rules? [Clarity, Spec §Edge Cases]
- [x] CHK011 - Is the behaviour when the optional content type is omitted on upload specified? [Clarity, Contract §Behavioural guarantees 2]

## Requirement Consistency

- [x] CHK012 - Do the spec, the plan, the data model and the contract all describe the same operation count and the same signatures? [Consistency, Spec §FR-002, plan.md §Design decisions, contracts/sdk-public-api.md]
- [x] CHK013 - Does the status vocabulary used in the requirements match `docs/business-rules.md` § 2 exactly, with no invented or renamed status? [Consistency, data-model.md §Wire vocabularies]
- [x] CHK014 - Are the documented examples in `docs/sdk/code_rules.md` reconciled with the clarified surface, so no two documents describe different signatures? [Conflict, Spec §FR-019]
- [x] CHK015 - Is the requirement that the HTTP contract stays unchanged consistent with every other requirement in the spec? [Consistency, Spec §FR-017]

## Acceptance Criteria Quality

- [x] CHK016 - Can each success criterion be judged without reading the implementation? [Measurability, Spec §SC-001..SC-006]
- [x] CHK017 - Is the coverage expectation stated as something checkable rather than "well tested"? [Measurability, Spec §SC-003, §FR-014]
- [x] CHK018 - Is the "no infrastructure required" constraint on the test suite stated as a requirement, not an aspiration? [Measurability, Spec §FR-014, §SC-003]
- [x] CHK019 - Does each user story carry an independent test statement that does not depend on another story being done? [Acceptance Criteria, Spec §User Story 1..4]

## Scenario Coverage

- [x] CHK020 - Are requirements present for the primary flow end to end (create → upload → confirm → read)? [Coverage, Spec §User Story 1]
- [x] CHK021 - Are requirements present for the alternate flow where the caller polls manually instead of using the wait helper? [Coverage, Spec §User Story 3]
- [x] CHK022 - Are requirements present for every exception class the API can return, including the one produced by the storage hop? [Coverage, Spec §FR-008, §User Story 2]
- [x] CHK023 - Are recovery/cancellation requirements defined for an in-flight wait? [Coverage, Spec §FR-010, §User Story 3]
- [x] CHK024 - Are adoption-level requirements (importable surface, both module systems, runnable example) covered rather than assumed? [Coverage, Spec §User Story 4]

## Edge Case Coverage

- [x] CHK025 - Is the trailing-separator base address addressed in the requirements? [Edge Case, Spec §Edge Cases]
- [x] CHK026 - Is an absent or unparsable rejection body addressed? [Edge Case, Spec §FR-009]
- [x] CHK027 - Is a successful response with an empty body addressed? [Edge Case, Spec §Edge Cases]
- [x] CHK028 - Is a time budget smaller than a single pause addressed, so the wait cannot sleep past its own deadline? [Edge Case, Spec §Edge Cases]
- [x] CHK029 - Is an already-cancelled cancellation token addressed? [Edge Case, Spec §Edge Cases]
- [x] CHK030 - Is a rejected direct upload addressed, including what must NOT happen afterwards? [Edge Case, Spec §Edge Cases, §FR-005]
- [x] CHK031 - Is a validation ending in the non-conclusive failure status addressed as a terminating state for the wait? [Edge Case, Spec §Edge Cases]
- [x] CHK032 - Are the accepted shapes of the document bytes enumerated rather than left to the reader? [Edge Case, data-model.md §UploadData]

## Non-Functional Requirements

- [x] CHK033 - Are the security requirements around the credential stated (where it goes, where it must not go, that it is never logged)? [Security, Spec §FR-004, §FR-007, plan.md §Constitution Check VIII]
- [x] CHK034 - Is the "no new dependency" constraint recorded as a requirement rather than left to reviewer judgement? [Non-Functional, plan.md §Technical Context, Spec §Assumptions]
- [x] CHK035 - Is the strict-typing constraint stated as non-negotiable in the written requirements? [Non-Functional, plan.md §Technical Context]
- [x] CHK036 - Is the absence of a performance target justified rather than silently omitted? [Non-Functional, plan.md §Technical Context]

## Dependencies & Assumptions

- [x] CHK037 - Is the assumption that the backend contract is already stable stated explicitly? [Assumption, Spec §Assumptions]
- [x] CHK038 - Is the assumption about the runtime's native HTTP capability recorded, with the version constraint it implies? [Assumption, Spec §Assumptions]
- [x] CHK039 - Is the ownership of idempotency semantics attributed to the API rather than to the library? [Assumption, Spec §Assumptions]
- [x] CHK040 - Is the upload destination documented as opaque and never derived client-side? [Assumption, Spec §Assumptions, Contract §Non-goals]

## Ambiguities & Conflicts

- [x] CHK041 - Were the points the documentation left open (missing confirm operation, options shape, idempotency-key placement) resolved on the record rather than decided silently? [Ambiguity, Spec §Clarifications]
- [x] CHK042 - Is every requirement traceable to a doc section, a recorded clarification, or an explicit gap marker? [Traceability, research.md §D-001..D-008]
- [x] CHK043 - Is there a stable identifier scheme so tasks can cite requirements? [Traceability, Spec §FR-001..FR-019, §SC-001..SC-006]

## Result

All 43 items pass. Two notes carried forward, neither blocking:

- `README.md` L55 states Node ≥ 18 while `.nvmrc` and `sdk/package.json` engines pin 20. A
  pre-existing inconsistency in a section this feature does not own; deliberately out of scope.
- CHK014 passes only because FR-019 exists. If the governance amendment to
  `docs/sdk/code_rules.md` is dropped during implementation, this item flips to a conflict.
