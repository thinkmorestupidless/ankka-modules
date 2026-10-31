# Review: spec 022 amendment, a split charged from a reservation (FR-008a)

**Verdict:** Approve with comments (after the one should-fix, resolved below).
**Reviewer:** the implementing session, with the pr-review checklist. The fork that wrote this could
not spawn a reviewer with a fresh context, so this is not the independent review `/speckit-review`
asks for. A second reviewer should read `origin/main...HEAD` before merge.
**What it does:** It lets the exchange settle a trade through the pool. Each buying leg is a split
charged from that leg's order reservation, through the existing `/splits` route and move flow. The
wallet uses a new command, `charge-reservation-to-account`, which applies `settle-debit`'s rule with
the pool account as the other side. `account_debit` gains `order_id` and `released_cents`.
**Should it exist:** Yes. ankka-predictions spec 005 FR-011 matches two buys of opposite outcomes
through the pool, and 022 had no move that draws a reservation into an account (item 9 of
ankka-predictions `notes/casino-requests.md`).
**Read:** the whole diff. Opened outside it: `SettlementWorkflow`, `AccountEntity` (`claim`, `debit`,
`credit`), `Views.scala`'s rows for `MovedToAccount`, attribution's `RealMoney` (a consumer of
`money.transactions`), and ankka-predictions' `LedgerPort` and `Settlement` (the caller).
**Size:** 25 files. About 120 hand-written lines are production Scala, about 150 are tests and the
rest are spec documents and a regenerated schema.

## Should fix

- `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/ServiceEndpoint.scala:285-294`
  issue (non-blocking): the route's two new refusals were not tested: `unused` without an order on
  a split, and an order on a merge or a clearing move. Suggestion: an integration case for each,
  asserting `INVALID_REQUEST` and that nothing moved.
  Resolved: `ReservationsItSuite` "FR-008a: unused without an order …", f24d64f

## Nits

- `services/wallet/src/main/scala/io/eitheror/casino/wallet/views/Views.scala:185` nitpick
  (non-blocking): a charged split's transaction row reads `reference = pool/<market>/<key>`, as any
  split's does, and does not name the order. The fact names it, which is what a fold needs.
  Left: nit; the player's history shows a split, which is what it was.
- `ServiceEndpoint.scala:301` nitpick (non-blocking): `c._1, c._2` would read better destructured.
  Left: nit.

## Questions

- `specs/022-wallet-primitives/research.md`, Amendment, Assumptions: question: should the wallet
  check that the buying legs of one trade sum to the sets they split (one unit per set)? It cannot
  today, because each leg is its own keyed move and the wallet has no trade. The reconciliation of
  spec 023 FR-011 compares the pool account with the exchange's pool.
  Answered: no, recorded as an assumption to confirm. The exchange decides the trade, as it decides
  every amount it sends.
- Same place: question: should a leg refused after the other leg was charged be undone?
  Answered: no, recorded as an assumption to confirm. The reconciliation raises the difference.

## Praise

- `WalletEntity.scala`, `chargeReservationToAccount`: the reservation rule is the one
  `settle-debit` keeps, refusal for refusal, so the two paths that draw a reservation down cannot
  drift.
- `AccountMoveWorkflow.digest` and `MoveDigestSuite`: a start without an order keeps the digest it
  had before the amendment. The suite proves this against the old shape, so a repeat across the
  upgrade is not `KEY_REUSED`.
- Using a split rather than a settlement to an account keeps every fold of a pool account on
  `account_debit` (the SC-004 check in `AccountsFeaturesItSuite` needed no change).

## Checks applied

- **General groups applied:** Correctness, Design and scope, Error handling, Data and migrations
  (the stored event and the flow start gained defaulted fields; a new command and manifest; no
  wire name moved), API and compatibility (two optional fact fields, the schema regenerated),
  Tests, Documentation, and Commit hygiene.
- **General groups skipped:** Concurrency (no new shared state; the flow is the existing one) and
  Security beyond input validation (no new route or ACL).
- **Language skill:** pr-review-scala's types, error handling and tests groups.
- **Project rules read:** `CLAUDE.md`, and 022's tasks Notes (every money command has an
  idempotence case).
- **Tests run:**
  - the wallet's whole build: 737 passed, 102 ignored, before f24d64f;
  - `ReservationsItSuite`: 6 passed after it;
  - the contract's suites: 215 passed;
  - `just features`: 0 findings.
