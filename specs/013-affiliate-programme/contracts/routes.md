# Routes

Research R11, R12. Every route of the original is listed with its fate; paths are the original's unless
marked. Bodies and answers are the original's (`S/AffiliateRoutes.scala`, `S/OperatorRoutes.scala`)
with money in minor units and a `brand` and `currency` on every figure. Refusals keep the original's
codes (`409 email_already_registered`, `409 must be X, currently Y`, `404 account_not_found`, …); new
ones are listed at the end.

## `/v1/affiliates` (`OperatorEndpoint`)

Admits `Routes.affiliateManager()` and checks the path's `{brand}` against `brandsOf(principal)`;
`managers` routes admit `brand_admin` and `platform_admin` only, as the original. Three routes are
public by `withAcl(Routes.public)`.

| Original # | Method and path | Notes |
|---|---|---|
| P7 | `POST /{brand}/apply` | public; FR-003; `201 {affiliateId, status: "pending"}` |
| P5 | `POST /{brand}/forgot-password` | public; always `200`; 30-minute token; 5-minute dedupe |
| P6 | `POST /accept-invitation` | public; `{token, password, mode}`; the token claimed once |
| O1–O3 | `GET /{brand}/managers`, `POST /{brand}/managers`, `POST /{brand}/managers/{user}/reset-password` | FR-031 |
| new | `DELETE /{brand}/managers/{user}` | change 16: removes `brand_<slug>`, and `affiliate_manager` when the user holds no other `brand_*` role |
| O4–O6 | `GET`, `POST /{brand}/commission-plans`; `PATCH /{brand}/commission-plans/{plan}` | + `cpaCurrency` (change 11) |
| O7, O8 | `GET`, `POST /{brand}/accounts` | list filtered and searched after decryption (R9); create active with a membership, `201 {affiliateId, email, status}` as the original; an email an account already holds, at this brand or another, `409 email_already_registered` (the affiliate applies, FR-041) |
| O9, O10 | `GET /{brand}/accounts/{aff}/network-health`, `/network` | |
| O11–O13 | `POST /{brand}/network/{sub}/{approve|suspend|reactivate}` | **reshaped** from `accounts/{aff}/network/{sub}/…` (two parameters) |
| O14, O15 | `POST /{brand}/accounts/{aff}/approve`, `/reject` | FR-002; with `parentAffiliateId` or `overridePercent`, account-wide (below) |
| O16–O18 | `POST /{brand}/accounts/{aff}/resend-invitation`, `/reset-password`, `/update-email` | update-email re-seals and moves the email index; all three account-wide (below) |
| O19 | `PATCH /{brand}/accounts/{aff}/status` | **not served**: `404` (change 13) |
| O20, O21 | `GET`, `PATCH /{brand}/accounts/{aff}` | `revShareOverride` is the membership's at `{brand}`; a PATCH with any field but `planId` and `revShareOverride` is account-wide (below) |
| new | `POST /{brand}/accounts/{aff}/close`, `POST /{brand}/accounts/{aff}/erasure` | FR-037: close disables sign-in; erasure files the hold |
| O22 | `GET /{brand}/accounts/{aff}/revenue` | |
| O23, O24 | `GET`, `POST /{brand}/accounts/{aff}/notes` | |
| O25 | `GET /{brand}/accounts/{aff}/audit` | rows `{auditId, affiliateId, brandId, event, actorId, recordedAt, details}`, no identity or token (change 17) |
| O26 | `GET /{brand}/accounts/{aff}/players` | pseudonyms, never a player id |
| O27 | `GET /{brand}/accounts/{aff}/sub-affiliates` | |
| O28 | `POST /{brand}/accounts/{aff}/adjustments` | `{type, amount, currency, reason}`; FR-022. An optional `Idempotency-Key` header makes the adjustment's id the key's (with the affiliate and the brand): a request sent again writes nothing and answers that `adjustmentId` |
| O29–O34 | `GET /{brand}/accounts/{aff}/view/{page}` | `page` ∈ `dashboard`, `stats`, `commission`, `links`, `payouts`, `notifications`, and (new, FR-036) `postbacks`; `commission` takes `?period=` (**reshaped** from `view/commission/{period}`) |
| O35, O36 | `GET`, `POST /{brand}/tracking-links` | the link gains a signed tag |
| O37–O39 | `GET /{brand}/revenue`, `/revenue/by-campaign`, `/revenue/geo` | per currency |
| O40 | `POST /{brand}/payouts/close?period=` | starts or rejoins the `Close` workflow; answers when it ends or after 10 s with `202` and the workflow's id |
| O41 | `GET /{brand}/payouts` | |
| O42 | `PATCH /{brand}/payouts/{payout}/status` | `{status, overrideCommission?, reason?}`; the figure as an adjustment (FR-019); to `paid`, `409 claims_changed` when a close claimed for the payout since it was read: read it again and pay |
| new | `GET /{brand}/payouts/export?period=` | FR-021: `text/csv` (below) |
| O43, O44 | `GET`, `PUT /{brand}/settings` | + `ggrSource`, `reportingZone`, sender configuration; `customerIoKey`, write-only, set by `brand_admin` or `platform_admin` only (`403` otherwise) and answered only as `customerIoKeySet` |
| O45, O46 | `GET`, `PUT /{brand}/programme` | + terms, contact, description, default plan |
| O47 | `POST /{brand}/messages` | |
| O48, O49 | `GET /{brand}/fraud-flags`, `PATCH /{brand}/fraud-flags/{flag}/dismiss` | |
| new | `GET /{brand}/unattributed` | facts recorded unattributed, mismatched or disagreeing, with the reason (FR-011, edge cases), from `Inbox` and `Players` |
| O50–O52 | `GET`, `POST /{brand}/creatives`, `DELETE /{brand}/creatives/{id}` | an address, not a file |

