# Quickstart: proving responsible-gambling

In build order. Each step names what it proves; the commands are the `just` recipes the plan adds.

## 1. The features and the glossary

`just features`: 0 findings. The seven `features/responsible-gambling/*.feature` files hold the 59
scenarios the `GherkinSuite` binds.

## 2. The contract

`just schemas` after the two additions (`contracts/facts.md`), then `sbt contract/publishLocal`. The schema
diff shows two optional fields and nothing else.

## 3. The recordings (needs the original's kind stack; research R1)

On a local checkout of the original only, on a local branch, never pushed: apply
`tests/original/e2e-recording.patch` and `e2e-rg-kyc.patch`, run `go test -tags e2e -run 'Scenario2[67]'`
against the local stack, and copy the recordings to `tests/port/responsible-gambling/`. The user stops
ankka on kind for the run (memory: *Recording the original on kind*).

## 4. Unit suites

`just rg-test`: `LimitsSuite`, `ChecksSuite` (every row of data-model.md's order), `WindowsSuite`,
`ProfileEntitySuite` (every command and refusal), the key value kits, `PublishersSuite`.

## 5. Integration, features and parity (Docker)

`just rg-it`: the kit over Postgres with `FakePlayers`, the in-memory broker and a settable clock.

- `TimersSuite`: a raise applies 24 h later, within a minute of applies-at (SC-003); a break ends; a
  return reinstates 24 h after the request; the sync retries until `players` answers.
- `ChecksSuite`: 1,000 asks of each check, p99 under 100 ms (SC-004); no outbound call.
- `ExclusionSuite`, `OperatorSuite` (history and audit within 5 s, SC-006), `UsageSuite` (deposits
  exact, more than 100 in a window, SC-007), `LeakSuite` (no note or reason in a log, a fact or an audit).
- `GherkinSuite` over the seven features; `ParitySuite` over the recordings once they exist.

## 6. Locally

`just rg-run`: the service on its port as brand goldvibe, Postgres from `docker compose`. `may-log-in`,
`may-deposit`, `may-bet` and `may-play` answered by `curl` with a service identity; a limit set and staged
through the player routes with a token from the brand's local Keycloak.

## 7. An installation (kind)

The image built and loaded as 008's §7; `just modules apply goldvibe --installation dev-kind --modules
core`. Then 008's waiting check: a sign-in on kind ends in `player_logged_in` (it was refused
`rg_unavailable` until this service ran), and a break set here refuses the next sign-in `cooling_off`.
