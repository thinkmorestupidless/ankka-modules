# Specification Quality Checklist: Ingest — The Merchant-Facing Edge of Shared Payments

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

- Two markers remain, both for Trevor or Sheldon: whether a payment read requires the merchant's
  key or session token (the original serves it to anyone holding the id; US3 scenario 5, FR-009),
  and the starting rate per merchant (Assumptions).
- The spec names the original's files, routes and test functions in *Established by* lines and the
  Context, as 001 FR-031 requires; the requirements themselves are stated as what a merchant, a
  player's cashier page or an operator observes. Scala is named once, in Context, as the
  constitution VI decision.
- The plan's two endpoints were checked against the original: the Stripe webhook is a stub and is
  dropped; the "merchant API webhook" is the merchant-key routes, which are real and are this
  spec's. The Cloudflow streamlets are dropped with evidence. Each is under *Changes from the
  original*.
- Reconcile with spec 004 (merchant) once it exists: which service serves the merchant-facing
  routes (this spec moves them here), how keys are issued and their digests published, what is
  kept of the customer fields against 001 FR-018, and the exact commands `ingest` forwards.
- The mandatory `after_specify` hook (`/speckit-bdd-features`) has not been run; the acceptance
  scenarios stay in the spec until the batch's living-features pass.
- 2026-10-22 (plan analysis): the two markers above are resolved (FR-009 clarified 2026-10-21; the
  rate is 600 a minute in *Assumptions*); the features were written and `just features` passes;
  spec 004 was reconciled 2026-10-12. *Recordings* corrected: scenario 12 is recorded, the scenario
  10 pair stays skipped and the driver holds their behaviours.
