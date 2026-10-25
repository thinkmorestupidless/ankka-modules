# Contract: the realm (`casino-<slug>`) and `players`

What passes between the brand's Keycloak realm and this service (research R4, R13). The realm is
the authority on the password, the address's verified state and the tokens; `players` on everything
else. The realm talks to `players` and to no other service (spec 001 FR-022).

## `players` → the realm (the administrative credential `players-realm-admin`, FR-007)

| Use | Keycloak call |
|---|---|
| register | `POST /admin/realms/casino-<slug>/users` with `username` = `email`, `enabled`, `emailVerified: false`, `credentials: [password]`, attribute `eitheror_twin_id` = the player id; `409` → `email_already_exists`; `400` on the password policy → `password_too_weak`; `404` realm → `brand_not_found` |
| undo a registration | `DELETE /admin/realms/casino-<slug>/users/{userId}` |
| verify an address, change the sign-in address | `PUT /admin/realms/casino-<slug>/users/{userId}` (`emailVerified`, `email`, `username`) |
| sign-in, refresh, verify a password | `POST /realms/casino-<slug>/protocol/openid-connect/token`, client `casino-frontend`, `grant_type=password` or `refresh_token` (no credential) |
| sign-out, suspend, exclude, close | `DELETE /admin/realms/casino-<slug>/sessions/{realmSessionId}`; close and exclude also `enabled: false` |
| reopen, reinstate | `enabled: true` (reinstate never disabled; exclusion and closure did) |
| live sessions (the reconciler) | `GET /admin/realms/casino-<slug>/clients/{casino-frontend id}/user-sessions` every 60 s |
| password reset (player and operator) | `PUT /admin/realms/casino-<slug>/users/{userId}/execute-actions-email` with `UPDATE_PASSWORD` |
| erasure | `DELETE /admin/realms/casino-<slug>/users/{userId}` |

The token `players` reads at sign-in carries `eitheror_twin_id` (the player id, the original's
claim name) and `session_state` (the realm session id); a token without either refuses
`missing_token`.

## The realm → `players` (`RealmEndpoint`, `/internal/realm/<slug>`)

| Caller | Route | Header | Body |
|---|---|---|---|
| `eitheror-audit-listener` (as today) | `POST /webhook` | `X-Webhook-Secret: <players-webhook-secret>` | `{type: LOGIN \| LOGOUT \| SESSION_EXPIRED, realmId, sessionId, userId, timestamp}`; `LOGOUT` also synthesised from admin `USER_SESSION DELETE` with `userId: "-"` |
| `eitheror-rg-status-authenticator` (changed read) | `POST /may-sign-in` | `X-Realm-Secret: <players-realm-secret>` | `{email}` → `{allowed, reason?, until?}`; a non-200 or no answer within `RG_STATUS_TIMEOUT_MS` is a refusal (fail closed, as today) |
| `eitheror-gamstop-authenticator` (new report) | `POST /gamstop-refusal` | `X-Realm-Secret` | `{email, result: excluded \| api_error \| timeout}` after it refused, best effort |

## The plugin change (in the original's repository and the installation's image)

`bundle/keycloak-plugins/eitheror-rg-status-authenticator/RgStatusAuthenticator.java:35-128` reads
`GET {SUBSTRATE_INTERNAL_URL}/twins/casino-{brand}/{derived id}` today, with no secret. It changes to
the route above: env `PLAYERS_URL` (the brand's `players` inside the cluster,
`players.ankka-<slug>.svc.cluster.local`) and `PLAYERS_REALM_SECRET`; the `brandId` is still the realm
name with `casino-` stripped; the fail-closed behaviour and `RG_STATUS_TIMEOUT_MS` are unchanged.
`GamstopAuthenticator.java` gains the report. `EitherOrAuditListener.java` is unchanged but for its
URL (`CASINO_SERVICE_WEBHOOK_URL` → the brand's `players`). The installation's Keycloak
(`installation/base/keycloak/`) carries the rebuilt jars; one Keycloak serves every brand realm, so
the plugins resolve the brand's `players` from the realm name, and the two secrets are per realm
(realm attributes the brands tool writes from the brand's values).

## Realm template additions (`brands/_realm/casino-template.json`)

- The `eitheror_twin_id` user attribute mapped into the access token (the original's claim; the
  template is checked for it).
- The realm attributes `players_url`, `players_realm_secret`, `players_webhook_secret`, read by the
  plugins.
- The audit listener on, pointed at the brand's `players`.
