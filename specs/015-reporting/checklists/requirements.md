# Specification Quality Checklist: Reporting

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

- Three markers remain, all finance's or compliance's: NGR before or after the programme's fees;
  the day-end's close instant (day's end or a settlement cut-off, which spec 012's `ggr-daily`
  must share); the document lifetime beyond 24 hours for compliance exports.
- "No implementation details" is read as the sibling specs read it: views, timers and topics are
  named because the constitution fixes them; no library, table or class is.
- The original's figures are established as defects, not behaviour, with the code cited: the
  twenty-player sample, the per-player page of two hundred, the constant 97% and 0, the UTC days
  and the single currency. Field names and shapes are kept so the backoffice changes only its base
  URL; the figures under them change.
- The boundary with spec 008 is stated twice (Context, Out of scope): the per-player activity feed,
  bets, sessions, transaction search and the player's own export are 008's; this service owns the
  brand-level reports, the dashboard, the dispute report, the day-end and the compliance exports.
  `casino-read-model` is 016's.
- No Go end-to-end scenario covers the reports or the dashboard today; Recordings names the new
  scenarios to write and the Playwright oracles meanwhile, and says a parity test on a figure must
  be against the fact set, since the original's answer is a sample.
- Four assumptions are requirements on sibling specs (the reporting zone in 008; provider and
  wallet ids on `casino.rounds` in 003; offer name and type on `casino.rewards` in 011; the
  provider on `payments.payments` in 004); the parent's reconciliation pass should confirm them.
- The acceptance scenarios stay in the spec until `/speckit-bdd-features` moves them into
  `features/`; the parent run does that for the batch.

- Reconciliation 3 (2026-10-08, after specs 014–019): changes recorded under the spec's Clarifications; the checklist above still holds.
