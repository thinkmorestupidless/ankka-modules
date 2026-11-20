# Quickstart: validating per-brand roles

No kind and no running installation is needed; every check below runs on one machine.

## Prerequisites

- `uv` (for `just features`), Docker (merchant's and backoffice-state's integration suites), Node
  with `npm ci` at the root.
- A unique contract version for this branch (other worktrees overwrite the shared snapshot):
  `export CASINO_VERSION=0.2.0-032-SNAPSHOT`.

## Checks, in order

| Step | Command | Expected |
|---|---|---|
| 1. The name survives Keycloak (R1) | `just service backoffice-state -Dit=true '"testOnly *RealmRoleNameIt"'` | a role `brand_finance@goldvibe` is created, assigned, and in the token's `realm_access.roles` unchanged |
| 2. The rule | `just test` (contract) | `BrandRolesSuite` passes every row of `brand-roles.cases.json` |
| 3. The backoffice's twin | `just backoffice-test` | `roles.test.ts` passes the same rows |
| 4. The giving | `just service merchant "testOnly *OperatorsFeatures *BrandRolesApplierSuite"` | the four `payments/operators.feature` scenarios pass; the applier suite's 100 concurrent runs end as asked (SC-003) |
| 5. Players | `just service players test` | P13's giving state, `api_keys` and `markets` given |
| 6. Named roles | `just service public-api test`, `just service markets test`, `just service affiliate-programme test` | the changed keys row, the markets and manager scenarios pass |
| 7. Apply and carry-over | `just test` (the root build runs the modules tool's suites) | the carry-over scenario in `brands/modules.feature`; a second apply reports nothing to do |
| 8. The backoffice's scenarios | `just backoffice-features` | `access.feature`'s 4 and `roles.feature`'s 3 new scenarios pass |
| 9. The living features | `just features` | `0 findings` |

`features/migration/operator-roles.feature` is spec 019's and stays unbound (plan, *Where each new
scenario is bound*).
