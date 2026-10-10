# Specification Quality Checklist: Merchant

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-08
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) beyond the platform's own seams
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders where the domain allows
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain (FR-036 and FR-037 resolved as dropped at reconciliation)
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

- Every requirement is established from `substrate/merchant-service/` and
  `substrate/deposit-on-payment-capture-fn/` as read on 2026-10-08, the bundle's three CronJobs, and
  the Go scenarios 12, 13 and 14 (constitution I); the Recordings section names them.
- The amendments to specs 001 and 002 this spec asked for (the `initiated` outcome; the
  realm-administration exception for `merchant`; the merchant key as a route admission; the
  `payments.merchants` topic) landed at reconciliation on 2026-10-08, when the cashier routes also
  moved to `ingest` (006) and FR-036/FR-037 were resolved as dropped. No marker remains.
- Acceptance scenarios stay in the spec until `/speckit-bdd-features` runs for this batch; the
  glossary will need `sub-brand`, `holding`, `company`, `merchant key`, `credential`, `routing tree`,
  `session`, `payment`, `capture`, `variance`, `deadline` as proposed terms.

- Reconciliation 3 (2026-10-08, after specs 014–019): changes recorded under the spec's Clarifications; the checklist above still holds.
