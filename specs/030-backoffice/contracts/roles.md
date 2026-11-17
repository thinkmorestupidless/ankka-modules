# Permissions, roles, and the role matrix a brand starts with

(FR-005, FR-008 to FR-008d; research R5.)

- A **permission** is one action an operator may take. A route requires exactly one permission and
  names no role. The contract holds the permissions' names (`contract.access.OperatorPermissions`).
- An **operator role** is a bag of permissions, kept by the brand's `players` service and changed
  at run time by the brand's administrators. A token carries roles only (`brand_<slug>` and role
  names); it never carries a permission.
- The **role matrix** below is what a brand starts with: the default roles and their default bags.
  The contract holds it as the seed and as what a default role is reset to.
- `platform_admin` carries every permission, in code, and is not the brand's to change.

Each row is one permission, the default roles whose bag holds it, and the services whose routes
require it. Every route also needs the brand: `brand_<slug>` or `platform_admin`, as
`Routes.operator` checks today. Role names are the realm's, so an operator of two brands holds the
same roles in both; what a role carries is each brand's own.

Roles: **S** `brand_support`, **F** `brand_finance`, **C** `brand_compliance`, **A** `brand_admin`.

| Permission | S | F | C | A | Required by |
|---|---|---|---|---|---|
| `player.read` (profile, standing, sessions, rounds, activity, notes, messages) | ✓ | ✓ | ✓ | ✓ | players, game-provider `rounds`, engagement `backoffice`, support-agent `config` |
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
| `config.brand` (sites, countries, currencies, withdrawal, wagering, reward settings, reporting settings, identity policy, protection settings) | | | | ✓ | players, payouts, wallet, reporting, kyc, responsible-gambling, public-api `status` |
| `config.games` (providers, catalogue, overrides) | | | | ✓ | game-provider |
| `config.crm` | | | | ✓ | engagement |
| `config.agent` | | | | ✓ | support-agent |
| `config.attribution` (networks, webhooks, signing keys) | | | | ✓ | attribution |
| `modules.read`, `modules.request` | | | | ✓ | players `modules` |
| `roles.read` (the brand's roles and what each carries) | ✓ | ✓ | ✓ | ✓ | players `roles` |
| `roles.change` (create, change, reset, delete a role) | | | | ✓ | players `roles` |
| `roles.give` (give and take away an operator's roles) | | | | ✓ | players `role-assignments` |
| `callback.resolve` (unacknowledged calls: list, replay, resolve) | | | | ✓ | game-provider |
| `fact.redeliver` (undelivered facts: list, resend, discard) | | | | ✓ | attribution |
| `send.release` (parked sends: list, retry, discard) | | | | ✓ | engagement |

## Not in this matrix, and unchanged

- **Payments administration** (`Routes.paymentsOperator`, spec 004): `resource_*` by level. These
  roles confer nothing in the table above (clarified 2026-11-15).
- **The affiliate programme** (`Access.manager`): `affiliate_manager`, `brand_admin`,
  `platform_admin`; managers by `brand_admin`.
- **Audit** (`audit_compliance*`, `reidentify_*`) and **access keys** (`api_keys`).
- **The predictions module's `markets` role** (spec 023 FR-004): it still opens `markets`' and
  `exchange`'s operator routes by name (`operatorHolding`), and carries nothing in a brand's bags.
  Before this spec reporting's market report admitted it beside the report roles; under E3 the
  report needs `report.read`, as no `operatorMay` route names a role. A brand whose trading desk
  reads the market report gives it a role carrying `report.read` (review Q6, 2026-11-17; reporting's
  `PredictionsSuite` holds the `markets` role alone refused).

## What changes in each service

| Service | Today | Becomes |
|---|---|---|
| wallet | any operator of the brand adjusts | requires `adjustment.make`, `turnover.override`, `wallet.read`, `config.brand` |
| payouts | `FinanceRoles` (with `resource_admin`, `resource_finance`) in the handler | `withdrawal.read`, `withdrawal.review`, `config.brand` in the ACL |
| players | any operator; `BlockRoles` for one route | the players rows above, per route |
| kyc | `ComplianceRoles` (with `compliance_officer`, `resource_admin`) in the handler | `identity.read`, `identity.review`, `config.brand` |
| responsible-gambling | any operator | `protection.read`, `protection.change`, `config.brand` |
| rewards | any operator lists and grants; admin roles read one | `reward.read`, `reward.grant`, `reward.revoke`, `offer.manage` |
| game-provider | any operator | `player.read` on rounds, `config.games`, `callback.resolve` |
| engagement, support-agent | `operatorHolding(brand_admin, resource_admin, platform_admin)` | `config.crm`, `config.agent`, `presence.read`, `send.release` |
| reporting | `Access` (with `resource_admin`) | the reporting rows above |
| attribution | any operator | `config.attribution`, `fact.redeliver` |
| players | — | keeps the brand's roles; every other service of the brand reads them from it |
| merchant | payments operators only | also admits an operator holding `brand_<slug>` to read the deposits of the sub-brand naming that brand (a read; merchant is shared and opens no brand's bag); sets an operator's roles at a brand's request |

## How a service opens a bag

`contract.access.RoleBags` is the one helper every service of a brand uses:

- it asks the brand's `players` service for the roles (`GET …/roles`, admitted to every service of
  the project) and keeps the answer; it asks again every 10 seconds;
- `Routes.operatorMay(slug, permission)` admits a token when the bag of one of its roles holds the
  permission, from the kept answer; it makes no call in the request;
- until the first answer it decides `Unavailable` (503 with `Retry-After`), never admit or refuse;
  when `players` cannot be reached it keeps the last answer and says so in the service's status;
- `players` itself reads its own roles directly.

A change is therefore in force everywhere within 10 seconds plus one request, inside FR-008b's 15.
The backoffice's server uses the same routes to answer `/api/me` and `/api/permissions`.

## Rules the owner holds (players)

- a role's permissions must all be names the contract knows;
- the last role carrying `roles.change` cannot lose it or be deleted; one writer (`RoleChangers`)
  decides it, so two changes at once cannot both take it away;
- a change or reset of a role may add only permissions its changer holds: one that would add another
  is refused `403 forbidden`, naming the permission, as a giving is (so `roles.change` is not every
  permission; `brand_admin` and `platform_admin` hold every permission and are not limited by it);
- a default role can be changed and reset to the matrix above, and cannot be deleted; a role the
  brand created can be deleted;
- no realm role but the four default roles, and no name under `brand_`, `audit_compliance_` or
  `reidentify_` but the default roles, is a role a brand can create, change or give: that is
  `platform_admin`, every `brand_<slug>`, the payments roles, and every other role an ACL checks by
  name (`audit_compliance`, `affiliate_manager`, `api_keys`, …). `OperatorPermissions.RealmRoles`
  lists the realm's roles and a contract test holds it equal to `brands/_realm/eitheror-operators.json`,
  so a new realm role is reserved by being added to the realm;
- giving roles (`role-assignments`) is refused when the giver's own permissions do not include
  every permission the roles carry, or the roles it takes away from the operator; when it passes, `players` asks merchant to set them;
- every change and every giving is an audit record.

## Published form

`contract/schemas/access/operator-permissions.v1.json`: the permissions' names and the role matrix
(the seed), for the backoffice's typed catalogue of actions. What a role carries at run time is
never read from this file.

```json
{ "version": 1,
  "permissions": ["adjustment.make", "withdrawal.review"],
  "defaults": { "brand_finance": ["adjustment.make", "withdrawal.review"] } }
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
for payments administration. The mapping gives default roles only; a brand's own roles are given
afterwards, in its backoffice.
