# Specification Quality Checklist: Affiliate programme

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-08
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [ ] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Three markers remain, all product questions for Sheldon: payout per currency or converted;
  whether the programme serves brands outside this installation; confirmation that the
  first-deposit guard becomes lifetime. Resolve with `/speckit-clarify` before planning.
- The spec names the original's code (`CommissionEngine`, `notify/*` routes, twin namespaces)
  only in Context and *Changes from the original*, to say what is kept and what changes;
  requirements are stated in the domain's words.
- `/speckit-bdd-features` has not been run; scenarios stay in the spec until the batch is done.
- Reconciled with 001 and 012 on 2026-10-08: consumes exactly 012's fourteen fact kinds and
  fields, serves no click route, publishes the legacy tag document instead of answering lookups,
  and forgets a player's country and click details on any `player-closed` fact.
