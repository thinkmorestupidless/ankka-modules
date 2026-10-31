# Review: feat(022): a split charged from a reservation, for trades through the pool (#23)

**Verdict:** Approve with comments. The three should-fix items are small, mostly documentation, and
expected before merge or answered with a reason.
**What it does:** It adds a third way to debit a wallet into a pool account. A split may name an
order, and then its amount comes out of that order's reservation, not the available amount, with
`unused` released in the same move. The exchange settles each buying leg of a trade through the
pool this way. The wallet does it with a new command, `charge-reservation-to-account`, which copies
`settle-debit`'s reservation rule. The event is the existing `MovedToAccount` with two defaulted
fields, and the fact is `account_debit` with two optional fields.
**Should it exist:** Yes. ankka-predictions spec 005 FR-011 matches two opposite buys through the
pool, and the `LedgerPort.settle(…, Split, legs)` docs say each leg is charged from its reservation
into the pool. Spec 022 had no move that does that. A split is the right base: pool money is
already a split on every fold, and a new command (not two fields on `debit-to-account`) leaves
every existing key's digest alone.
**Read:** In full: the whole diff (code, tests, spec, research, data-model, contracts, tasks,
README, GLOSSARY, feature file, schema). Outside the diff: `WalletEntity` (`keyed`, `reserve`,
`releaseReservation`, `settleDebit`, `debitToAccount`, `movePools`, the event handler),
`WalletState.reserving`/`reserved`/`available`, `WalletCodecs`, `FactCodecs`,
`AccountMoveWorkflow` (all of it), `ServiceEndpoint` (settlements and the account routes),
`SettlementWorkflow`'s check, `Views.scala`'s rows, `Facts.audit` for `CommandRefused`,
`InvariantsSuite`'s checks, every `money.transactions` consumer (attribution `RealMoney`,
responsible-gambling `usage/Consumers`, players `WalletSummaryView`/`ActivityView`), spec 023
FR-011 and its Dependencies, and in ankka-predictions `notes/casino-requests.md` item 9, spec 005
FR-011, `Ports.scala` (`LedgerPort`) and `Settlement.scala`. The author's `review-amendment.md` was
read last, after this view was formed.
**Size:** 26 files, +433 −31. About 90 lines are production Scala, about 145 are tests, and the
rest is documents plus 8 lines of regenerated schema. The hand-written code is well under the
split threshold, so no split is needed.

## Should fix

- `specs/022-wallet-primitives/research.md` (Amendment 2026-10-31) and Description:
  issue (non-blocking): this is safe to upgrade but not to roll back, and nothing says so.
  `WalletCodecs.stored` skips unknown fields (the jsoniter default). A wallet binary from before
  this change therefore reads a charge's `MovedToAccount` without `orderId` and `unused`. Its
  replay then leaves the order's reservation at its full amount, so `reserved` is too high and
  `available` too low. The same happens on an old pod during a rolling deploy if it recovers the
  entity after a new pod wrote a charge. The wallet has no snapshots, so the state heals once every
  pod runs the new binary. Until then the player's available amount is understated, and an old pod
  would still accept a `settle-debit` or `release` against the phantom reservation. I confirmed the
  drop with a probe: the old `AccountMoveStart`/`AccountMoveFlow` shape decodes a new flow with
  `orderId = Some("o1")` and silently loses the field. The same probe decodes the new event. Spec
  025's pool fields set the same precedent, which is why this is not blocking.
  Suggestion: add one line to the Amendment and the PR body: "No rollback of the wallet past this
  version once a charge is journaled; the exchange's pool settlement is enabled only after the
  wallet's rollout completes." If-minor.
  Resolved: research's Amendment says no rollback past this version once a charge is journaled, and pool settlement on only after the wallet's rollout; the PR body says so.
- `specs/022-wallet-primitives/tasks.md:425` (T056): issue (non-blocking): the task is ticked and
  says "the rules in `EntityRulesSuite`", but the diff does not touch `EntityRulesSuite.scala`. That
  suite has no `settle-debit` cases either. The rules are tested elsewhere: success, removal at 0
  and the second charge in `WalletBasicsSuite`, both refusals and the repeat in
  `EveryCommandIdempotentSuite`, and the boundary `required == reservation` in the first
  `accounts.feature` scenario (650 = 600 + 50). Suggestion: change T056's text to name the suites
  that hold the rules. Otherwise add the cases to `EntityRulesSuite`, including RESERVATION_SHORT
  one unit over the boundary.
  Resolved: the cases are added to `EntityRulesSuite` (exactly the reservation, and one unit over), and T056 says they came after review.
