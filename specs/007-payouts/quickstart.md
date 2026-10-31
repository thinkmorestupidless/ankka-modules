# Quickstart: proving payouts

In build order. Each step names what it proves; the commands are the `just` recipes the plan adds.

## 1. The features and the glossary

`just features`: 0 findings. The five `features/payouts/*.feature` files hold the scenarios that
`GherkinSuite` binds.

## 2. The contract and its producers

`just schemas` after the additions (`contracts/facts.md`), then `sbt contract/publishLocal` with
`CASINO_VERSION=0.2.0-007-SNAPSHOT`. Other worktrees overwrite the shared snapshot, which is why the
version is unique. The schema diff shows only optional fields and one new family.

`just service players test`: `FactsSuite` shows `player_registered` with its email, and
`player_email_changed` from a promotion and a change, each `Personal`.

The modules tool's suite passes with `brand_finance` in the operator realm and `payouts` gone from
`contract/keys.md`'s "Not yet built".

## 3. The recordings (needs the original's kind stack; research R2)

These run on a local checkout of the original only, on a local branch, never pushed:

- run `tests/original/payouts-driver` (scenario 30) and scenario 251 twice on fresh ids against the
  local stack, with psp-gateway pointed at psp-stub;
- the user stops ankka on kind for the run and supplies `TEST_OPERATOR_EMAIL` and
  `TEST_OPERATOR_PASSWORD` (memory: *Recording the original on kind*);
- the outcome route's secret is redacted by the merge;
- copy the recordings to `tests/port/payouts/`, and list in its `README.md` any gate the driver could
  not reach, with the reason.

## 4. Unit suites

`just payouts-test`:

- `GatesSuite`: each row of research R4's table, in order, and the refusal a player sees when two apply;
- `LimitsSuite`: `WithdrawalLimitChecker`'s cases per currency; the rolling seven days and the UTC
  month at their edges; the statuses that count;
- `EligibilitySuite`: `WithdrawalTerminalEligibility`'s cases; 1,000 reads with one order (SC-007);
- `IbanSuite`, `NamesSuite`: `IbanValidator`'s and `PayoutDestinationRoutesSpec`'s cases;
- `ApprovalSuite`: automated or manual for each gateway and each missing field;
- the entity kits: every row of data-model.md's command tables;
- `WithdrawalWorkflowSuite`: each step, each gateway answer of R7, each wait of R8;
- the consumer kits, and `PublishersSuite` (every event to its family and audit record, never a
  `Personal` value).

## 5. Integration, features and parity (Docker)

`just payouts-it` runs the kit over Postgres, with `FakeWallet` and `FakeGateway` under
`localServices`, the in-memory broker, `TimerRuntime(200.millis)` and a settable clock. It holds these
suites:

- `RequestSuite` (SC-005): 1,000 requests, p99 under 2 s, with the preview and the hold the only calls
  out;
- `PayoutFailureSuite` (SC-002): the gateway broken at every answer and wait; manual-payout states
  counted, and zero;
- `DoublePaySuite` (SC-003): initiated, a timeout, then completed on both routes at once leaves one
  completed request and one confirm;
- `ScaleSuite` (SC-004): 10,000 requests with 200 in flight; only the 200 wake;
- `OutcomeSuite`: the grant's caller admitted and any other refused; a repeat moves nothing; a
  reference mismatch is recorded;
- `ErasureSuite` (SC-006), `LeakSuite`, `OperatorSuite` (roles, another brand);
- `GherkinSuite` over the five features;
- `ParitySuite` over the recordings, once they exist.

## 6. Locally

`just payouts-run` starts the service on 9004 as brand goldvibe, with Postgres from `docker compose`
on 5439. `wallet-run` on its port answers the holds; `psp-gateway`'s local run with its stubs answers the
payouts.

With a player token from goldvibe's realm, `curl` drives a request, its cancel and the listing. An
operator token with `brand_finance` approves one.

## 7. An installation (kind)

Build and load the image as 008's §7, then run
`just modules apply goldvibe --installation dev-kind --modules core --allow-unbuilt`. Then check:

- a verified player's request holds the money and appears in the queue;
- an approval reaches the gateway through the payments grant, and the stub's outcome arrives on the
  outcome route through the core module's grant;
- the request completes, and `withdrawal_completed` is read by `attribution`.
