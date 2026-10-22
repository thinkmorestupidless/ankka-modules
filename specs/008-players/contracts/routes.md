# Contract: routes of `players` added by other specs

Spec 008 writes the rest of this contract when it is planned. These rows are spec 021's (core and
modules, `specs/021-core-and-modules/contracts/routes.md`), which `players` serves because it is the
core's operator-facing service and the vendor's agent does not trust the `eitheror` realm.

## The operator's modules page

| Route | Class | Does | Answers |
|---|---|---|---|
| `GET /v1/casino/brands/{brand}/modules` | `operator(brand)` | calls the vendor's agent, inside the project by its certificate, at `GET /vendor/modules` and passes the answer through: modules with `status`, `version`, `follow` (channel or pin), each service's state, `lastChange`, `dataKeptUntil`; the open requests; the names of missing keys | 200; 503 `agent unreachable` |
| `POST /v1/casino/brands/{brand}/modules/requests` | `operator(brand)` | body `{"module", "note"?}`; forwards to the agent's `POST /vendor/requests` with the operator's subject as `by` | 204; 400 unknown module; 503 |

Nothing else is served under `/modules`: no role on the installation enables, disables, pins or
moves a module (spec 021 FR-013, SC-003). The `platform_admin` role is admitted as any operator is
and gains no enable. The page's copy says "ask eitheror for this module", never "enable".
