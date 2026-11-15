# The operator role matrix

What `contract.access.OperatorActions` holds, once (FR-005, FR-008; research R5). Each row is one
action: its name in the contract, the roles that admit it, and the services whose routes it guards.
`platform_admin` is admitted to every action and is not repeated. Every action also needs the
brand: `brand_<slug>` or `platform_admin`, as `Routes.operator` checks today.

Roles: **S** `brand_support`, **F** `brand_finance`, **C** `brand_compliance`, **A** `brand_admin`.

| Action | S | F | C | A | Guards |
|---|---|---|---|---|---|
| `player.read` (profile, standing, sessions, rounds, activity, notes, messages) | ✓ | ✓ | ✓ | ✓ | players, game-provider `rounds` |
| `wallet.read` (balances, restricted funds, lifetime totals, transactions) | ✓ | ✓ | ✓ | ✓ | wallet |
| `withdrawal.read` (queue, request, a player's history) | ✓ | ✓ | ✓ | ✓ | payouts |
| `reward.read` (list, a reward, a player's rewards) | ✓ | ✓ | ✓ | ✓ | rewards |
| `identity.read` (a player's identity status; not documents) | ✓ | ✓ | ✓ | ✓ | kyc |
| `protection.read` (limits, breaks, cases, history) | ✓ | ✓ | ✓ | ✓ | responsible-gambling |
| `note.write` (add, pin, unpin) | ✓ | ✓ | ✓ | ✓ | players |
| `message.send` (inbox item, preview, merge tags) | ✓ | ✓ | ✓ | ✓ | players |
| `presence.read` (who is online, the online notice) | ✓ | ✓ | ✓ | ✓ | engagement |
| `adjustment.make` (player wallets and non-player accounts) | | ✓ | | ✓ | wallet |
| `turnover.override` | | ✓ | | ✓ | wallet |
| `withdrawal.review` (approve, reject, confirm a payment, mark it failed) | | ✓ | | ✓ | payouts |
| `reward.grant`, `reward.revoke` | | ✓ | | ✓ | rewards |
| `deposit.read` (a brand's deposits and their payments) | ✓ | ✓ | ✓ | ✓ | merchant, by the brand's sub-brand |
| `standing.change` (suspend, reinstate, close, reopen) | | | ✓ | ✓ | players |
| `profile.change` (profile, date of birth, password reset, verification link, consents) | | | ✓ | ✓ | players |
| `session.end` (force-end one or all) | | | ✓ | ✓ | players |
| `flag.clear` (suspected duplicate, no-deposit block) | | | ✓ | ✓ | players |
| `fraud.review` (list, review, dismiss) | | | ✓ | ✓ | players |
| `identity.review` (queue, documents, view, decide, mode, status, required, override, source of funds, dismiss a flag) | | | ✓ | ✓ | kyc |
| `protection.change` (limits, breaks, self-exclusion, return, cases) | | | ✓ | ✓ | responsible-gambling |
| `erasure.request` | | | ✓ | ✓ | players |
| `export.player` (activity export, dispute report) | | ✓ | ✓ | ✓ | players, reporting |
| `tier.change` (VIP tier, potential VIP, manager tags) | | | | ✓ | players |
| `dashboard.read` | ✓ | ✓ | ✓ | ✓ | reporting |
| `report.read` (revenue, players, transactions, rewards, payment providers) | | ✓ | | ✓ | reporting |
| `report.dayEnd` | | ✓ | | ✓ | reporting |
| `report.compliance` | | | ✓ | ✓ | reporting |
| `offer.manage` | | | | ✓ | rewards |
| `config.brand` (sites, countries, currencies, withdrawal, wagering, reward settings, reporting settings, identity policy, protection settings) | | | | ✓ | players, payouts, wallet, reporting, kyc, responsible-gambling |
| `config.games` (providers, catalogue, overrides) | | | | ✓ | game-provider |
| `config.crm` | | | | ✓ | engagement |
| `config.agent` | | | | ✓ | support-agent |
| `config.attribution` (networks, webhooks, signing keys) | | | | ✓ | attribution |
| `modules.read`, `modules.request` | | | | ✓ | players `modules` |
| `callback.resolve` (unacknowledged calls: list, replay, resolve) | | | | ✓ | game-provider |
| `fact.redeliver` (undelivered facts: list, resend, discard) | | | | ✓ | attribution |
| `send.release` (parked sends: list, retry, discard) | | | | ✓ | engagement |

## Not in this matrix, and unchanged

- **Payments administration** (`Routes.paymentsOperator`, spec 004): `resource_*` by level. These
  roles confer nothing in the table above (clarified 2026-11-15).
- **The affiliate programme** (`Access.manager`): `affiliate_manager`, `brand_admin`,
  `platform_admin`; managers by `brand_admin`.
- **Audit** (`audit_compliance*`, `reidentify_*`) and **access keys** (`api_keys`).

## What changes in each service

| Service | Today | Becomes |
|---|---|---|
| wallet | any operator of the brand adjusts | `adjustment.make`, `turnover.override`, `wallet.read`, `config.brand` |
| payouts | `FinanceRoles` (with `resource_admin`, `resource_finance`) in the handler | `withdrawal.read`, `withdrawal.review`, `config.brand` in the ACL |
| players | any operator; `BlockRoles` for one route | the players rows above, per route |
| kyc | `ComplianceRoles` (with `compliance_officer`, `resource_admin`) in the handler | `identity.read`, `identity.review`, `config.brand` |
| responsible-gambling | any operator | `protection.read`, `protection.change`, `config.brand` |
| rewards | any operator lists and grants; admin roles read one | `reward.read`, `reward.grant`, `reward.revoke`, `offer.manage` |
| game-provider | any operator | `player.read` on rounds, `config.games`, `callback.resolve` |
| engagement, support-agent | `operatorHolding(brand_admin, resource_admin, platform_admin)` | `config.crm`, `config.agent`, `presence.read`, `send.release` |
| reporting | `Access` (with `resource_admin`) | the reporting rows above |
| attribution | any operator | `config.attribution`, `fact.redeliver` |
| merchant | payments operators only | adds `deposit.read` for a brand's own sub-brand (`projectOrPaymentsOperator`'s shape) |

## Published form

`contract/schemas/access/operator-actions.v1.json`:

```json
{ "version": 1,
  "roles": ["brand_support", "brand_finance", "brand_compliance", "brand_admin", "platform_admin"],
  "actions": [ { "action": "adjustment.make", "roles": ["brand_finance", "brand_admin", "platform_admin"] } ] }
```

## Cutover mapping (FR-008a, amends spec 019)

| Original role | Given |
|---|---|
| `platform_admin` | `platform_admin` |
| `resource_admin`, `brand_admin` | `brand_admin` |
| `resource_finance`, `brand_finance` | `brand_finance` |
| `compliance_officer`, `brand_compliance`, `fraud_analyst` | `brand_compliance` |
| `support_agent`, `brand_support`, `resource_viewer` | `brand_support` |
| `resource_owner`, `resource_operator`, `resource_developer`, `casino_admin`, or more than one row above | `brand_support`, and listed for review |

Each keeps `brand_<slug>` for every brand their original scope reached, and their payments roles
for payments administration.
