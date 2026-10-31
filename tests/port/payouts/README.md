# payouts (spec 007, T053)

Recorded 2026-10-31 from the original on its own KinD stack, twice, on fresh ids. Two sets:

- 56 recordings by `tests/original/payouts-driver/`, one per scenario of `features/payouts/*.feature`
  the original can show. Both runs: 56 `pass`. The scenarios it cannot show are listed, with why, in
  the driver's `NotDriven` (`scenarios.go`): the original's sweeper polls on its own five-minute
  schedule and a provider's review window is a day or more, neither of which a run waits out; psp-stub's
  payout providers never answer *completed* at initiation; the original's double-approval defect (R1) is
  not driven; nothing on the stack answers psp-gateway down or with an unknown word; and the clawback
  and funds-policy cases need game rounds or an offer's rule snapshot the driver does not create.
- The original's own `TestScenario251_*` (four tests, `scenario251_withdrawal_cap_lifecycle_e2e_test.go`),
  by `tests/original/recorder`, each `pass` on both runs. The recorder filed them under `realm`, their
  first request being the operator's sign-in; they were moved here, so their `service` still reads
  `realm`. Each was given the requirement it shows: `007/FR-009` for the cancellation, `007/FR-016`
  for the two confirmations, `007/FR-020` for the reversal.

`services/payouts`' `ParitySuite` replays all 60; `port/Parity.scala` names the divergences and the
stand-ins for the other services' steps.

## The stack

- casino-service was built from the original's `main` at `e7d8f2de` (its #733 added the brand's
  withdrawal limits, which the spec ports); the rest of the stack is the players run's `a78decf1`. The
  image was built in a detached worktree of the original, with the Lightbend token copied there only,
  tagged `eitheror/casino-service:dev`, `kind load`ed and rolled out (`kubectl --context kind-eitheror-dev`).
- Ports as for players (`../players/README.md`): casino-service `8091`, Keycloak `8180`, the twin API
  `9000`, the wallet `8092` and its internal port `19001` through reconnecting port-forwards.
- psp-stub ran on the host (`PSP_STUB_GATEWAY_URL=http://localhost:8087`), with psp-gateway's
  providers pointed at it as for players. Unity Finance's payouts were held *pending* through its
  `/__control` route, so a polled payout stays in doubt.
- The operator: the realm-sync job's `dev-operator`, through `casino-backoffice`. The outsider:
  `payouts-outsider` in the `eitheror` realm, made for the run, with no role at any brand. The replay
  sends it as an operator of another brand.
- The outcome route was signed with casino-service's `WALLET_SERVICE_INTERNAL_SECRET`, read from its
  deployment into the run's environment and never written; the recordings carry no `X-Internal-Secret`.

## What the run found about the original

- **Its terminal index overflows.** Eligibility reads every terminal in the brand's index
  (`eitheror-terminal-index/adecbe44-…`) at once; past 64 entries the substrate client's connection pool
  overflows and every terminal answers `TERMINAL_NOT_ELIGIBLE`. Earlier runs had left 107 entries; the
  index was trimmed to the brand's seeded `cfg_0c628563` before the run, and the driver now takes its
  terminals out of the index when a scenario ends. Scenario 251 does not, so its terminals stay listed.
- **An email's local part over 64 characters makes registration answer 503.** The driver keeps it
  within 64.
- **The queue is read from a projection.** A request is listed a moment after it is answered; the
  driver reads the queue again until it is.
- **The limits count the wallet twin's `HAS_WITHDRAWAL_REQUEST` edges**, which the substrate shows a
  moment after the hold is answered; a second request sent before then is refused
  `WITHDRAWAL_REQUEST_ALREADY_PENDING` by the hold, not by the limit. The driver waits for the edge.

## Reading them

- The terminal listings hold every terminal of the brand's index at the time, scenario 251's and the
  seeded one among them. The replay compares only the terminals the recording itself wrote, in order
  (`Parity.scoped`).
- The operator's read of one request (`observe:` steps) holds the original's whole twin, the player's
  test address among it; it is compared by `status` and `payoutStatus` only, and waited on.
