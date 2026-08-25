# Requirements Quality Checklist: Example project integrating the SDK

**Purpose**: Validate the quality of the requirements before implementation begins
**Created**: 2026-08-25
**Feature**: [spec.md](../spec.md)
**Depth**: Release gate — this checklist runs immediately before `/speckit-implement`
**Audience**: The implementer, and the reviewer of the resulting diff

These items test the **requirements**, not the system. Each asks whether something is well written,
not whether it works.

## Requirement Completeness

- [x] CHK001 - Is every operation of the client library's public surface named by at least one requirement? [Completeness, Spec §FR-003]
- [x] CHK002 - Are all four documented verdict outcomes enumerated in the requirements rather than referred to collectively? [Completeness, Spec §FR-004]
- [x] CHK003 - Are requirements defined for both idempotency behaviors — creation replay and confirmation replay — or only one? [Completeness, Spec §FR-005]
- [x] CHK004 - Does the spec state what the example must do when the client library cannot be resolved at all, as distinct from the API being unreachable? [Gap]
- [x] CHK005 - Are requirements stated for the report's content, or only for its existence? [Completeness, Spec §FR-009]
- [x] CHK006 - Is the prerequisite ordering (infrastructure, backend, library build) captured as a requirement or only in the quickstart? [Gap, Spec §FR-016]
- [x] CHK007 - Are the governing documents that must be amended named individually, or referred to as a group? [Completeness, Spec §FR-018]

## Requirement Clarity

- [x] CHK008 - Is "consume the client library the way an external consumer would" defined by observable properties rather than by intent? [Clarity, Spec §FR-001]
- [x] CHK009 - Is "a single documented command" unambiguous about whether prerequisites count as part of it? [Ambiguity, Spec §FR-002]
- [x] CHK010 - Is "human-readable report" specified concretely enough that two implementers would produce the same fields? [Clarity, Spec §FR-009]
- [x] CHK011 - Is "actionable message" for an unreachable API defined by what it must contain? [Clarity, Spec §FR-013]
- [x] CHK012 - Is "every documented option" bounded to a specific list, or left to the implementer's reading? [Ambiguity, Spec §FR-003]
- [x] CHK013 - Does the spec distinguish a *status* of `FAILED` from a *verdict* of `FAIL` everywhere it uses either word? [Clarity, Spec §US2]

## Requirement Consistency

- [x] CHK014 - Does the narrowed FR-017 still prohibit exactly what it intends, now that FR-016a permits one change to the library? [Consistency, Spec §FR-017]
- [x] CHK015 - Do the requirements and the clarification log agree on where the example lives and why? [Consistency, Spec §Clarifications]
- [x] CHK016 - Is the scenario count consistent across spec, plan, tasks, contract and quickstart? [Consistency]
- [x] CHK017 - Does SC-002's coverage claim remain true given that one documented option cannot succeed against this backend? [Consistency, Spec §SC-002]
- [x] CHK018 - Are the three failure reasons quoted identically in the spec and in `docs/business-rules.md` § 5? [Consistency]

## Acceptance Criteria Quality

- [x] CHK019 - Can "100% coverage of the published surface" be objectively determined, or does it rest on judgement? [Measurability, Spec §SC-002]
- [x] CHK020 - Is the two-minute budget attached to a stated machine and stack condition? [Measurability, Spec §SC-004]
- [x] CHK021 - Is "identical pass/fail outcomes" precise about what may legitimately differ between runs? [Clarity, Spec §SC-005]
- [x] CHK022 - Can SC-006 — a reader understanding a failure without the source — be checked without asking a person? [Measurability, Spec §SC-006]
- [x] CHK023 - Does every functional requirement have an outcome that can be observed from outside the example? [Acceptance Criteria]

## Scenario Coverage

- [x] CHK024 - Are requirements present for the primary flow, the alternate flows (each failing verdict), and the exception flows (each error)? [Coverage]
- [x] CHK025 - Are requirements defined for the recovery class — what happens after a scenario fails mid-run? [Coverage, Spec §FR-011]
- [x] CHK026 - Are both non-completion paths of the waiting operation — exhausted budget and cancellation — separately required? [Coverage, Spec §FR-007]
- [x] CHK027 - Is the un-confirmed-request state, reachable when an upload is rejected, addressed in the requirements? [Coverage, Spec §US3]

## Edge Case Coverage

- [x] CHK028 - Is the boundary of the size rule stated as a specific byte count rather than "large"? [Edge Case, Spec §FR-014]
- [x] CHK029 - Are requirements defined for a successful response that carries no body? [Edge Case, Spec §FR-016a]
- [x] CHK030 - Is the behavior specified when an error response is not in the API's error format? [Edge Case, Spec §US3]
- [x] CHK031 - Does the spec address a second run inheriting the first run's state? [Edge Case, Spec §FR-015]

## Non-Functional Requirements

- [x] CHK032 - Are the configuration sources and their defaults specified, rather than assumed from convention? [Completeness, Spec §FR-012]
- [x] CHK033 - Is the prohibition on committed secrets stated as a requirement, not only as an assumption? [Completeness, Spec §FR-012]
- [x] CHK034 - Is the prohibition on committed binaries bounded, so the existing sample document is clearly permitted? [Clarity, Spec §FR-014]
- [x] CHK035 - Are timing and repeatability expressed as measurable criteria rather than as qualities? [Measurability, Spec §SC-004, §SC-005]

## Dependencies & Assumptions

- [x] CHK036 - Is the dependency on a live backend recorded as an assumption the reader can accept or reject? [Assumption]
- [x] CHK037 - Is the requirement to build the library before running the example stated where an implementer will see it? [Dependency, Spec §Assumptions]
- [x] CHK038 - Are new toolchain dependencies identified and justified, per constitution IX? [Dependency]
- [x] CHK039 - Is the assumption that the sample document is a valid PDF under the ceiling stated explicitly? [Assumption]

## Ambiguities & Conflicts

- [x] CHK040 - Does any requirement conflict with the constitution's two-artifact description without the amendment resolving it? [Conflict, Spec §FR-018]
- [x] CHK041 - Does FR-016a conflict with the constitution's rule that code-rules files change only by amendment? [Conflict]
- [x] CHK042 - Is any requirement satisfiable in more than one materially different way? [Ambiguity]

## Notes

Evaluated 2026-08-25 before `/speckit-implement`. 39 of 42 items passed on the first read. Three gaps
were found and closed in the spec in the same pass:

- **CHK004** → new **FR-013a**: an unresolvable client library is a distinct failure from an
  unreachable API, and must name the prerequisite that fixes it.
- **CHK009** → **FR-002** rewritten: the single command runs the scenarios; the infrastructure, the
  backend and the built library are prerequisites, not part of it.
- **CHK039** → Assumptions now state the sample document's size and validity, so the passing verdict
  is derivable from the spec instead of from the file.

No CRITICAL or HIGH issue remained. Requirements are ready for implementation.
