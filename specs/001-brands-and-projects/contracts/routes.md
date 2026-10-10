# Contract: routes, addresses and the six classes

## Addresses

- A service is reached at `https://<service>-<slug>.<base>` once exposed (`ankka services
  expose`); inside the cluster at `<service>.ankka-<slug>.svc.cluster.local`.
- Every route keeps the original's `{brand}` segment (`/v1/casino/brands/{brand}/...`,
  `/v1/casino/players/{brand}/...`, `/v1/casino/backoffice/{brand}/...`). A brand project
  answers a request whose `{brand}` is not its slug with `404` and body
  `{"error":"wrong_brand","brand":"<slug>"}` (FR-005).
- Exposed: the services a frontend or a provider calls (`players`, `wallet` for the cashier's
  return, `game-provider` for the provider's calls, `payouts`, `kyc`, `responsible-gambling`,
  `rewards`, `engagement` for push, `reporting`, `partner-api`, `support-agent`). Never exposed:
  routes of class `service`; the tool refuses `expose: true` on a service that serves only those.

## Classes and recipes (`contract.access`)

| Class | Recipe | Admits | Refuses as |
|---|---|---|---|
| public | `Routes.public` = `Acl.AllowAll`; the handler verifies the provider's signature or the merchant key and refuses otherwise | the internet | `401 signature_invalid` / `401 merchant_key_invalid` |
| player | `Routes.player(slug)` = `Oidc.authenticate()` on issuer `player`; principal's issuer must be `.../realms/casino-<slug>` | a player of this brand | `401 unauthenticated`; `403 wrong_brand` naming the slug when the realm is another brand's |
| operator | `Routes.operator(slug)` = issuer `staff`; realm role `brand_<slug>` or `platform_admin`; roles only, never a client-stamped claim (decision 0045) | an operator whose roles name the brand | `403 brand_not_in_roles` |
| affiliate | `Routes.affiliate` = issuer `staff`, realm role `affiliate` | an affiliate (programme routes only) | `403 not_affiliate` |
| partner | `Routes.partner(slug)` = issuer `staff`, realm role `partner_<slug>` (spec 016 FR-015) | a partner's client for this brand | `403 not_partner` |
| service | `Routes.service(route)` = `Acl.allowCallers(Callers.anyInProject, <the route's grantees>)` | a service of this project; for a granted route, the shared services `contracts/grants.md` names for it | `403` |

- `ANKKA_AUTH_ISSUERS=player,staff` on every brand service; `staff` alone on a shared service.
  Issuer `player` = `<issuerBase>/realms/casino-<slug>`, `staff` = `<issuerBase>/realms/eitheror`;
  JWKS at `<issuer>/protocol/openid-connect/certs`; audience per class as the realm's clients name it.
- Service-to-service calls carry no token. The caller is its certificate's `ankka://<project>/<service>`.
- ankka 0.11 has no `Callers.granted` (spec 040 is unbuilt), so a granted route names its grantees
  from the grants contract; when 040 ships, `Routes.service` adds `Callers.granted` and the list goes.
- A player token from another brand's realm is refused 401 by the verifier, which trusts only this
  brand's realm; `wrong_brand` (403) is the refusal for a verified token whose realm is not the brand's.
- A route with no class is a defect of the service's spec (SC-003); `contract.access` offers no
  other way to declare an ACL.
