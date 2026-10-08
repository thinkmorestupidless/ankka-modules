# Specification Quality Checklist: Affiliate protocol

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

- Three markers remain, all product decisions for Sheldon: the transition period for bare-UUID
  tags (FR-004), the attribution window default (FR-008), and whether per-round GGR is opt-in per
  network (FR-016). Resolve with `/speckit-clarify` before planning.
- The spec names ankka features (038, 040, 042, 043) and sibling specs (001, 008, 013, 019)
  because they bound what the brand can do; it names no component, route or table of its own.
- Reconciled with 001 and 013 on 2026-10-08: the brand side is the `attribution` service
  (FR-029, FR-030), the topic is `affiliates.attribution`, the legacy tag document is the
  programme's, every click has an id, and country is on `click` and `registration`. The country
  open question is closed.
- `/speckit-bdd-features` has not been run; the acceptance scenarios stay in the spec until it is.
