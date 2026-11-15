# backoffice-state

What a brand's backoffice keeps beyond one instance (spec 030, data-model.md): each operator's
sign-in (`Session`, event sourced: the code exchange, one refresh at a time, sign-out) and their
preferences (`Preferences`, key value). Nothing about a player. It admits only the brand's
`backoffice` service.

```bash
just service backoffice-state test   # unit suites
just backoffice-state-it             # over Postgres and a Keycloak container (Docker)
```
