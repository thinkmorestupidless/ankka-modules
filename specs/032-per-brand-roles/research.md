# Research: Per-brand realm roles

Phase 0 of the plan. Each decision says what was chosen, why, and what else was weighed. Code
references are to this branch (`032-per-brand-roles`, cut from `030-backoffice` at `cffcf762`).

## R1. A brand role's name in the realm

- **Decision**: `<role>@<slug>`: `brand_finance@goldvibe`, `vip_host@goldvibe`, `api_keys@goldvibe`.
  The contract builds and reads it in one place, `contract.access.BrandRoles` (`nameIn(slug, role)`,
  `heldIn(slug, roles)`). Every other reader uses it.
- **Rationale**: Trevor's example. A role name a brand makes matches `[a-z0-9_]{1,40}`
  (`OperatorPermissions.validRoleName`), so no brand's role name can contain `@`, and splitting on
  the last `@` is unambiguous. A slug cannot contain `@` either.
- **To prove in the build** (first task): Keycloak 26.0.7 (`backoffice-state`'s `StateIt` image)
  creates a realm role named `brand_finance@goldvibe` through the admin API, assigns it, and puts
  it in an access token's `realm_access.roles` unchanged. If it refuses, the separator becomes `:`,
  changed only in `BrandRoles`.
- **Proven** (T001, 2026-11-20): `RealmRoleNameIt` in `services/backoffice-state` creates, finds,
  assigns and signs in with `brand_finance@goldvibe` on Keycloak 26.0.7; the token carries it
  unchanged. The separator stays `@`.
- **Alternatives**: a role per brand under a prefix (`goldvibe_brand_finance`): ambiguous where
  slugs and role names both contain `_`. Client roles of a per-brand client: a token then carries
  them under `resource_access.<client>`, a second claim every reader would need; rejected as the
  larger change.

## R2. Where a role is opened: once, in the contract

- **Decision**: `RoleBags.permissionsOf(slug, roles)` opens only `heldIn(slug, roles)`; the brand is
  named at the call (*Found during implementation*). `OperatorPermissions.permissionsOf(roles, bags)` gains the slug.
  `Routes.operatorHolding(slug, roles*)` and `Routes.accessKeys(slug)` check `nameIn(slug, role)`.
  `platform_admin` stays a bare name everywhere.
- **Rationale**: the readers today are `Routes.operatorMay` (every service), and three direct
  callers: kyc's `OperatorEndpoint.holds`, reporting's `Documents.held` and players' `RolesRoutes`.
  Changing the shared helper fixes all of them; no service keeps its own copy of the rule.
- **The backoffice server** (TypeScript) has its own twin, `services/backoffice/src/server/roles.ts`
  (`permissionsOf`, `inBrand`), and `/api/me` returns every token role. It gains the same filter:
  `/api/me` returns the roles held in this brand, by their names without the brand (FR-005).
  A shared vector of cases (`contract/src/test/resources/brand-roles.cases.json`) is run by both the
  contract's suite and the backoffice's, so the two cannot drift.

## R3. Reading a brand from a role name

- **Decision**: `Routes.brandsOf` reads a brand only from `brand_<slug>` whose rest contains no `@`
  and is not one of the four default role names (`admin`, `finance`, `compliance`, `support`).
- **Rationale**: today `brand_admin` is read as the brand `admin` (`Routes.scala:217`), and after
  032 `brand_admin@goldvibe` would be read as the brand `admin@goldvibe`. Neither is a brand.

## R4. Applying a giving: a consumer of merchant's operator entity

- **Decision**: H6's PUT records the giving on merchant's event-sourced `Operator` entity
  (`BrandRolesAsked(brand, roles, version, at)`) and answers `202` with the giving as `pending`. A
  consumer of that entity's events, `BrandRolesApplier`, brings the realm to the asked set:
  1. it reads the entity; when a later version is recorded for that brand, it does nothing (the
     later event follows);
  2. it creates any missing realm role named for the brand, assigns what is asked and missing, and
     removes every role named for the brand that is held and not asked (`<anything>@<slug>`, so a
     cutover's role goes too);
  3. on success it records `BrandRolesApplied(brand, version)`;
  4. when the realm cannot be reached, it fails, and the change is delivered again;
  5. when the realm refuses outright, it records `BrandRolesFailed(brand, version, reason)` and
     succeeds.
- **Rationale**: a consumer sees one entity's changes in order, at least once, and a failed change
  is delivered again (ankka docs, *Consistency and delivery*). So an older giving can never be
  applied after a newer one, a crash part-way resumes, and every realm write sets the brand's roles
  to the asked set, which is safe to repeat. Roles now carry their brand, so a brand's giving
  touches only names ending `@<slug>`. The `others` read-then-act of today's H6 disappears, and
  two brands cannot undo each other (FR-008).
- **Alternatives**: the workflow keyed by the operator that 030's review suggested. Two givings by
  one brand would then need their own serialising: a second workflow, or a claim on the entity. A
  consumer has it already. A timer per giving: also retried with backoff, but unordered between
  givings.
- **Backoff**: the consumer's restart backoff (the projection's), from 1 s doubling to a ceiling of
  10 s, so a giving is in force within 30 s of the realm answering again (SC-002). A realm that
  stays unreachable holds back that slice's later changes, which is right: nothing can be applied
  until it answers.
- **Its own events**: the applier acts only on `BrandRolesAsked`; `Applied` and `Failed` pass by.

## R5. Unreachable or refused

- **Decision**: `KeycloakAdmin`'s `RealmError.Failed` is split into `Unreachable` (no answer, a
  timeout, 5xx, 429) and `Refused(status, reason)` (any other 4xx). `NotFound` (the operator) is a
  refusal. The applier retries `Unreachable` and records the rest as failed (clarification
  2026-11-20).
