# Review: Per-brand realm roles (spec 032, origin/030-backoffice...HEAD)

**Verdict:** Request changes
**What it does:** A role an operator holds in a brand becomes the realm role `<role>@<slug>`, and every
reader (contract `RoleBags`/`OperatorPermissions`/`Routes`, kyc, reporting, players, the programme, the
backoffice server) opens only the roles named for its own brand. Merchant's H6 now records a versioned
giving and answers `202`; a new consumer, `BrandRolesApplier`, brings the realm to it, retrying an
unreachable realm and failing a refused one. The modules tool makes the named roles at apply and
carries old unbranded roles over.
**Should it exist:** yes. It closes 030's blocking cross-brand escalation and the three H6 findings 030
declined into it. It does this with one rule in the contract and a twin in the backoffice, held
together by a shared case table. Recording the giving first and applying it from a consumer is the fix
030's review asked for.
**Read:** in full: the contract's `BrandRoles`, `OperatorPermissions`, `RoleBags`, `Routes`; merchant's
`OperatorsEndpoint`, `Operator`, `BrandRolesApplier`, `KeycloakAdmin`, `AuditFacts`, `FakeKeycloak`,
`BrandRolesApplierSuite`, `OperatorEntitySuite`, `KeycloakAdminSuite`, `OperatorsFeatures`; players'
`RolesRoutes`, `RoleRules`, `Endpoints`; the programme's `Access`, `Managers`, `OperatorEndpoint`;
kyc and reporting main changes; the backoffice's `roles.ts`, `access.ts`, `main.ts`, `events.ts`,
`routes.ts`, `assign.tsx`, `roles.test.ts`; the modules tool's `Realm.scala`, `ModulesFeatures`,
`RealmPlayersSuite`, `RealmBackofficeClientSuite`; `brand-roles.cases.json`, `BrandRolesSuite`; the
spec, plan, tasks, research R7–R9 and data-model. Skimmed: the per-service kit changes that only wrap
roles in `BrandRoles.nameIn` (mechanical), the backoffice doubles and step files, the feature files, the
019/030 doc amendments, and the generated `owners/players.ts`. Opened outside the diff:
`OperatorPermissions.RealmRoles`/`reserved`, `Layout.ReservedSlugs`, `Values.rules`, `OperatorsView`,
`MerchantCodecs`, the provider's `ProviderEndpoint` (its `operatorRefusal` call is unaffected: `games`
holders return before it), the programme's `KeycloakAdmin` (`enc` encodes `@`), `TeamPage.tsx` and
`operators.tsx`, 030's `REVIEW.md` (the findings carried here), and ankka's consumer docs (redelivery and
virtual threads).
**Size:** 155 files, +2925 −402. That is far over the split threshold. Most of it is mechanical
test-kit renames, and the commits are already split by layer (contract, readers, merchant, players,
backoffice, modules tool, docs), so no further split is asked for. Review those commits one by one.

## Blocking

