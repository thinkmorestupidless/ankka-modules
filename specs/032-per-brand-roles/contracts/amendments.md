# Amendments

Each row changes a route or step another spec owns. Codes are spec 030's (`contracts/amendments.md`)
where they exist.

| Code | Owner | Change |
|---|---|---|
| H6 | merchant, `PUT /v1/operators/{id}/brand-roles/{brand}` | Validates (data-model.md), records `BrandRolesAsked` and answers `202 {roles, version, state: "pending"}`. It no longer touches the realm, reads `others`, or answers a realm error. Refuses `400 role_named_for_brand` for a role with `@`. Accepts `api_keys` and `markets`. |
| H6r | merchant, `GET /v1/operators/{id}/brand-roles/{brand}` (new) | `200 {roles, version, state, reason?, at}`, or `404` when the brand never gave. Admitted as H6 is: that brand's `players` under the brand's grant. |
| H6a | merchant, `BrandRolesApplier` (new consumer of `Operator`) | research R4: applies the brand's current giving; `Unreachable` fails the change (redelivered with backoff); `Refused`/`NotFound` records `BrandRolesFailed`; success records `BrandRolesApplied`. Audit records for each (FR-014). |
| — | merchant, `KeycloakAdmin` | `RealmError.Failed` split into `Unreachable` and `Refused(status, reason)` (R5); role names URL-encoded on every path. |
| P13 | players, `PUT role-assignments/{operator}` | Unchanged check (`roles.give`, and the giver must hold every permission a bag role carries; `api_keys` and `markets` need `roles.give` only). Records `RoleAssignment` after H6 answers `202`; answers `202` with the giving's state. |
| P13r | players, `GET role-assignments/{operator}` | Adds `{state, reason?, version}` from H6r. |
| — | players, `RoleRules.nameRefusal` | Stops refusing `api_keys` and `markets` as names to give; still refuses them as names to make. Refuses any name with `@`. |
| 013 | programme, `Managers` | Gives and lists `affiliate_manager@<slug>`; revoke removes it (and `brand_<slug>`, as today). `Access.ManagerAdminRoles` checks `brand_admin@<path brand>`. |
| 016 | public-api, `KeysEndpoint` | `Routes.accessKeys(slug)` checks `api_keys@<slug>`. No code change beyond the contract version. |
| 023 | markets, exchange `OperatorEndpoint` | `operatorHolding(slug, "markets", "platform_admin")` checks `markets@<slug>`. Contract version only. |
| 021 | the modules tool, `Realm.ensureBrandRole` | Makes `<default>@<slug>` for the four defaults, `api_keys@<slug>`, `markets@<slug>`, `affiliate_manager@<slug>`; then carries over (R7); reports each change in its plan output. Idempotent. |
| 019 | the cutover's role step | Gives `<mapped>@<slug>` through H6 for each brand the original's scope reached (R10). |
| 030 | backoffice server | `/api/me` and `/api/permissions` per brand (brand-roles.md); the operators area shows each giving's state and a failed giving's reason. |
