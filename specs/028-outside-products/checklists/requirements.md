# Specification Quality Checklist: Outside products

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-11-10
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — repositories, directories and spec numbers are named only because the change is where things live and which requirement each one amends
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [ ] No [NEEDS CLARIFICATION] markers remain — three remain, for `/speckit-clarify`: FR-011 (the machine's topic carrier), FR-018 (the home of the provider's core library and of the simulator), FR-022 (compliance's confirmation of counting open stakes)
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined (as references to the living features)
- [x] Edge cases are identified
- [x] Scope is clearly bounded (the brand's side and what leaves this repository; nothing the products do inside themselves)
- [x] Dependencies and assumptions identified (specs 015 and 026 are in flight and are named as such)

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Every requirement names the requirement it amends or restates and what established it; none was recorded from the original, which has neither product.
- `just features`: 0 findings in 1211 scenarios and 25 specs (2026-11-10).
- Scenarios changed or added in `features/` are not yet bound: the bound suites of `attribution`, `game-provider`, `responsible-gambling`, `reporting` and the modules tool fail on them until the spec is built.
- The neighbouring specs' own text (001 FR-003, FR-016, FR-019; 003; 010 FR-010; 012 FR-019; 015 FR-017, FR-018) is amended by the tasks, not by this specification; spec 012's reference to the one scenario this spec renames was corrected so the checker holds.
- Open for others: spec 026 is on a local branch, unmerged; which repository it lands in first is for whoever holds it (Open Questions).