- `services/wallet/src/main/scala/io/eitheror/casino/wallet/api/ServiceEndpoint.scala:131`
  (`charged`) with `WalletEntity.scala` `chargeReservationToAccount` (`Math.addExact`): issue
  (non-blocking): a split with `amount` 1 and `unused` 9223372036854775807 passes the route's
  checks. The entity then throws `ArithmeticException: long overflow` instead of refusing (probe
  confirmed). In the flow this is the `debit-wallet` step failing three times on the same input. I
  did not run the flow end to end, so what the caller then sees for that key (presumably
  `OUTCOME_PENDING` with no end) is unconfirmed. `/settlements` has the same gap and is
  pre-existing. Suggestion: refuse at the route when `amount + unused` overflows, using
  `INVALID_REQUEST`, in `charged` (and in `/settlements` while there). Alternatively, make the
  entity map the overflow to RESERVATION_SHORT. If-minor.
  Resolved: `drawn` refuses `amount + unused` past a `Long` as `INVALID_REQUEST` on `/splits` and `/settlements`; `ReservationsItSuite` covers both and fails without it.

## Nits

- `specs/022-wallet-primitives/data-model.md:63`: nitpick (non-blocking): "`orderId` (absent) and
  `unused` (0) when charged from a reservation" reads as if those are the values when charged.
  Suggestion: "`orderId` and `unused`, set when charged from a reservation (FR-008a); absent and 0
  otherwise".
  Resolved: reworded as suggested.
- `specs/022-wallet-primitives/contracts/routes.md:23`: nitpick (non-blocking): the splits row
  lists `WALLET_NOT_FOUND`, but a split with `orderId` against a player with no wallet answers
  `RESERVATION_NOT_FOUND`. The charge, like `settle-debit`, does not check `created`, and the
  to-account flow checks nothing first. The merges and moves rows do not name the new
  `INVALID_REQUEST` for an `orderId`. Suggestion: one clause in each row.
  Resolved: one clause in each of the three rows.
- Description, "The merge side": nitpick (non-blocking): item 9 says the merge side is "spec 022's
  payment as it stands", but this PR uses merges. The PR's choice is the right one: a merge is keyed
  by the caller, and `LedgerPort`'s `Merge` pays legs, not positions. Say in a sentence that this
  departs from item 9's wording, so ankka-predictions does not wire `pay`.
  Resolved: research's Amendment and the PR body say so.

## Questions

- `research.md`, Assumption 1 (legs not summed): thought: agreed. The wallet cannot know the set
  count. Spec 023 FR-011's reconciliation compares reserved, released and split figures with "the
  wallet's facts". After this change, part of an order's drawdown arrives on `account_debit`
  (`order_id`, `released_cents`), not on `reservation_released`/`settlement_debit`. Is that
  recorded where 023's reconciliation will be designed? It is resolved if 023's plan names
  `account_debit.order_id` as a reservation movement.
  Answered: spec 023 FR-011 now names `account_debit` with `order_id` as a reservation drawdown, in this PR; its dependency line on item 9 is updated too.
- `research.md`, Assumption 2 (a charged leg stands when the other is refused): thought: agreed.
  The refusal is visible: `CommandRefused` produces a `platform.audit` record with action
  `charge-reservation-to-account`, and the exchange's settlement consumer logs it. The consequence
  is worth one line: the refused buyer holds shares the pool was not paid for, so the pool runs
  short at resolution, and the last resolution payment meets the account's floor. No answer is
  needed if the reconciliation is accepted as the remedy.
  Answered: the reconciliation is the remedy; research's Assumption 2 now says the pool pays short until it is settled.
- `research.md`, Assumption 3 (RG): question: the responsible-gambling consumer
  (`services/responsible-gambling/.../usage/Consumers.scala:36-40`) counts neither
  `settlement_debit` nor `account_debit`. So "counts where a settlement's debit does" means it
  counts nowhere downstream of the wallet. The substance is right (the gate saw the cost at
  `reserve`), but would "the charge is not counted again; the gate counted the cost when the order
  was reserved" say it plainly? Rewording resolves this.
  Resolved: reworded: the gate counted the cost at the reservation, and nothing counts it again after the wallet.
