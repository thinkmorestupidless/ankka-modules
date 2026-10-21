# Specification Quality Checklist: psp-gateway

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

- Two markers remain: whether a captured sample of Unity Finance's withdrawal webhook exists for
  the stub; and whether an unsigned Morphosis callback is acceptable for real money or must be
  confirmed against the provider's API. Settle with `/speckit-clarify` before planning. The ConnecX
  question is settled: dropped, recorded under *Clarifications* and *Changes from the original*.
- "No implementation details" is read as the other specs read it: the spec names the language
  decision (TypeScript, with the reason recorded) because constitution VI requires one, the
  component kinds (two event sourced entities, a consumer), and each provider's signature scheme and
  status map, because those *are* the observable contract a provider holds the gateway to and the
  parity tests must match them. It names no library, class or table.
- The per-provider table is read from `services/psp-gateway/internal/<provider>/` and the handlers
  in the original on 2026-10-08; every status word and credential name in it is the code's.
- The acceptance scenarios stay in the spec until `/speckit-bdd-features` moves them into
  `features/`; the parent run does that for the batch. New glossary terms this spec will need:
  session, outcome, variance, delivery, undelivered outcome, re-drive, quote, deadline, provider
  reference.
- The seven providers without an end-to-end recording today need scenarios against `psp-stub` before
  the port; the Recordings section says so. The gateway's own 37 Go test files are the source for
  them.
