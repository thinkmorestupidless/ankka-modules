# backoffice-state

What a brand's backoffice keeps beyond one instance (spec 030, data-model.md). Nothing about a
player. It admits only the brand's `backoffice` service.

| Prefix | What it keeps |
|---|---|
| `/sessions` | each operator's sign-in (`Session`, event sourced): the code exchanged at the realm, one refresh at a time, sign-out revoked at the realm, expiry |
| `/preferences` | each operator's preferences (`Preferences`, key value): theme, worklist kinds shown, online notices, the references of the jobs they started |
| `/subjects` | an operator's erasure: their sessions ended and revoked, their preferences deleted; the erasure handler runs the same for a subject under `operator/` |

```bash
just service backoffice-state test   # unit suites
just backoffice-state-it             # over Postgres and a Keycloak container (Docker)
```