- `WalletEntity.scala` `chargeReservationToAccount`: thought: the wallet does not check that the
  order's reservation is for the market whose pool account it charges, because reservations carry
  no market. Settlement has the same property, and the reconciliation is keyed by order id. Nothing
  to do now.
  Answered: agreed; nothing to do now.

## Praise

- `AccountMoveWorkflow.digest` with `MoneyCommand.digestOf(…, unset)` and `MoveDigestSuite`: an
  in-flight or recently keyed split keeps its digest across the upgrade. The suite proves it
  against a field-for-field copy of the old shape, not against the new code's own output.
- `research.md`, "Why a new wallet command": reusing `debit-to-account` with defaulted fields would
  have made every recent split's repeat `KEY_REUSED`. A new command with no history avoids that
  without any special casing.
- `chargeReservationToAccount` matches `settleDebit` refusal for refusal, details included. Its
  replay in the `MovedToAccount` case uses the same `amount + unused` drawdown as
  `SettlementDebited`, so the two reservation paths cannot drift apart.
- `InvariantsSuite`: the new generator case picks among the wallet's real reservation keys (with a
  miss for `none-n`), so the reserved-equals-sum and available-not-negative invariants are tested
  against charges that actually land.

## Out of scope

- note: `specs/023-predictions-module/spec.md:259` (on another branch) still says spec 022's split
  "does not yet do" this. Update it when #23 merges.
- note: `/settlements` has the same `amount + unused` overflow path as the third should-fix. Fix it
  in the same commit or in an issue.

## Where this disagrees with the author's self-review

- The self-review's Data and migrations pass covers the upgrade, not the downgrade or rolling-deploy
  read by an old binary (first should-fix).
- It does not catch that T056's `EntityRulesSuite` claim is ticked without a hunk.
- It does not test the overflow path.
- It reads Assumption 3 as stated; the RG consumer shows the wording overstates it.
- Agreed with it on: the resolved should-fix (`ReservationsItSuite` now covers the route's two
  refusals), both of its nits (left as nits, not repeated here), and its answers to the two
  questions it raised.

## Checks applied

- **General groups applied:** Correctness, Design and scope, Error handling, Data and migrations
  (stored event, flow start, digest), API and compatibility (fact fields, schema, spec 001's
  additive rule, old consumers' codecs skip unknown fields), Tests, Observability (audit of the new
  action and its refusals), Documentation, Commit and PR hygiene.
- **General groups skipped:** Concurrency (no new shared state; entity commands are serialised and
  the reservation check bounds concurrent settle/release/charge), Security beyond input validation
  (no new route or ACL; the existing `/splits` ACL is unchanged), Performance (no new loop, query or
  allocation of note), Dependencies (none added).
- **Language skill:** pr-review-scala, the bundled one (no repository override). Applied:
  serialization and persisted-type evolution, types and totality, error handling, tests, build
  (contract case class shape is binary API; consumers are separate builds against a published
  snapshot, so no MiMa applies). Idioms skimmed. Formatting not reviewed.
- **Project rules read:** `CLAUDE.md` (root, workspace, ankka-modules),
  `.specify/memory/constitution.md` (grep for stored and journal rules), spec 001's "MAY add fields
  and MUST NOT remove one". There is no `REVIEW.md` and no `.claude/rules/`.
- **Tests run:** in a `git archive HEAD` copy under the scratchpad, with
  `CASINO_VERSION=0.2.0-freshrev-SNAPSHOT`: `contract/publishLocal`, then `WalletBasicsSuite`,
  `EveryCommandIdempotentSuite`, `InvariantsSuite`, `MoveDigestSuite`, `MappingSuite` and a probe
  suite (event round trip with `orderId`/`unused`, old flow shape reading back with defaults, an
  old binary reading a new flow, overflow): 69 passed, 0 failed. The integration suites were not
  run locally: the PR's CI is green on the `wallet` job and every service job, and the PR reports
  737 passed. No PR comments were posted and nothing was committed.