- **Rationale**: today `Failed` folds a refused connection and a 400 together
  (`KeycloakAdmin.scala:80-98`), so nothing could tell a retry from an end.

## R6. Where the giving's state is read

- **Decision**: merchant answers `GET /v1/operators/{id}/brand-roles/{brand}`
  (`{roles, version, state: pending | in_force | failed, reason?, at, held}`), admitted as H6 is;
  `held` is the realm roles named for the brand now, so a carried-over role no giving asked for is
  seen, and can be taken away. players'
  `role-assignments/{operator}` GET adds it to what it answers. The backoffice shows it beside the
  operator's roles (FR-009).
- **Rationale**: the realm, and the record of what reached it, are merchant's; players keeps what
  the brand gave (`RoleAssignment`) and the permission checks, as in 030.

## R7. The default roles and named roles at apply; carrying old roles over

- **Decision**: the modules tool's `Realm.ensureBrandRole` (`tools/modules/.../Realm.scala:229-270`)
  makes `<default>@<slug>` for the four default roles, and `api_keys@<slug>`,
  `markets@<slug>` and `affiliate_manager@<slug>`, beside `brand_<slug>` and the audit roles. It then
  carries over (FR-013): for each unbranded role this spec names per brand, each user holding both
  it and `brand_<slug>` is given `<role>@<slug>`. A user who then holds `<role>@<b>` for every
  `brand_<b>` they hold loses the unbranded role. A second apply finds nothing to do.
- **Rationale**: the tool already makes this brand's realm roles at apply, and is the one step every
  brand passes through. Its admin client gains `usersWithRole`, `assign` and `remove`, the calls
  the programme's client already makes. `roleExists` and `createRole` encode the role's name.
- **Alternatives**: a one-off script per installation: no record, and easy to skip on one.

## R8. The programme's managers

- **Decision**: `Managers.scala` gives `affiliate_manager@<slug>` and `brand_<slug>`, lists
  `usersWithRole("affiliate_manager@<slug>")`, and on revoke removes `affiliate_manager@<slug>`.
  `Routes.affiliateManager` admits `affiliate_manager@<b>`, `brand_admin@<b>` or `platform_admin`.
  The brands a manager manages are those `b` with `brand_<b>` and either role named for `b`, not
  `brandsOf`. `Access.ManagerAdminRoles` follows (`brand_admin@<b>` for the path's brand).
- **Rationale**: FR-012. The programme writes these roles itself (spec 013), not through a brand's
  giving: it is the shared project's own administration, and a manager is not a brand operator.

## R9. `api_keys` and `markets` given by a brand

- **Decision**: `RoleRules.nameRefusal` and H6's `BrandRoles.refused` stop refusing `api_keys`
  and `markets`. A giving may carry them beside bag roles, and `RolesRoutes.give` checks only
  `roles.give` for them, as neither carries a bag permission (clarification 2026-11-20). They are
  not listed by `GET …/roles` (no bag) and cannot be made, changed or reset there.
- **Rationale**: the brand gives everything held in it, through one path with the giving's
  guarantees.

## R10. The cutover (spec 019)

- **Decision**: the migration's role step gives `<mapped>@<slug>` for every brand the original's
  scope reached, through H6, so each is a recorded giving its brand can replace (FR-011).
- **Rationale**: a role the cutover gave is then a giving like any other; and the applier removes
  any role named for the brand that is not asked, so nothing given before a brand's first giving
  can stay out of its reach.

## R11. Recordings and parity

- The behaviour is new (spec, *Changes from the original*): the original runs one brand per
  deployment and has no per-brand roles. There is no recording to replay. The parity left behind is
  030's role scenarios, still green, plus 032's scenarios.

## R12. Each scenario lives with the suite that can make it true

- **Decision**: the realm's side of a giving (part-way, two brands at once, later replaces earlier,
  refused and not tried again) moved from `features/backoffice/roles.feature` to
  `features/payments/operators.feature`, which merchant's `OperatorsFeatures` runs; the carry-over
  moved to `features/brands/modules.feature`, which the modules tool's `ModulesFeatures` runs. The
  refusal was split: the brand's view of it stays in the backoffice's feature. Wording is unchanged
  otherwise; the spec's references follow.
- **Rationale**: the backoffice suite runs over service doubles, so the realm's behaviour bound there
  would test a double. playwright-bdd fails any scenario of its files without steps, and the
  repository uses no tags to divide a file between suites.

## Found while researching, not in scope

- Affiliates are provisioned `affiliate` and `brand_<slug>` at the same realm
  (`Provisioning.scala:70`), so `Routes.operator(slug)` (any operator of the brand) admits an
  affiliate signed in at `eitheror`. Routes under `operatorMay` and `operatorHolding` do not, as
  an affiliate holds no bag role. Worth its own fix; it is neither 030's nor 032's change.
- A manager's revoke removes `brand_<slug>` (`Managers.scala:84`), which an operator of that brand
  also holds. 032 keeps that behaviour; whether a revoke should only remove the manager role is the
  programme's question.

## Found during implementation

- **RoleBags names the brand at the call** (Trevor, 2026-11-20, T006): `RoleBags.permissionsOf(slug,
  roles)` and `OperatorPermissions.permissionsOf(slug, roles, bags)` rather than a slug given when a
  `RoleBags` is made. Every reader states its brand where it decides, the compiler finds every caller,
  and no construction in a service or test kit changes. `contracts/brand-roles.md` amended.

## Design finished

2026-11-20: analyze reported nothing above LOW (round 2: one LOW, applied; T002 is a confirmation task, left).