**Account-wide writes** (FR-041): what is the account's rather than one brand's membership of it (its
email, identity, payment details, `postbackUrl`, `minimumPayout`, manager fields, parent and override,
and the sign-in links O16 and O17 send) is changed only by a caller who holds every brand the account is
a member of, or a platform administrator; anyone else is answered `409 account_shared` naming the other
brands, as close and erasure (`/close`, `/erasure`) are. The plan, the RevShare override and the status are the
brand's.

## `/v1/my` (`PortalEndpoint`)

Admits `Routes.affiliate()`; the target is the caller (`eitheror_scope_id`) or a descendant, else
`403` with the original's ACL text, `The supplied authentication is not authorized to access this
resource` (recorded; asked 2026-11-07). A target the caller reaches that does not exist is
`404 account_not_found`. Every read takes `?brand=` (absent: every brand the account
has a membership at), and every figure is per brand and currency.

A revenue read (M1, and O22 and O37 on the operator's side) answers
`{affiliateId, period, figures: [{brandId, currency, …}]}`: one element per brand and currency, each
holding the original's revenue fields (`ggr`, `ngr`, `cpaCommission`, `depositCount`, …) in minor units.
O37 has no `affiliateId`. A brand with registrations and no money yet has an element without `currency`.
Decided 2026-11-02 (research.md *Found during implementation*).

| Original # | Paths under `/{affiliate}` |
|---|---|
| M1–M5 | `revenue`, `revenue/{period}/breakdown`, `revenue/by-campaign`, `deductions/{period}`, `payouts` |
| M6–M10 | `account` (`GET`, `PATCH`), `plan`, `available-plans`, `programme` (these three need `?brand=`) |
| M11, M12 | `tracking-links` (`GET`, `POST` with `brand`) |
| M13–M16 | `network-health`, `network`, `network/{sub}` (`GET`, `PATCH`) |
| M17–M19 | `network/{sub}/{approve|suspend|reactivate}`, the membership named by `?brand=` |
| M20, M21 | `sub-affiliates/invite` (`brands` in the body), `sub-affiliates` |
| M22–M27 | `creatives`, `players`, `notifications`, `notifications/read-all`, `events`, `events/account` |
| M28–M30 | `bonus-codes` (`GET`, `POST`), `bonus-codes/{code}` (`DELETE`) |
| M31–M33 | `invite-links` (`GET`, `POST`), `invite-links/{link}` (`DELETE`) |
| M34, M35 | `first-login`, `last-login` |
| new | `postbacks`: the last outcome per event (FR-036) |

## `/v1/join` (`JoinEndpoint`), public

`GET /{token}?brandId=`, `POST /{token}?brandId=` (P3, P4): the invite link; `429 too_many_requests`
past 50 an hour.

## `/v1/programme` (`ProgrammeEndpoint`)

| Method and path | Admits | Notes |
|---|---|---|
| `GET /documents/keys`, `/documents/revocations`, `/documents/legacy-tags`, `/documents/codes/{brand}` | public | spec 012's shapes; `Cache-Control: max-age=<cacheSeconds>` |
| `POST /inbound/{brand}` | public, HMAC | [inbound.md](inbound.md) |
| `POST /documents/legacy-tags/withdraw` | `platform_admin` | FR-026: once every brand with imported tags reports its transition ended in its own key document, read from the brand's `keyDocumentUrl`; else `409 transition_not_ended` naming the brands not ended or not reachable; the document answers `404` after |
| `GET`, `POST /brands`; `PATCH /brands/{brand}` | `platform_admin` | FR-040; `POST` answers the webhook secret once; `POST` and `PATCH` take `keyDocumentUrl` (FR-026) |
| `GET /brands/{brand}/usage?month=` | `platform_admin` | FR-042 |
| `POST /imports/{brand}` | `platform_admin` | FR-039 |

## Not served

The click (`POST /v1/track/click`, spec 012's), `/health` (the runtime's readiness), the eight
`notify/*` routes and the eight other internal routes (research R1), and `PATCH accounts/{aff}/status`.

## New refusals

`409 not_a_member` (an affiliate acting at a brand with no membership), `403 brand_not_held` (a brand
the caller's roles do not cover), `409 status_transition` (naming the status), `409 payout_paid`,
`404 brand_unknown` (inbound or documents for a brand the programme does not serve), `409 erased`,
`409 brand_retired` (an application, an account made or a status change at a retired brand).
`400 postback_url_refused` (an account PATCH, the portal's or a manager's, whose `postbackUrl` is not
`https` or names a loopback, private, link-local, CGNAT or metadata host; its message says which). A
send to an address that resolves to one of those is not made, and its reason is the outcome's
`lastError`.

## The export

`GET /v1/affiliates/{brand}/payouts/export?period=` answers `text/csv`, one row per approved payout,
the header:

```text
affiliateId,displayName,email,paymentMethod,brand,currency,period,cpa,revShare,override,adjustments,total,approvedBy,approvedAt
```

Amounts in minor units. Every export writes an `AuditEntry` `payment_details_read` with the actor, the
brand, the period and the affiliates listed (FR-021).
