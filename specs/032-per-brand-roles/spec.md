# Feature Specification: Per-brand realm roles

**Feature Branch**: `032-per-brand-roles`

**Created**: 2026-11-20

**Status**: Draft

**Input**: User description: "Spec 032: per-brand realm roles. Role names in a brand's operator realm are shared across brands, so a role one brand gives an operator holds in every brand that operator belongs to: brand A's administrator can make an operator who also holds `brand_b` a `brand_admin` or `brand_finance` of brand B without B's consent (spec 030's review, blocking finding). Trevor's decision of 2026-11-17: per-brand realm roles, role names in the realm that say which brand they hold in (for example `brand_admin@<slug>`), read by `Routes.operatorMay` for its own brand only. Scope: the contract, every service's role reading, merchant's H6, players' P13 and the backoffice's `/api/me`, and the Spinvibe migration's role mapping. It also takes the review findings 030 declined: a role given at the cutover can never be taken away, a failure part-way leaves realm roles no brand owns, and two brands' concurrent givings read then act on each other's. Not in scope: the permission sets themselves and RoleChangers. Cut from `030-backoffice`, merged into it before it merges."

## Why

Every brand's operators sign in at one realm, `eitheror`. An operator of two brands holds
`brand_goldvibe` and `brand_spinvibe` there, and every other role they hold is a single realm role
with no brand in it. Spec 030 made each role a bag of permissions that each brand keeps. A bag is
the brand's own, but the role an operator holds is the realm's. So when goldvibe gives an operator
`brand_finance`, that operator is `brand_finance` in spinvibe too, carrying whatever spinvibe's
`brand_finance` carries. Spinvibe did not agree to that.

The same holds for every role a brand makes. Goldvibe's `vip_host` and spinvibe's `vip_host` are
one realm role. It also holds for the roles routes still check by name: `api_keys` (spec 016),
`markets` (spec 023) and `affiliate_manager` (spec 013).

This contradicts constitution III: a brand is a project, and nothing is shared by convention. The
decision (Trevor, 2026-11-17) is that a role an operator holds in a brand is a realm role named for
that brand, and each brand reads only the roles named for it.

## Clarifications

### Session 2026-11-20

- Q: Do the roles routes check by name (`api_keys`, `markets`, `affiliate_manager`) become per
  brand too? → A: All three, so the same hole is closed everywhere; specs 016, 023 and 013 are
  amended (FR-012).
- Q: What happens to today's roles with no brand in their name on installations? → A: Carried over
  as the role named for every brand the operator holds when each brand is applied, then removed
  (FR-013).
- Q: Does the term "giving" stand? → A: Yes: a brand's choice of the roles one operator holds in
  it, refusing "assignment"; it is not a grant (glossary).
- Q: How does a giving end when the realm refuses a change outright, rather than being
  unreachable? → A: An unreachable realm is retried with backoff for as long as it takes; an
  outright refusal ends the giving as failed, with the realm's reason, shown to the brand, audited
  and not tried again. The brand can give again (FR-007, FR-009).
- Q: Who gives `api_keys` and `markets` per brand? → A: The brand, in a giving, as it gives its
  other roles: only a giver holding `roles.give`, and both stay out of the brand's bags and the
  role matrix (FR-012).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A role one brand gives holds in that brand only (Priority: P1)

An administrator of goldvibe gives an operator the finance role. The same operator also works in
spinvibe as support. In spinvibe they can do what spinvibe's support role allows and nothing more.
In goldvibe they can do what goldvibe's finance role allows.

**Why this priority**: This is the security hole spec 030's review found blocking. Until it is
closed, the backoffice cannot merge.

**Independent Test**: Give an operator of two brands a role in one. Every operator route of the
other brand still refuses what only that role would allow.

**Acceptance Scenarios**:

- added `features/backoffice/access.feature`: a role one brand gives an operator admits nothing in another brand the operator works in
- added `features/backoffice/access.feature`: two brands' roles of the same name each carry their own brand's permissions only
- added `features/backoffice/access.feature`: a role held in a brand admits nothing when the operator's roles do not name the brand
- added `features/predictions/lifecycle.feature`: the markets permission given in one brand opens no other brand's markets
- added `features/programme/managers.feature`: the manager role given for one brand manages no other brand the operator's roles name
- changed `features/public-api/keys.feature`: an operator without the access keys permission, or whose roles do not name the brand, cannot see or change the brand's keys

---

### User Story 2 - A brand's giving ends as asked, whatever fails on the way (Priority: P1)

A brand administrator gives an operator a set of roles. However the setting is interrupted, the
operator ends holding exactly that set in the brand, and no role is left that no brand owns. The
roles another brand gave the same operator are untouched, even when the two brands give at the
same moment.

