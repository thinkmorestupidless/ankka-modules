# Implementation Plan: Per-brand realm roles

**Branch**: `032-per-brand-roles` | **Date**: 2026-11-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/032-per-brand-roles/spec.md`. The pull request targets
`030-backoffice`, which merges only after this.

## Summary

A role an operator holds in a brand becomes a realm role named for that brand, `<role>@<slug>`, and
every reader opens only the roles named for its own brand. The rule lives once in the contract
(`contract.access.BrandRoles`, used by `RoleBags`, `OperatorPermissions` and `Routes`) and once in
the backoffice server, both checked against one shared table of cases. Merchant's H6 stops changing
the realm in the request: it records the giving on the operator's entity, and a consumer of that
entity brings the realm to the last recorded set, retrying while the realm is unreachable and
ending the giving as failed when the realm refuses (research R4, R5). The modules tool makes each
brand's named roles at apply and carries the old unbranded roles over (R7). The programme, public
API and the predictions desk read their named roles per brand (R8, R9).

## Technical Context

**Language/Version**: Scala 3 (the contract, merchant, players, programme, public-api, markets,
exchange, kyc, reporting, the modules tool); TypeScript (the backoffice server and page).

**Primary Dependencies**: ankka SDK as each build pins it (merchant 0.13.0, players 0.16.3; 031
moves them to 0.18.0, no change here depends on it); Keycloak 26.0.7 admin REST API.

**Storage**: merchant's `Operator` event-sourced entity gains four events (data-model.md). No new
store.

**Testing**: munit and the testkit's `GherkinSuite` per service; the contract's unit suite and the
shared case table; the backoffice's vitest and playwright-bdd over its doubles; merchant's
`FakeKeycloak` for the applier, and one Keycloak container suite (`StateIt`'s image) for R1.

**Target Platform**: ankka installations; Keycloak's `eitheror` realm.

**Project Type**: changes across services of a brand, one shared project (`programme`), merchant
(`payments`) and the modules tool.

**Performance Goals**: a giving is in force within 30 s of being recorded, or of the realm answering
again (SC-002); the applier's work per giving is one read of the operator's realm roles plus one call
per role changed.

**Constraints**: a giving never touches a role not named for its brand (FR-006); every realm write
is safe to repeat; no real-money brand is live, so the cutover is the only migration (Assumptions).

**Scale/Scope**: tens of operators per brand, single-digit brands per installation; 100 concurrent
givings in SC-003.

## Constitution Check

| Principle | Holds? | How |
|---|---|---|
| I. The original is the specification | Yes, with a recorded change | The original runs one brand per deployment; per-brand roles are listed under the spec's *Changes from the original* (research R11). |
| II. Money is exact | Not touched | No money path changes. |
| III. A brand is a project | Yes, it is the point | A role held in one brand opens nothing in another; a brand's giving changes only its own roles; `players` reaches merchant's H6 only through the brand's existing grant. |
| IV. Commands over HTTP, facts over topics | Yes | H6 stays a command; the applier is merchant's own consumer of its own entity; no other service reads merchant's journal. |
| V. Personal data | Not touched | A giving holds an operator id and role names, as H6 does today. |
| VI. Scala by default | Yes | Every change is in the language its service already uses. |
| VII. Specs in the domain's words | Yes | Scenarios in `features/`, terms in the glossary (`giving`), the checker green. |

No violation; Complexity Tracking is empty. Re-checked after Phase 1: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/032-per-brand-roles/
├── spec.md
├── plan.md
├── research.md          # R1–R11
├── data-model.md        # brand role, giving, merchant's events
├── quickstart.md
├── contracts/
│   ├── brand-roles.md   # the name, who reads it, the case table
│   └── amendments.md    # H6, H6r, P13, 013, 016, 023, the modules tool, 019
└── tasks.md             # /speckit-tasks
```

### Source code

```text
contract/src/main/scala/io/eitheror/casino/contract/access/
├── BrandRoles.scala            # new: nameIn, heldIn, brandOf
├── RoleBags.scala              # opens heldIn(slug, roles) only
├── OperatorPermissions.scala   # permissionsOf(slug, roles, bags); reserved names
└── Routes.scala                # operatorHolding, accessKeys, affiliateManager, brandsOf
contract/src/test/resources/brand-roles.cases.json   # shared with the backoffice

services/merchant/src/main/scala/io/eitheror/casino/merchant/
├── operators/Operator.scala          # BrandRolesAsked / Applied / Failed
├── operators/KeycloakAdmin.scala     # Unreachable vs Refused
├── operators/BrandRolesApplier.scala # new consumer
└── api/OperatorsEndpoint.scala       # H6 records; H6r reads

services/players/src/main/scala/io/eitheror/casino/players/
├── api/RolesRoutes.scala      # P13: giving state, api_keys/markets
└── roles/RoleRules.scala      # names a brand may give

services/backoffice/src/server/{roles.ts,main.ts}   # the filter, /api/me
services/backoffice/src/web/areas/team/         # the giving's state shown

services/affiliate-programme/src/main/scala/io/eitheror/casino/programme/api/{Managers,Access}.scala
services/public-api/…/api/KeysEndpoint.scala, services/markets/…/api/OperatorEndpoint.scala,
services/exchange/…/api/OperatorEndpoint.scala       # slug-aware named roles (via Routes)
services/kyc/…/api/OperatorEndpoint.scala, services/reporting/…/api/Documents.scala  # slug passed
tools/modules/src/main/scala/io/eitheror/casino/modules/Realm.scala   # named roles, carry-over
brands/_realm/eitheror-operators.json                # unbranded default roles kept until carried
```

**Structure Decision**: no new service or project. One new contract object, one new merchant
consumer; everything else is a change to code that reads or writes roles today.

## Where each new scenario is bound

A design leaves them unbound; the build binds every one or the suites fail (an undefined step fails
a `GherkinSuite` scenario, and the backoffice runs playwright-bdd with `missingSteps: "fail-on-run"`).

| Feature | Scenarios | Bound in |
|---|---|---|
| `backoffice/access.feature` | the 4 added | `services/backoffice/tests/features/steps/access.steps.ts`, over the doubles, with tokens carrying named roles |
| `backoffice/roles.feature` | the 3 added: not yet in force, refused shown, the access keys and markets roles | `roles.steps.ts`, over the doubles (players answers the giving's state) |
| `payments/operators.feature` | the 4 added: part-way, two brands at once, later replaces earlier, refused not tried again | merchant's `OperatorsFeatures`, over `FakeKeycloak` (which gains a switch to stop answering and to refuse a user) |
| `brands/modules.feature` | the carry-over | the modules tool's `ModulesFeatures`, over its in-memory realm admin |
| `predictions/lifecycle.feature` | the markets scenario | `services/markets/.../features/LifecycleFeatures.scala` |
| `programme/managers.feature` | the manager scenario | `services/affiliate-programme/.../features/ManagerSteps.scala` |
| `public-api/keys.feature` | the changed row | `KeysFeatures.scala:27` (already renamed; its token becomes `api_keys@spinvibe`) |
| `migration/operator-roles.feature` | the outline's Then, the take-away scenario | spec 019's suite, which does not exist yet; nothing runs the file today, and 032 adds no binding |

## Complexity Tracking

None.
