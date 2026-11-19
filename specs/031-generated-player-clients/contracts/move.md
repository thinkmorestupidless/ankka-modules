# Contract: the move to ankka 0.18.0

The first pull request, branch `031-ankka-0-18` against `main` (tasks T004 to T031). One commit per group at least; the
repository builds at every commit (FR-005).

| Group | What | Was | Gate, as CI runs it |
|---|---|---|---|
| 1 | root `build.sbt` (`ankka.version` default): contract, provider-core, modules tool | 0.13.0 | `sbt test`; `sbt "modules/run validate --all"`; `sbt contract/schemas` leaves no diff |
| 2 | attribution, game-provider, kyc, payouts, players, public-api, responsible-gambling, rewards, wallet | 0.16.3 | each: `sbt contract/publishLocal` (and `providerCore/publishLocal` for game-provider), `sbt test`, and `-Dit=true "testOnly …"` where `ci.yml` has that step |
| 3 | audit, engagement, reporting; support-agent | 0.16.0; 0.16.2 | the same |
| 4 | exchange, markets, with `PREDICTIONS_VERSION` default 0.3.0 | 0.14.2, predictions 0.1.1 | the same; V7 |
| 5 | affiliate-programme, game-provider-simulator, ingest, merchant, provider, psp-gateway | 0.13.0 | the same; source changes of R2's first two rows |
| 6 | `modules/*/module.json`, `shared/*/manifest.json`, `local/generate.py`'s output under `local/guyanabet/services/`, the tool's suites and fixtures (`RenderSuite` `NewRuntimes` removed, `Spec001RenderSuite`, `spec-001-goldvibe/plan.json`, `manifests/predictions.json`, `AccessSuite`, `ApplyOnKindSuite`'s default), `ci.yml`'s comments, READMEs, `CLAUDE.md` | mixed | `sbt test`; `just modules validate --all`; `just modules render guyanabet` shows 0.18.0 everywhere; a search of builds and manifests finds no other ankka version (SC-001) |

## In every service of groups 2 to 5

- `val ankkaVersion = "0.18.0"` in `build.sbt`; `"runtime": "0.18.0"` in `service.json`.
- The block `ThisBuild / libraryDependencySchemes ++= … VersionScheme.Always` and its comment are
  removed (15 services). A Jackson pin that names ankka's version in its comment
  (`public-api/build.sbt`) is re-read against what 0.18.0 brings.
- No file under `src/test` changes an expectation (FR-002). A change there is one of: an ankka API
  renamed or removed, named in the commit; or a finding.
- No stored type, `Codecs.serializer` or manifest name changes (FR-003). `git diff` of the
  service's entity, view and workflow state types is empty, or the build stops.

## What must not appear

- A service on 0.18.0 against a contract published on another version.
- A manifest naming 0.18.0 for a service whose build is still on an earlier version, at any commit.
- A recording or a `.feature` file in the diff.

## The local brand (phase C)

After group 6: `brew upgrade thinkmorestupidless/tap/ankka` (0.18.0), `just local-images`, `just
local-project`, `ankka local up` in `local/guyanabet/`, `just local-onboard`, `just
player-site-it` (T030). Expected: the contract tests pass 24 of 24 with the hand-written clients, as on
`main`. `local/README.md`'s note on 0.13 services showing running and not ready is removed if it
is no longer true.
