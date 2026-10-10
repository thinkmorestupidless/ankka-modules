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
