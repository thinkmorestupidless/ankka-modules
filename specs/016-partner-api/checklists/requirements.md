# Specification Quality Checklist: partner-api

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

- Three markers remain, all for Trevor and Sheldon: whether any partner is contracted or in
  discussion (decides whether the pilot brand ships with this service enabled); whether any planned
  partner needs several brands under one token (the dropped `company`/`holding` scope levels); and
  whether a brand with a partner raises `casino.sessions` retention to twelve months. Settle with
  `/speckit-clarify` before planning.
- "No implementation details" is read as the other specs read it: the language decision
  (TypeScript, reason recorded) is named because constitution VI requires one; the contract file,
  the enum mappings, the token claims and the grant's fields are named because they *are* the
  observable surface a partner and an operator hold the service to, and the parity tests must match
  them byte for byte. No library, class, table or route path is named.
- Every requirement cites how it was established (001 FR-031): the original's contract file, its
  `partner-grant.md`, its `V8`/`V10` migrations, one of its forty test suites, decision 0050 or 0051,
  or a decision in this spec's Clarifications.
- Read from the original on 2026-10-08: `casino/public-api/` (src, test, README, package manifest),
  `specs/211-casino-public-api/` (spec, contracts), `substrate/casino-read-model/`, the casino
  chart's `casinoPublicApi` and `rateLimit.publicApi` values and templates,
  `docs/casino-public-api-versioning.md`, and the eitheror brain's decisions 0050 and 0051.
- The acceptance scenarios stay in the spec until `/speckit-bdd-features` moves them into
  `features/`; the parent run does that for the batch. New glossary terms this spec will need:
  partner, grant (partner's — distinct from ankka 040's platform grant; propose "partner grant"),
  opaque id, contract, contract version, agreement (data processing), entitled field, cost ceiling,
  cursor (exists, from 012's feed — check the sense matches), degraded, lag.
- The original's public API has no Go or Playwright coverage; the Recordings section names the ten
  scenarios to write and record against the original before the port, and the forty `node:test`
  suites as the oracle meanwhile.
- Reconciliation notes for the parent: 001 FR-020 says "a partner token the partner API issues";
  this spec has the `eitheror` realm issue it and the service verify it through 022 (the service
  holds no realm credential, 001 FR-021) — one of the two wordings should change. 001 FR-011 lists
  `partner-api` as a reader of `casino.withdrawals`, `casino.rewards`, `casino.rounds`,
  `casino.sessions`, `casino.players` and `money.transactions`, which matches FR-008 here. Spec 019
  must list the opaque-id registry among what the migration carries.

- Reconciliation 3 (2026-10-08, after specs 014–019): changes recorded under the spec's Clarifications; the checklist above still holds.
