# The brand realm

`casino-template.json` is the original's realm template, copied unchanged from
`eitheror-platform/substrate/substrate-iam/src/main/resources/keycloak-realms/casino-template.json`
(constitution I: the original is the specification). The brands tool substitutes two values when it
creates a brand's realm and changes nothing else:

| Placeholder | Becomes |
|---|---|
| `${brandId}` | the brand's slug, so the realm is `casino-<slug>` |
| `${frontendBaseUrl}` | the brand's player site, from the manifest's `brand.frontendBaseUrl` or `https://<slug>.<base>` |

The realm keeps the original's roles, its `casino-frontend` client, its password and brute-force
policy, and its `eitheror-audit-listener`, which reports sign-ins to the brand's `players` service
(spec 001 FR-022). The tool also adds the realm role `brand_<slug>` to the `eitheror` realm, which is
what an operator's access to the brand is checked on (decision 0045).

When the original changes its template, copy it again; do not edit it here.

# The operators' realm

`eitheror-operators.json` is the `eitheror` realm an installation needs before any brand: the operators'
roles, the public operator clients (`casino-backoffice`, `payments-backoffice`, `affiliate-portal`,
`eitheror-cli`) and the password policy, written here rather than copied, so it carries no credential
(the original's file holds client secrets and SMTP settings). Every client's tokens carry the audience
`eitheror-casino`, which every casino service checks on a staff token (the manifests' `staffAudience`;
decided 2026-10-10: keep the check, add the mapper). The operator clients (`casino-backoffice`,
`payments-backoffice`, `affiliate-portal`) also carry the operator's scope (`eitheror_scope_level`,
`eitheror_scope_id`, `eitheror_scope_ancestors`) with the original's attribute mappers, and the realm's
user profile is the one the original's realm-sync sets (`unmanagedAttributePolicy=ADMIN_EDIT`, the two
scope attributes declared): without it Keycloak 24 drops the attributes merchant writes on an operator,
and without the mappers every operator route refuses the token. The brands tool imports it when an installation
lacks the realm, and never overwrites one that exists. Confidential clients (partners, machine accounts)
are added by the specs that need them.

The four brand roles `brand_support`, `brand_finance`, `brand_compliance` and `brand_admin` are the
role matrix's default roles (spec 030 contracts/roles.md); the modules tool adds any of them a realm
imported earlier lacks. In the realm they are names only: what each lets an operator do is the
brand's, kept by its `players` service and changed in its backoffice, so one operator of two brands
holds the same role names in both and each brand decides what they carry. A role a brand creates is
made in the realm by `merchant` when the brand first gives it.

Each brand's backoffice signs its operators in through a confidential client of its own,
`backoffice-<slug>` (standard flow with PKCE, the audience `eitheror-casino`, redirected only to
`<backoffice address>/auth/callback`). It is not in this file: the modules tool makes it with the
brand, as it makes `brand_<slug>` (spec 030 amendment D3), with the secret the brand's
`<slug>-backoffice` secret holds; when there is none, Keycloak mints one and the brand's
`backoffice-client-secret` must be set to it.