**Why this priority**: These are the review findings 030 declined into 032. Without them, a giving
can strand roles in the realm that no brand can remove.

**Independent Test**: Interrupt a giving after its first realm change and let it resume. Have two
brands give the same operator at once. Both end with exactly the sets asked.

**Acceptance Scenarios**:

- added `features/payments/operators.feature`: a giving ends as asked when the realm stops answering part-way through it
- added `features/payments/operators.feature`: two brands giving one operator roles at the same moment each end as their own brand asked
- added `features/payments/operators.feature`: a brand's later giving replaces its earlier one, even before the earlier is in force
- added `features/backoffice/roles.feature`: a giving not yet in force is read as asked and as not yet in force
- added `features/brands/modules.feature`: a role held with no brand in its name is carried over to every brand the operator works in, then removed
- added `features/backoffice/roles.feature`: a giving the realm refuses is shown to the brand as failed, with the realm's reason
- added `features/payments/operators.feature`: a giving the realm refuses ends as failed and is not tried again
- added `features/backoffice/roles.feature`: a brand gives the access keys and markets roles as it gives its other roles, in that brand only

---

### User Story 3 - A role given at the cutover can be taken away (Priority: P2)

When Spinvibe's operators move to the backoffice, each is given the brand's default roles by the
mapping of spec 030. Afterwards, the brand's administrator can change or take away every one of
those roles, as for any role the brand gave itself.

**Why this priority**: Spec 030 FR-008c says a brand gives and takes away roles. A role the cutover
gave, which no brand recorded giving, could never be taken away.

**Independent Test**: Run the cutover's mapping for one operator, then give that operator a
smaller set from the backoffice. The roles left out are gone.

**Acceptance Scenarios**:

- changed `features/migration/operator-roles.feature`: an operator of the original is given the role their original role maps to
- added `features/migration/operator-roles.feature`: a role the cutover gave can be taken away by its brand

---

### User Story 4 - An operator sees their roles in this brand (Priority: P3)

An operator signed in to a brand's backoffice sees the roles they hold in that brand, by the
brand's names, and none of another brand's.

**Why this priority**: What an operator is shown must match what they are admitted to, but showing
another brand's roles exposes nothing that admits them.

**Independent Test**: Sign in to one brand's backoffice as an operator of two brands. The roles
shown are only those named for this brand.

**Acceptance Scenarios**:

- added `features/backoffice/access.feature`: an operator is shown only the roles they hold in the brand of the backoffice

### Edge Cases

- An operator holds a role named for a brand but not `brand_<slug>`: it admits nothing,
  as today an operator without `brand_<slug>` is refused.
- A brand deletes a role it made: operators who held it hold a role whose bag no longer exists, and
  it carries nothing.
- A brand asks to give a role whose name already carries a brand, or another brand's `brand_<slug>`: it
  is refused, and the realm is not touched.
- A platform administrator holds every permission in every brand, as today, with no role named for
  any brand.
- The installation's audit roles (`audit_compliance`, `audit_compliance_<slug>`,
  `reidentify_<slug>`) and payments administration's roles are unchanged.
- A brand's slug that is also the name of a default role (for example `admin`) is never read as a
  brand.
- The realm refuses a giving outright (it no longer knows the operator): the giving ends as failed,
  with the realm's reason, and is not tried again.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Every role a brand's bags hold (the four default roles and every role the brand made)
  MUST be held in the realm as a role named for that brand: the role's name, then the brand's slug
  (`brand_finance@goldvibe`).
- **FR-002**: An operator route of a brand MUST open a bag only for the roles named for that brand.
  A role named for another brand, or one with no brand in its name, MUST carry nothing there. This
  holds in every service of the brand, in `players`' own checks, and in the backoffice's answers to
  `/api/me` and `/api/permissions`.
- **FR-003**: The role saying an operator works in a brand MUST stay `brand_<slug>`, and is checked before
  any role, as today. A brand MUST be read only from that role, never from the name of a role
  (`brand_admin` is not the brand `admin`).
- **FR-004**: `platform_admin`, the installation's audit roles and payments administration's roles
  MUST stay as they are today: one realm role each, with no brand in its name.
- **FR-005**: A brand MUST give, show and change roles by their names without the brand
  (`brand_finance`, `vip_host`). The brand part MUST be added and read by the platform, never typed
  by an operator. A role name a brand makes or gives MUST NOT contain the brand separator.
- **FR-006**: A brand's giving MUST change only the roles named for that brand and the brand's own
  role (`brand_<slug>`): never a role named for another brand, and never a role with no brand in its name.
