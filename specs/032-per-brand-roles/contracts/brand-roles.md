# Contract: brand roles

Amends spec 030's `contracts/roles.md`, section "How a service opens a bag".

## The name

`contract.access.BrandRoles`:

```scala
object BrandRoles:
  val Separator = '@'
  def nameIn(slug: String, role: String): String          // "brand_finance@goldvibe"
  def heldIn(slug: String, roles: Set[String]): Set[String] // the roles named for slug, without it
  def brandOf(realmRole: String): Option[(String, String)] // (role, slug), split at the last '@'
```

## Who reads it

| Reader | Before | After |
|---|---|---|
| `RoleBags(slug, …).permissionsOf(roles)` | every role's bag | the bags of `heldIn(slug, roles)` |
| `OperatorPermissions.permissionsOf` | `(roles, bags)` | `(slug, roles, bags)`; `platform_admin` still every permission |
| `Routes.operatorMay(slug, permission, bags)` | via the bag | unchanged signature; the bag is slug-aware |
| `Routes.operatorHolding(slug, roles*)` | `p.roles.contains(r)` | `p.roles.contains(nameIn(slug, r))`, `platform_admin` bare |
| `Routes.accessKeys(slug)` | `api_keys` | `api_keys@<slug>` |
| `Routes.affiliateManager` | `affiliate_manager` or `brand_admin` | `affiliate_manager@<b>` or `brand_admin@<b>` for the path's brand `b` |
| `Routes.brandsOf(p)` | every `brand_*` | `brand_<slug>` whose rest has no `@` and is not a default role's rest |
| kyc `OperatorEndpoint.holds`, reporting `Documents.held`, players `RolesRoutes` | `permissionsOf(roles, bags)` | `permissionsOf(slug, roles, bags)` |
| backoffice `roles.ts` `permissionsOf`, `/api/me` `roles` | every token role | `heldIn(brand, roles)`; `/api/me` lists those, without the brand, plus `platform_admin` when held |

## The shared case table

`contract/src/test/resources/brand-roles.cases.json`: rows of `{slug, roles, bags, permissions,
held}`. The contract's `BrandRolesSuite` and the backoffice's `roles.test.ts` both run every row.
Cases include: another brand's role; an unbranded role; `brand_admin` without a brand; `platform_admin`;
a role whose bag the brand deleted; a slug equal to a default role's rest (`admin`).
