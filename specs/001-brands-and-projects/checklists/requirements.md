# Specification Quality Checklist: Brands and Projects

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-08
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
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

- Two markers remain, both in Assumptions and both for Trevor and Sheldon: which brand is the pilot
  (is Goldvibe still free of player data?), and whether TL survives as a sold feature. Neither
  changes a requirement here; the first fixes the order of Phases 3 and 4, the second decides whether
  specs 010 and 011 build timers or function packs.
- This spec names ankka's features by number (022, 038–044), the original's suites and stubs, and the
  platform's addressing scheme, because they are the contract the casino is built on rather than a
  choice a plan makes. "No implementation details" is read in that light; no language, framework or
  library is named in a requirement.
- The topic contract (FR-011) restates the plan's table with the readers changed for the decisions
  recorded under Clarifications: `attribution` replaces `affiliates`, `reporting` is added as a
  reader, and `payments.payments` is produced by the brand's wallet. Each service spec must reconcile
  its producers and readers with this table, and the table changes only by a clarification here.
- Service names in FR-002 are the spec list in `CLAUDE.md` with one difference: the brand's emitter
  of attribution facts is a service named `attribution`, specified by 012 (`affiliate-protocol`).
- Reconciled with 012 and 013 on 2026-10-08 (see Clarifications, reconciliation session): the
  attribution topic is `affiliates.attribution`, keyed by the per-network pseudonym; `programme`
  holds no player id; `player_registered` and `bonus_granted` carry what `attribution` binds from.
- The acceptance scenarios have not been moved into `features/`; `/speckit-bdd-features` runs after
  the other foundation specs (002, 012, 013) are written, so the glossary is built once.

- Reconciliation 3 (2026-10-08, after specs 014–019): changes recorded under the spec's Clarifications; the checklist above still holds.