- **FR-007**: A giving MUST be recorded as the set asked before the realm is changed. The realm MUST
  then be brought to the last set recorded for that brand and operator. While the realm cannot be
  reached, this MUST be retried, with backoff, until it holds, without anyone asking again. When the
  realm refuses a change outright (for example, it no longer knows the operator), the giving MUST end
  as failed, with the realm's reason, and not be tried again; the brand may give again. A role named
  for the brand that is held but not in that set MUST be taken away, whoever gave it, the cutover
  included.
- **FR-008**: Two brands' givings to one operator MUST each end as their brand asked, whatever their
  order. A brand's second giving MUST replace its first, even when the first is not yet in the
  realm.
- **FR-009**: The brand MUST be able to read, for each operator, the set its last giving asked and
  whether it is in force in the realm yet, or failed and why.
- **FR-010**: When a brand is applied, the realm MUST hold that brand's default roles named for it,
  beside `brand_<slug>`, as that role and the brand's audit roles are made today. A role the brand
  made MUST be made in the realm, named for the brand, when it is first given.
- **FR-011**: The cutover (spec 019, spec 030 FR-008a) MUST give each mapped default role named for
  every brand the operator's original scope reached, so each of those brands can change or take it
  away (FR-007).
- **FR-012**: The roles that routes check by name MUST be named per brand too: `api_keys@<slug>`
  (spec 016, a brand's access keys), `markets@<slug>` (spec 023, the predictions desk's operator
  routes) and `affiliate_manager@<slug>` (spec 013). The shared programme MUST read which brands an
  operator manages from `affiliate_manager@<slug>` and `brand_admin@<slug>`, each for its own brand
  only. A brand MUST give `api_keys` and `markets` in a giving, as it gives its other roles, and
  only a giver holding `roles.give` may. Neither is in the brand's bags or the role matrix, and
  neither carries a permission of a bag.
- **FR-013**: When a brand is applied, an operator of that brand who holds a role with no brand in
  its name that this spec names per brand MUST be given that role named for the brand. Once every
  brand the operator holds has been carried over, the role with no brand in its name MUST be
  removed. Carrying over a second time changes nothing. No operator loses an action they had, and
  each brand can then take the carried role away (FR-007).
- **FR-014**: A giving, a change in the realm and every refusal MUST be an audit record, as spec 030
  requires of a giving today.

### Changes from the original

- The original has one brand per deployment, so a role always meant one brand. Naming roles for
  their brand is new. It is required because one realm now serves every brand of an installation.
- Spec 030 is amended: `contracts/roles.md` (role names, *How a service opens a bag*, *Rules the
  owner holds*, the cutover mapping), H6 (merchant sets one brand's roles, from a recorded set),
  P13 (`role-assignments` reads and gives roles named for the brand), and `/api/me`.
- Spec 019's cutover gives roles named for each brand (FR-011).
- Specs 016, 023 and 013 are amended: their named roles are held per brand (FR-012).

### Key Entities

- **Brand role**: a role as one brand holds it, named for the brand in the realm. What it carries
  is that brand's bag of the same name.
- **The brand's own role**: `brand_<slug>`, the realm role saying an operator works in a brand at all.
- **Giving**: for one brand and one operator, the set of roles asked, when it was asked, and
  whether the realm holds it yet. The last one recorded is the one the realm is brought to.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For an operator of two brands given every default role in one brand and none in the
  other, every operator route of the other brand that needs a permission refuses them: 0 routes
  admit them.
- **SC-002**: A giving holds in the realm within 30 seconds of being recorded when the realm
  answers. When the realm stops answering part-way, the giving holds within 30 seconds of the realm
  answering again, with nobody asking again.
- **SC-003**: In 100 runs of two brands giving one operator roles at the same moment, all 100 end
  with each brand's roles exactly as asked.
- **SC-004**: Every role the cutover gives can be taken away by its brand's next giving.
- **SC-005**: After a brand is applied, no operator holds a role with no brand in its name that a
  brand's bags hold.

## Assumptions

- No real-money brand runs on these modules yet, so the realms whose role names change are
  development installations. The Spinvibe cutover has not run.
- The brand separator is `@` (Trevor's example). The plan confirms the realm accepts it in a role
  name and in a token, and picks another separator if it does not.
- An operator's token carries their realm roles as today. A giving is in force for an operator from
  their next token, as spec 030's giving is today.
- The permissions, the role matrix's default bags, and RoleChangers are unchanged.
- Taking an operator out of a brand altogether (their `brand_<slug>`) stays as merchant's operator
  administration does it today, outside the brand's giving.
