# Parity: every capability of the original backoffice

The list FR-030 holds the backoffice to. One row per capability of
`eitheror-platform/casino/frontend` and `payments/frontend` (inventory of 2026-11-11), the phase
that delivers it (FR-045), and the service in this repository whose operator route serves it.

**Status**: *kept* (a route exists; the plan confirms its shape), *gap* (no route found in the
owning service on `origin/main` at `c07af6d6`; the plan confirms, and a confirmed gap is an
amendment to that service's spec), *changed* (kept with the change the spec names), *dropped*,
or *moved*. The two rows once marked *decide* were settled on 2026-11-15: both kept.

## Shell and access (phase 1)

| Capability | Owner | Status |
|---|---|---|
| Sign in at the `eitheror` realm; wrong-portal page for affiliates | realm (spec 001) | changed: server-held sign-in (FR-003) |
| Accept an operator invitation; reset a password | merchant `/v1/operators` | kept |
| Holding, company, sub-brand picker; impersonation banner | — | changed: brand from roles (FR-004); levels stay in payments administration |
| Theme preference | backoffice-bff (Postgres) | changed: `backoffice` project preferences (FR-038) |
| Notice when a player comes online | engagement `presence`, `agent-stream` | kept (phase 6) |

## Dashboard and reports (phase 4)

| Capability | Owner | Status |
|---|---|---|
| KPI tiles with history; trend; live activity; map; top games and players; reward summary; payment health | reporting `dashboard/*` | kept |
| Reports: GGR, players, rewards, transactions with export, payment providers | reporting `reports/*` | changed: export from the server (FR-033) |
| Payments analytics (captured volume, status counts) | merchant `cashier/summary` | kept |
| Day-end and compliance exports | reporting `day-end`, `compliance/*` | kept (new in spec 015, no screen in the original) |

## Players (phases 1 to 3)

| Capability | Owner | Status |
|---|---|---|
| Player list: search, status, VIP | players `/players` | kept |
| Player list facets: identity queue states, birthday window, potential VIP, online, country, currency, counts | players | gap |
| Profile read and edit | players `profile` | kept |
| Correct date of birth with a reason | players `date-of-birth` | kept |
| Notes: add, pin, unpin | players `notes` | kept |
| Send message with merge tags and preview | players `messages`, `merge-tags`, `messages/preview` | kept |
| Reset password | players `reset-password` | kept |
| Suspend, reinstate, close, reopen | players | kept |
| Self-exclusion | responsible-gambling `self-exclude` | kept |
| VIP tier, potential VIP | players `vip-tier`, `pot-vip` | kept |
| Manager tags | players | gap |
| Clear suspected-duplicate flag; clear no-deposit block | players | kept |
| Activity feed with categories, search, period; CSV export as a job | players `activity`, `activity-export`, `exports` | changed: one timeline (FR-021, FR-022) |
| Games panel (what the player plays) | players | gap |
| Devices tab | players | gap |
| Marketing consents and suppression | players `crm` | kept |
| Erasure request | players `erasure` | kept (no screen in the original) |
| Verification link | players `verification-link` | kept |
| Wallet balances, restricted funds, lifetime totals, transactions | wallet `wallets/{player}/{currency}/*` | kept |

## Sessions, rounds and transactions (phase 1)

| Capability | Owner | Status |
|---|---|---|
| Player sessions list with filters; force-close | players `sessions` | kept (brand-wide list: gap to confirm) |
| Game sessions active and closed; force-end; force-end all | players `sessions/*` | kept |
| A session's bets | players `sessions/{sid}/bets` | gap (answers "not yet") |
| Player session timeline with data-gap warning | players, game-provider `rounds` | changed: FR-023 |
| Game session rounds | game-provider `rounds` | kept |
| Bet and win search by provider round or transaction id, type, status, period, with totals | players `bets`, game-provider `rounds` | kept |
| Dispute report | reporting `players/{p}/dispute-report` | kept |
| Deposits list and detail | merchant `/v1/payments` | kept |

## Withdrawals and payouts (phase 2)

| Capability | Owner | Status |
|---|---|---|
| Withdrawal queue by state, needs-review count, filters | payouts `withdrawal-requests` | kept |
| Approve, reject, confirm manual payment, mark payment failed | payouts | kept |
| Withdrawal request page with its whole story | payouts `withdrawal-requests/{id}` | changed: FR-014, story 4 |
| A player's withdrawal history in every state | payouts `players/{p}/withdrawal-history` | changed: every state |
| Deposit records; per-player turnover override | payouts, wallet `turnover-override` | kept |
| Payments payouts page (substrate payout twins) | payouts | changed: one queue |

## Corrections (phase 2)

| Capability | Owner | Status |
|---|---|---|
| Adjustment: credit or debit, real or restricted, reason | wallet `adjustments` | changed: cause, before and after, refusal not clamp (FR-027) |
| Adjustments to many players | wallet `adjustments` | new (FR-028) |
| Account adjustments (non-player accounts) | wallet `accounts/{kind}/{name}/adjustments` | kept (no screen in the original) |

## Rewards (phase 3)

| Capability | Owner | Status |
|---|---|---|
| Brand-wide reward list with search and state | rewards `reward-grants` | kept |
| A player's rewards with wagering progress | rewards, wallet `restricted-funds` | changed: state actions (FR-026) |
| Forfeit or reclaim | rewards `revoke` | changed: effects stated |
| Grant from an offer; grant by hand (amount, or free rounds) | rewards `reward-grants/{player}` | changed: refusal reasons |
| Offers: create, edit, deactivate, reorder, dependants named, free rounds values, games picker | rewards `offers` | kept |

## Identity and responsible gambling (phase 3)

| Capability | Owner | Status |
|---|---|---|
| Identity queue (documents awaiting, pending, ready for final review, verified, rejected) | kyc `queue` | changed: first-in first-out, place kept |
| Documents: view, approve, reject with reason, move to review | kyc `documents`, `views`, `reviews` | changed: served only to admitted operators (FR-031) |
| Verification mode, status, required documents, override, source of funds, dismiss a flag | kyc | kept |
| KYC policy and licence profile | kyc `policy` | kept |
| Limits set and removed, with pending change | responsible-gambling `limits` | kept |
| Breaks started and lifted; return | responsible-gambling `cooling-off`, `return` | kept |
| Cases list; notes; close | responsible-gambling `cases`, reporting `compliance/rg-cases` | kept (brand-wide open list: gap to confirm) |
| Fraud cases: list, review, dismiss | players `fraud-cases` | kept |

## Games and providers (phase 5)

| Capability | Owner | Status |
|---|---|---|
| Providers list | game-provider `providers` | kept |
| Provider configuration and catalogue refresh | game-provider `providers/{p}` | kept (no screen in the original) |
| Game catalogue: developers, categories, per-game overrides | game-provider `game-catalog`, `game-catalog-config` | kept |
| Unacknowledged callbacks: replay, resolve | game-provider `unacknowledged` | kept (worklist) |

## Brand configuration (phase 5)

| Capability | Owner | Status |
|---|---|---|
| Lobby and deposit URLs | players `brand-config` | kept |
| Country allow-list | players `allow-list` | kept |
| Currency and zone | players `currency-config` | kept |
| Withdrawal settings | payouts `withdrawal-config` | kept |
| Wagering settings and game contribution | wallet `wallet-config` | kept (reward settings beyond turnover: gap to confirm) |
| Deposit minimum and maximum per method | — | gap |
| Terminal capabilities | merchant `terminals`, gateway capability | kept |
| CRM configuration | engagement `crm-config` | kept (the original page had no link; decided 2026-11-15) |
| Support agent configuration | support-agent (spec 017) | kept when merged |
| Modules page | spec 021 | kept |
| Attribution networks, webhooks, signing keys | attribution | kept (no screen in the original) |
| Reporting settings | reporting `settings` | kept |

## Payments administration (phase 5)

| Capability | Owner | Status |
|---|---|---|
| Hierarchy: holdings, companies, sub-brands; status | merchant | kept |
| Payment provider configurations: create, edit, activate, disable, delete, test payment | merchant `psp-configurations` | kept (test payment: gap to confirm) |
| Routing rules: tree, draft, simulate, deploy, retire, roll back; terminals; gateway catalogue; integrations | merchant `routing-rules`, `catalog/gateway`, `merchants/{id}` | kept |
| Risk: blocklist, risk settings | merchant | gap |
| Merchant detail | merchant `/v1/merchants/{id}` | kept |
| Undelivered and refused provider outcomes; redrive | psp-gateway `outcomes` | kept (worklist; no screen in the original) |
| Lost answers | ingest `lost-answers` | kept (no screen in the original) |

## Team (phase 5)

| Capability | Owner | Status |
|---|---|---|
| Operators: list, invite with role and level, resend, reset, revoke | merchant `/v1/operators` | kept |

## Affiliate programme (phase 6)

| Capability | Owner | Status |
|---|---|---|
| Programme dashboard; reporting by campaign and country | programme `revenue/*` | kept |
| Plans | programme `commission-plans` | kept |
| Accounts: list, create, approve, reject, status, edit, notes, audit, password, email, network, reparent | programme `accounts/*` | kept |
| View as the affiliate | programme | gap to confirm |
| Tracking links, creatives | programme | kept |
| Payouts by period: close, status, export | programme `payouts` | kept |
| Fraud flags: dismiss with reason | programme `fraud-flags` | kept (the original logged it locally only) |
| Settings, programme settings, messages, managers | programme | kept |
| Affiliate adjustments | programme `accounts/{id}/adjustments` | kept (unreachable in the original; decided 2026-11-15) |

## Content (none)

| Capability | Owner | Status |
|---|---|---|
| Banners, promotions, categories, pages, media, homepage locales, translation languages, navigation, site and footer configuration, translation workflow | Strapi | dropped (FR-043) |

## Unreachable in the original

| Capability | Status |
|---|---|
| Operators twin page (`OperatorsPage.tsx`) | dropped: the team page covers it |
| Customer.io page (`CustomerIOConfigPage.tsx`) | kept as CRM configuration above |
| Affiliate portal calls (`portalApi.ts`) | dropped: the portal's |
| Affiliate adjustment call | kept: above |
