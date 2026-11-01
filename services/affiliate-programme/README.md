# affiliate-programme

The shared affiliate programme (spec 013): accounts, memberships, plans, the ledger, settlement,
tags and documents, managers and communications, for every brand that reports to it. Its components,
as plan.md lays them out:

- **Event sourced**: `Affiliate`, `Membership`, `PlayerAttribution`, `CommissionAccount`, `Payout`.
- **Key value**: `BrandProgramme`, `Plan`, `FactInbox`, `EmailIndex`, `Token`, `SigningKeys`, `Link`,
  `AuditEntry`, `Notification`, and the rest of data-model.md.
- **Workflows**: `Close`, `Erasure`, `Postback`, `EmailSend`, `Import`.
- **Consumers**: one `attribution-<slug>` per brand of `PROGRAMME_BRANDS`, `ToLedger`, `Overrides`,
  `Communications`, `Provisioning`.
- **Endpoints**: `/v1/affiliates` (managers), `/v1/my` (affiliates), `/v1/join` (public),
  `/v1/programme` (documents, hosted brands, imports).

More follows as the components land (tasks T094).
