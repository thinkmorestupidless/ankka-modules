# Specification Quality Checklist: engagement

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-08
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) beyond what the constitution asks a
      spec to name (the language and its reason; the original's code as the source)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders where the domain allows
- [x] All mandatory sections completed

## Requirement Completeness

- [ ] No [NEEDS CLARIFICATION] markers remain — three remain (operator mail for the payments
      backoffice; withholding events for suppressed players; the agent online alert)
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic where the domain allows
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

- Source of truth: `eitheror-platform` — `tl/runtime/capabilities/customerio_dispatch/`
  (handler, cio_client, cache), `starter-packs/casino/functions/notify-*`, `casino-service`'s
  `CrmService`, `CustomerIOClient`, `CustomerIOConfigRoutes`, `CustomerIOWebhookRoutes`,
  `NotificationService`, `PushEventConsumer`, `AgentStreamConsumer`, `DailyDepositCountReset`,
  `DepositDay`; `merchant-service`'s `BrandCrmConfigRoutes`, `OperatorInviteEmailService`;
  `substrate/push-gateway/` in full; specs 097, 099, 129, 146, 175, 182; decision 0025.
- Glossary words this spec needs that are not yet terms: CRM, send record, dead letter, trigger,
  class (marketing/service), suppression, connection, signal, presence, seed person. `Avoid:
  notify` already applies to the `notify_*` functions named only as the original.
- Recordings: the Go suite has two scenarios that touch this service; the oracle is mostly the
  sixteen Playwright suites and the unit suites replayed against a `crm-stub` that does not exist
  yet.
- The mandatory `after_specify` hook (`/speckit-bdd-features`) has not been run; the parent runs
  it over the wave.

- Reconciliation 3 (2026-10-08, after specs 014–019): changes recorded under the spec's Clarifications; the checklist above still holds.
