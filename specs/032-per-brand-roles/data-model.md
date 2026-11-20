# Data model: Per-brand realm roles

## Brand role (a realm role's name)

| Part | Rule |
|---|---|
| role | `[a-z0-9_]{1,40}` (`OperatorPermissions.validRoleName`); never contains `@` |
| slug | the brand's slug, as `brand_<slug>` names it |
| name in the realm | `<role>@<slug>`; built and read only by `contract.access.BrandRoles` |

Not brand roles, and never given a brand: `brand_<slug>`, `platform_admin`, `audit_compliance`,
`audit_compliance_<slug>`, `reidentify_<slug>`, the payments roles, `affiliate`.

Brand roles a brand may give: its bag roles (the four defaults and those it made), `api_keys`,
`markets`. `affiliate_manager@<slug>` is given by the programme only (research R8).

## Giving (merchant's `Operator` entity, per brand)

State, beside today's `brandRoles` (which becomes the last applied set, read by nothing new):

```text
givings: Map[brand, Giving]
Giving(roles: Set[String], version: Long, state: Pending | InForce | Failed(reason), at: Instant)
```

Events:

| Event | Fields | Applied as |
|---|---|---|
| `BrandRolesAsked` | brand, roles, version, at | `givings(brand) = Giving(roles, version, Pending, at)`; `version` is the brand's last + 1 |
| `BrandRolesApplied` | brand, version | when `version` is the brand's current: state `InForce`, `brandRoles(brand) = roles`; otherwise nothing |
| `BrandRolesFailed` | brand, version, reason | when `version` is current: state `Failed(reason)`; otherwise nothing |
| `BrandRolesRejected` | brand, caller, roles, code, at | nothing: the audit record of a refused giving (FR-014), on an operator that exists |

`SetBrandRoles` (030's event) stays readable: replayed, it sets `brandRoles` and a giving `InForce`
at version 0. Codecs: new manifests in `MerchantCodecs`; no stored type changes shape.

Transitions, per brand:

```text
(none) ──asked──▶ Pending ──applied──▶ InForce
                     │  └──failed───▶ Failed
any state ──asked (version+1)──▶ Pending   (a later giving replaces, FR-008)
```

Validation at H6, before anything is recorded: every role is a brand role this brand may give;
none carries `@`; `brand_<slug>` is this brand's; no payments role, no `platform_admin`.

## Role assignment (players' `RoleAssignment`, unchanged in shape)

Keeps what the brand gave, by names without the brand, as in 030. Its GET answer gains the giving's
`state`, `reason`, `version` and `held` (the realm roles named for the brand), read from merchant's H6r.

## The applier's realm view

What it reads and writes per change: the user's realm roles (`realmRoles(id)`), filtered to
`*@<slug>` plus `brand_<slug>`. Target = `asked.map(nameIn(slug, _)) + brand_<slug>`. Adds
`target -- held`, removes `heldNamedForBrand -- target`. `brand_<slug>` is added, never removed by
a giving (an empty giving keeps the operator in the brand, as 030).
