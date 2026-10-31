# Quickstart: proving kyc

In build order. Each step names what it proves; the commands are the `just` recipes the plan adds.

## 1. The features and the glossary

`just features`: 0 findings. The seven `features/kyc/*.feature` files hold the 68 scenarios the
`GherkinSuite` binds.

## 2. The contract

`just schemas` after the addition (`contracts/facts.md`: `player_kyc_pending.reason`), then
`sbt contract/publishLocal` with a unique `CASINO_VERSION` (other worktrees overwrite the snapshot). The
schema diff shows one optional field and nothing else.

## 3. The recordings (needs the original's kind stack; research R2)

On a local checkout of the original only, on a local branch, never pushed: apply
`tests/original/e2e-recording.patch` and `e2e-rg-kyc.patch` (with scenario 28's incomplete-profile test
waiting for `pending`), run `go test -tags e2e -run 'Scenario2[89]'` against the local stack, and copy the
recordings to `tests/port/kyc/`. The user stops ankka on kind for the run (memory: *Recording the
original on kind*).

## 4. Unit suites

`just kyc-test`:

- `RollupSuite`: the 61 cases of `rollup_test.go` and `handler_test.go`, the same inputs and outcomes
  (SC-001);
- `PolicySuite`: the seven `substrate_test.go` cases, every profile row of research R9, every refusal;
- `GatesSuite`: every row of data-model.md's deposit check order, and the play check;
- `KycRecordSuite`: every command, event and refusal of data-model.md's table;
- the consumer kits, and `PublishersSuite` (every event to its family; never a `Personal` value in a log,
  a fact or an audit record).

## 5. Integration, features and parity (Docker)

`just kyc-it`: the kit over Postgres, an S3-compatible container for the bucket, `FakePlayers` for the
suspend route, the in-memory broker and a settable clock.

- `DocumentsSuite`: an upload through a signed PUT, confirmed by HEAD; an oversized or mistyped object
  deleted and refused `file_rejected`; a view link issued, and its `ViewIssued` and audit record
  (SC-005).
- `GatesSuite` (integration): 1,000 asks of each gate, p99 under 50 ms, no outbound call (SC-003); a
  policy change seen by the next ask (SC-006).
- `WindowSuite`: a deadline lapses, flags, and calls `players`' suspend once, retried until it answers.
- `ErasureSuite`: every version under the player's prefix deleted; the records' non-personal fields
  unchanged (SC-004, as far as one store version goes).
- `LeakSuite`, `OperatorSuite` (roles), `GherkinSuite` over the seven features; `ParitySuite` over the
  recordings once they exist.

## 6. Locally

`just kyc-run`: the service on 9012 as brand goldvibe, Postgres from `docker compose` on 5438, and the
bucket from the same compose file. 9012 is where `players`' local run already looks for `kyc` (its
`FakesMain` binds a fake there; run `players` without it). The deposit and play checks answered by `curl`;
an upload offered, put and confirmed with a token from goldvibe's realm on the kind installation's
casino Keycloak, which `kyc-run` verifies against (010 quickstart §6).

## 7. An installation (kind)

The image built and loaded as 008's §7; `just modules apply goldvibe --installation dev-kind --modules
core --allow-unbuilt`. `ankka services get kyc` shows the bucket. Then:

- a deposit by a player not verified, under the UKGC profile, is refused through `players`;
- a launch is refused through `game-provider`;
- a document uploaded through the player site's proxy appears in the operator's queue.

Encryption at rest is Garage's volume on kind (research R17); the soft-delete half of SC-004 holds on
Google Cloud Storage only.
