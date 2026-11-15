# Quickstart: proving the backoffice

In build order. Each step names what must be true when it passes. Commands run from the repository
root; `--context kind-…` on every `kubectl`, never the default context.

## 0. Prerequisites

Node 24, the `ankka` CLI 0.16.3, Docker, `uv`, a local installation (`dev-kind`) with one brand
applied (`just modules apply goldvibe --installation dev-kind`) and the shared projects.

## 1. The assumption everything rests on (V1)

```bash
just backoffice-spike
```

A web-hosted probe in a scratch project calls `players.goldvibe` at the calling address with an
operator's token, and with none. **Passes when** the first answers `200` and the second `401`, with
no grant made. If the first is refused by the runtime, stop: it is an ankka request.

## 2. The contract and the matrix

```bash
just test                     # contract: OperatorPermissions, the published files
just schemas && git diff --exit-code contract/schemas
```

**Passes when** `operator-permissions.v1.json` and `currencies.v1.json` are written and unchanged by a
second run.

## 3. Each amended service

```bash
just service <name> test      # for each service in contracts/amendments.md
```

**Passes when** each service's own suites pass, including its bound role scenarios: a support
operator is refused every write but notes and messages; a payments role is refused a casino action.

## 4. `backoffice-state`

```bash
just service backoffice-state test
just backoffice-state-it      # Postgres and a Keycloak container
```

**Passes when** a code is exchanged, two concurrent `token` calls near expiry make one refresh,
sign-out revokes at the realm, and a preference naming a player is refused.

## 5. The page and its server

```bash
cd services/backoffice && npm ci && npm run typecheck && npm test
npm run features              # playwright-bdd over features/backoffice against the doubles
npm run a11y                  # axe on every screen, dark and light
```

**Passes when** every scenario in `features/backoffice/` passes, no screen has a serious axe
finding (SC-008), and the copied contract files equal the contract's.

## 6. Locally, against real services

```bash
just modules apply shared/backoffice/manifest.json --installation dev-kind
just backoffice-seed goldvibe # 10,000 players, 1,000 rewards, 5,000 withdrawal requests, 3 currencies
just backoffice-it
```

**Passes when**, signed in as each of the five roles and as a payments role:

| Check | Criterion |
|---|---|
| every list's total equals its source's count and its last page is reachable | SC-005 |
| every action offered is admitted when taken, and every action not offered is refused when called directly | SC-006 |
| every amount equals its record's minor units, in JPY, EUR and KWD | SC-007 |
| the three investigations (a capped withdrawal, a reward's end, a suspected bug) are answered from the pages alone | SC-004 |
| a source stopped (`ankka services pause kyc -p goldvibe`) is named in the worklist and the timeline, and the rest is shown | FR-017, FR-022 |
| an adjustment list repeated makes nothing twice | FR-028 |
| a rollout of `backoffice` while a page is open breaks nothing | FR-042 |
| the browser's requests go only to the backoffice's address, and no script can read the sign-in | FR-002, FR-003 |

## 7. With people

SC-002 and SC-003 are a moderated test with three operators on the seeded brand, at the end of
phases 1 and 2: the script and the result sheet are `specs/030-backoffice/checklists/moderated.md`.

## 8. The before

```bash
just backoffice-record-original   # the original on kind; one capture per screen per role
```

Compared with the built screens in the review at the end of each phase.
