# Quickstart: checking the predictions module

Prerequisites: Docker; the contract published (`CASINO_VERSION=<unique> sbt contract/publishLocal`);
ankka-predictions v0.1.0 on Maven Central.

1. The contract on 0.14.2: `cd contract && sbt -DankkaVersion=0.14.2 test` passes (R1).
2. Each service: `just service markets test`, `just service exchange test`: unit, bound features
   and the integration kit. Every scenario of `features/predictions/` passes but the two fee
   scenarios (ignored naming ankka-predictions spec 007) and the mirroring one (ignored naming
   ankka-reasoning 0.2.0).
3. The module: `just modules validate --all`; `just modules render <slug> --modules predictions` shows `markets` and
   `exchange` for a brand entitled to `predictions`.
4. SC-002: `just service exchange "testOnly *PlacementPerfSuite"` prints the median and p99.
5. SC-003, on purpose only, against kind (`--context kind-…`): `just it` runs ankka-predictions'
   `features/exchange/orders.feature` against the real gates and wallet.

Checks that can fail: let the gate port skip the play gate, and "an order the play gate refuses
rests nowhere" turns red; let the ledger port answer `Done` without starting a retry on a refusal,
and "a settlement the wallet refuses is recorded, retried and raised" turns red.