- `services/merchant/src/main/scala/io/eitheror/casino/merchant/operators/BrandRolesApplier.scala:78`
  - issue (blocking): The applier removes every role named for the brand that is held and not asked.
    That includes `affiliate_manager@<slug>`, which the programme gives (research R8, data-model.md:15)
    and the modules tool carries over. A brand cannot keep that role by asking for it, because H6
    refuses `affiliate_manager` as reserved (`OperatorPermissions.RealmRoles`, and the "request is
    refused" step in `OperatorsFeatures` asserts the 400).
  - Why it matters: say a programme manager also works in the brand. The brand's next giving to that
    user, even one that only adds `brand_support`, silently takes away their manager role. Nobody asked
    for that, and the brand cannot undo it. FR-007's "whoever gave it" contradicts R8, which says the
    programme writes this role outside any giving.
  - Suggestion: remove only the names a brand may give, and leave the programme's role alone. For
    example, in `apply`:
    ```scala
    _ <- each(held.filter(r => BrandRoles.brandOf(r).exists((base, b) => b == brand && base != "affiliate_manager")) -- target)(realm.removeRole(id, _))
    ```
    A better form is a contract set, `BrandRoles.NotGiven` (or `ProgrammeOwned`), that both this filter
    and H6's `refused` read. Add an applier-suite case with `affiliate_manager@goldvibe` held across a
    giving, and add the carve-out to FR-007 and `contracts/brand-roles.md`.
  - Resolved: the applier takes away only `BrandRoles.givenBy(brand, _)`, which excludes the contract's `ProgrammeGiven`; a `BrandRolesApplierSuite` case holds `affiliate_manager@goldvibe` across a giving; FR-007 and the H6a row of `contracts/amendments.md` carry the carve-out, f5aaf9a4, 23a2babc

## Should fix

- `services/merchant/src/main/scala/io/eitheror/casino/merchant/api/OperatorsEndpoint.scala:250`,
  `services/players/src/main/scala/io/eitheror/casino/players/roles/RoleRules.scala:27`,
  `services/backoffice/src/web/areas/team/assign.tsx:21`,
  `tools/modules/src/main/scala/io/eitheror/casino/modules/Realm.scala:246`
  - suggestion: `Set("api_keys", "markets")` is spelled out 3 times, and the modules tool spells
    `"api_keys", "markets", "affiliate_manager"` a fourth. The plan says the rule lives once in the
    contract. If a fifth named role arrives and one copy is missed, brands will either refuse to give it
    or fail to carry it over.
  - Fix: add `BrandRoles.Given` and `BrandRoles.Named` (Given plus `affiliate_manager`, the roles routes
    check by name) to the contract. Read them from merchant, players and the tool. Carry them into the
    backoffice through the generated permissions file, as the permissions already are.
  - Resolved: `BrandRoles.Given`, `ProgrammeGiven` and `Named` in the contract; merchant, players and the modules tool read them; the permissions file publishes `given` and the page reads `GivenRoles`, f5aaf9a4, 3e10cf05, 9d47c4b4
- `services/merchant/src/main/scala/io/eitheror/casino/merchant/audit/AuditFacts.scala:81`
  - issue (non-blocking): FR-014 (every giving, outcome and refusal is an audit record) has no test.
  - The four new `OperatorEvent` cases map to `Audited` records, but no suite checks the mapping.
  - `OperatorsFeatures.scala:142` says "each refusal is an audit record (FR-014)", yet the line after
    it asserts only `givings.isEmpty`. That check would pass if `rejectBrandRoles` were never called.
  - Suggestion: add an `AuditFacts.operator` unit test for `BrandRolesAsked`, `Applied`, `Failed` and
    `Rejected`, covering the brand, the actor, the outcome and the reason. Then either assert in the
    step that the entity's events include `BrandRolesRejected`, or drop the comment.
  - Resolved: an `AuditFactsSuite` test for the four records (action, brand, actor, outcome, reason); the step's comment now says the record is that suite's, 3e10cf05
- `contract/src/main/scala/io/eitheror/casino/contract/Layout.scala:50`
  - issue (non-blocking): `brandOfRole` (`Routes.scala:239`) no longer reads `brand_admin` as the brand
    `admin`, which is the spec's edge case. But `Layout.ReservedSlugs` still admits the slugs `admin`,
    `support`, `finance` and `compliance`.
  - For such a brand, `operatorRefusal(p, "admin")` accepts the bare default role `brand_admin` as
    "works in this brand", while `brandsOf` says the same token holds no brand. The two readers
    disagree, and a legacy bare `brand_admin` holder passes the brand check of a brand they were never
    added to.
  - Suggestion: add the default roles' suffixes (`DefaultRoles.keys.map(_.stripPrefix("brand_"))`) to
    `ReservedSlugs`, with a `Values.rules` case, so the edge case cannot arise.
  - Resolved: `admin`, `support`, `finance` and `compliance` are reserved slugs, with a `LayoutSuite` case (`Values.rules` reads `slugProblems`), f5aaf9a4
- `tools/modules/src/main/scala/io/eitheror/casino/modules/Realm.scala:246` (`BrandRoleNames`)
  - issue (non-blocking): the carry-over covers only the four default roles and the three named ones.
  - FR-001 names *every* bag role per brand, and FR-013 and SC-005 say that after apply no operator
    holds an unbranded role a brand's bags hold, and nobody loses an action.
  - 030's H6 gave brand-made roles (`vip_host`) bare. On an installation that ran 030, those holders
    keep `vip_host`, which now opens nothing, and nothing carries it over.
  - Suggestion: either carry over the brand's bag names too (the tool can read players' `/roles`, or
    take them from the brand's values), or narrow FR-013/SC-005 to the default and named roles. If you
    narrow them, record that 030 installations must re-give brand-made roles.
  - Resolved: narrowed. 030 is not merged and no installation gave a brand-made role bare, so there is nothing to carry; FR-013 and SC-005 name the roles carried and say a brand gives its own again (spec.md)
- `services/backoffice/src/web/areas/team/assign.tsx:96`
  - suggestion (if-minor): the ticks still start from `listed`, which is merchant's payments role
    (`OperatorsEndpoint.scala:53`, `roles: Vector(s.role)`), so for brand roles nothing is ticked. The
    screen now reads the brand's last giving (`giving.data.roles`) and shows "Asked: …", but a giving
    sent from that screen still takes away every unticked role.
  - Seed `chosen` from `giving.data.roles` when it arrives and the user has not changed the ticks, so
    the confirmation's "loses" line matches what will happen.
  - Resolved: the ticks start from the brand's last giving, else the roles listed, until the person changes them, and reset when the operator changes; the "loses" line reads the same, 9d47c4b4

## Nits

- `services/players/src/main/scala/io/eitheror/casino/players/roles/RoleRules.scala:22` and
  `tools/modules/src/main/scala/io/eitheror/casino/modules/Realm.scala:242`
  - nitpick (non-blocking): in both files a new definition was inserted between an existing doc
    comment and its member. `nameRefusal`'s "Why `name` may not be…" now sits above `Given`, and
    `ensure`'s "Creates what is missing…" now sits above `BrandRoleNames`. Move each comment back onto
    its member.
  - Resolved: both comments moved back onto their members, 9d47c4b4
- `contract/src/main/scala/io/eitheror/casino/contract/access/Routes.scala:200`
  - nitpick (non-blocking): `affiliateManager`'s doc still says the managed brands are `[[brandsOf]]`.
    They are now `[[managedBrands]]`.
  - Resolved: the doc names `[[managedBrands]]`, f5aaf9a4
- `services/backoffice/src/web/areas/team/assign.tsx:98`
  - nitpick (non-blocking): `who_` duplicates `who` a few lines below, because the hook must run before
    the early returns. Compute `who` once above the hooks and drop the second binding.
  - Resolved: one `who` above the hooks, 9d47c4b4
- `specs/032-per-brand-roles/data-model.md:32` and `plan.md:28`
  - nitpick (non-blocking): both say merchant gains three events. The entity has four: `BrandRolesRejected`
    is missing from the table, and the table should give its fields and the fact that it changes no
    state.
  - Resolved: data-model.md lists `BrandRolesRejected` and its fields, changing no state; plan.md says four events

## Questions

- `services/merchant/src/main/scala/io/eitheror/casino/merchant/operators/BrandRolesApplier.scala:37`
  - question: `RealmError.of` treats every 5xx as `Unreachable`, and the applier throws to be delivered
    again, holding back its slice's later changes. That is right while the whole realm is down.
  - But Keycloak also answers 500 for one user's bad data. In that case one operator's giving would
    retry forever and stall every other operator in the same slice (4 slices by default). `NotConfigured`
    behaves the same way.
  - Is that accepted? If not, after N consecutive failures for one key (the `failures` map already
    counts them), end the giving as failed with "the realm kept failing", and log the operator and the
    brand.
  - Resolved: a 500 is now `Refused` and ends the giving as failed; only 429, 502, 503 and 504 (and a realm not answering at all, or no credentials) are tried again, so one user's bad data no longer stalls a slice, 3e10cf05
- `services/merchant/src/main/scala/io/eitheror/casino/merchant/api/OperatorsEndpoint.scala:222`
  - question: every H6 refusal persists `BrandRolesRejected` on `operator(id)` without a `live` check.
    That includes a 403 for another project's `players`, and ids the realm never issued. So each
    refused request writes an event into an entity that may not exist.
  - Is an audit record for an unknown operator wanted? If it is, a size bound on `roles` in the record
    would keep a hostile caller from growing journals. If not, check `exists` first and log instead.
  - Resolved: a refusal is recorded only on an operator that exists, its roles bounded to 64; others are logged, 3e10cf05
- `services/merchant/src/test/scala/io/eitheror/casino/merchant/features/OperatorsFeatures.scala:206`
  - question: SC-003 says "in 100 runs … all 100 end as asked". The step does one run of 98 concurrent
    givings, then 2 concurrent last givings after the first batch completes. Is that the intended
    reading of SC-003? If yes, reword SC-003. If no, loop the scenario. Entity versioning makes the
    outcome deterministic, so a reworded criterion is likely enough.
  - Answered: the intended reading is concurrent givings ending as last asked; SC-003 is reworded to "of 100 givings made at the same moment … every one ends as its last giving asked" (spec.md)
- `specs/030-backoffice/REVIEW.md:43` and `:583`
  - question: 030 carried two items "to spec 032":
    - refusing a giving that leaves no operator holding `roles.change`;
    - the role's version on `RoleChangers`' claim.
  - 032's spec puts RoleChangers out of scope, and neither item is in its tasks. Where are they tracked
    now? Answer in 030's resolutions, or in an issue, so they are not lost between the two specs.
  - Answered: neither belongs to per-brand names; 032's Assumptions now name both as open, for the spec that next changes RoleChangers (spec.md)

## Praise

- `contract/src/test/resources/brand-roles.cases.json`
  - praise: one table, run by both `BrandRolesSuite` and the backoffice's `roles.test.ts`. That is the
    right guard for the two readers of the same rule. The "split at the last separator" and "slug that
    contains another brand" rows are exactly the boundaries worth pinning.
- `services/merchant/src/main/scala/io/eitheror/casino/merchant/operators/Operator.scala:110`
  - praise: the giving is versioned per brand, and a stale `Applied`/`Failed` changes nothing. With
    the applier re-reading state, ankka's batch redelivery of earlier changes is harmless. 030's
    `BrandRolesSet` is replayed as version 0 in force, so old journals still read.
- `services/merchant/src/main/scala/io/eitheror/casino/merchant/operators/KeycloakAdmin.scala:300`
  - praise: splitting `Failed` into `Unreachable` and `Refused(status, reason)`, with one classifier
    (`RealmError.of`) and a table test, makes the retry policy something a reader can check.

## Out of scope

- note: `Managers.revoke` removes `brand_<slug>` (`services/affiliate-programme/.../Managers.scala`).
  That takes the user out of the brand altogether, even if the brand had also given them roles. The
  behaviour predates 032 (spec 013). File an issue to decide whether revoking a manager should leave
  `brand_<slug>` to merchant's operator administration, as the spec's Assumptions say.

## Checks applied

- **General checklist:** applied Correctness; Design and scope; Error handling; Concurrency and state;
  Security (authorisation per brand, cross-project H6 callers, URL encoding of role names); Data and
  migrations (new events and state field under jsoniter with transient defaults, the `BrandRolesSet`
  replay); Tests; Observability; API and compatibility (H6 200→202, the new H6r and P13r,
  `permissionsOf` signatures in the published contract); Documentation; Commit hygiene (Conventional
  Commits with scopes, consistently). Skipped: Performance beyond the applier's per-giving calls (scale
  is tens of operators), and Dependencies (no new dependency).
- **Language skills:** the bundled `pr-review-scala` (concurrency and actors, serialization, types and
  totality, error handling, tests, API design, idioms) and `pr-review-typescript` (types, async, tests);
  the repository has no override of either. Go: one changed line in `tests/original/programme-driver/scenarios.go`,
  a `NotDriven` string, so the Go skill was not loaded.
- **Project rules:** `CLAUDE.md` (root and the eitheror brain it imports), the constitution's
  principles as the plan cites them, and 030's `REVIEW.md` for the findings declined into this spec. It
  is a report, not a checklist, and has no `## pr-review` section.
- **Tooling:** merchant compiles with `-Wunused:all`, so unused code was not reviewed by hand.
- **Tests run:** the backoffice unit suite (`vitest run --dir tests/unit`): 51 files, 737 tests passed,
  including `roles.test.ts` over the shared table. Not run: the contract's and the services' sbt suites
  (they need sbt outside the sandbox and the published contract, which is more than a minute), and the
  Keycloak container suite `RealmRoleNameIt` and the merchant features over `FakeKeycloak` (they need
  Docker or a running service).
