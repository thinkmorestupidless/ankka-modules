# Research: Wallet primitives

**Spec**: [spec.md](spec.md) | **Date**: 2026-10-25

What was read: the spec after its 2026-10-25 clarification; the constitution; spec 002's plan,
research (R1–R17), data model and contracts, and the wallet as built (`WalletEntity`, `WalletState`,
`Commands`, `WalletEvent`, `Resolver`, `DepositWorkflow`, `Flows`, `ServiceEndpoint`,
`AudienceEndpoints`, `Facts`, `Views`, the suites `EveryCommandIdempotentSuite`, `InvariantsSuite`,
`ScaleItSuite`, `WalletFeatures`, `Harness`); the contract (`MoneyTransactions`, `KeyedOutcomes`,
`Routes`, `Layout`, `contract/access/routes.md`, `contract/keys.md`); spec 021's module manifests
and `modules/core/module.json`; spec 023's FR-003 and FR-009; ankka-predictions spec 001 FR-010 to
FR-014, spec 003 FR-012 and FR-018, and spec 004's ledger port (FR-007, FR-008, FR-012); the ankka
0.13 skills (`ankka-design`, `ankka-entities`, `ankka-workflows`, `ankka-endpoints`); the four feature
files under `features/wallet/`. Spec 024 does not exist; what this plan takes from it is named in R7
and R9. Each decision names what established it.

## R1. Reservations are wallet state; the resolver learns `reserved`

**Decision**: `WalletState` gains `reservations: Map[String, Reservation]` (order id → amount still
reserved, placed at) and a derived `reserved = Σ reservations` and `available = realBalance −
reserved`, never stored (as `restrictedBalance` is derived, spec 002 FR-021). The pure resolver's
`Wallet` gains `reserved: Long = 0L`; `fundBet` draws real legs from `realBalance − reserved`, and
`withdraw`'s two real-balance checks (`realBalance < amount`, `realAfter < amount`) compare against
`realBalance − reserved`. Every absolute the plans return (`newRealBalance`, `newReal`, `balanceBefore`)
stays in real terms, so the entity applies plans exactly as today. `fundingWallet` passes `reserved`.

**Rationale**: FR-003 says a hold, its preview and a bet draw on the available amount and refuse
"as they refuse beyond real balance today". The refusal order of spec 002 FR-023 (pending request,
insufficient, turnover) lives in `Resolver.withdraw`; a guard in the entity before the resolver would
have to restate that order, and a `Wallet` whose `realBalance` was the available amount would shift
every absolute in the plan. A field with a default of 0 leaves every Go table and TL fixture unchanged
(SC-001) and `allowNegative` bets meaning what they mean today, against available.

**Alternatives**: a guard in the entity (rejected: duplicates the refusal order); passing available
as `realBalance` (rejected: the plans' absolutes would be re-based in the entity, a second place for
one rule); reservations as an entity per order (rejected: a bet's check against available and the
bet's change would span two entities, the split spec 002 R1 removed).

## R2. Reserve and release are keyed wallet commands with their own refusals

**Decision**: `Reserve(key = orderId, amount, at)` (wire `reserve`): refused `INSUFFICIENT_FUNDS`
with the resolver's `Messages.InsufficientRealBalance` text when `amount > available`, and never
touching restricted funds. `ReleaseReservation(key = s"$orderId/$sequence", orderId, amount, at)`
(wire `release-reservation`): refused `RESERVATION_NOT_FOUND` when no reservation stands under the
order id and `RELEASE_EXCEEDS_RESERVATION` naming both amounts when `amount` exceeds what remains;
a release that leaves 0 removes the reservation. Both are `MoneyCommand`s: keyed through `keyed`,
digested, refusals persisted as `CommandRefused` (spec 002 R3), transaction ids from `Ids.transactionId`.

**Rationale**: FR-001 and FR-002 name the keys (the order id; the order id and a caller's sequence)
and the refusals. The sequence rides in the key, not beside it, because the key store is one map and
two releases of one order must be two keys (spec 002 FR-002). A reservation already placed under the
order id is a repeat (first outcome) or `KEY_REUSED` (different amount), which is what the feature's
"repeated under its key" scenario and the exchange's at-least-once reserve need.

**Alternatives**: a sequence field with the order id as key (rejected: the second release would be
`KEY_REUSED`); a release with no amount, releasing all (rejected: FR-002 says part or all, and the
predictions' ledger port passes an amount).

## R3. A settlement is a workflow across two wallets, keyed by the trade

**Decision**: `SettlementWorkflow` (component `settlement-flow`), id `<currency>/<tradeId>`, started
by `POST /settlements`. Its input: trade id, currency, buyer, order id, seller, `amount` (quantity ×
price) and `unused` (what of the reservation the trade did not use, which the exchange computes: the
order's price less the trade's over the filled quantity, plus the remainder on the order's last
trade; 0 by default). Steps, in order: `check` (a `balance` query on the seller's wallet; absent →
outcome `WALLET_NOT_FOUND`, end), `debit` (buyer's `settle-debit`, keyed by the trade id: refused
`RESERVATION_NOT_FOUND` when the order id has no reservation, `RESERVATION_SHORT` naming both amounts
when `amount + unused` exceeds it; else real and the reservation fall by `amount`, the reservation by
`unused` more, released to available), `credit` (seller's `settle-credit`, keyed by the trade id:
real rises by `amount`; it cannot be refused). The flow's outcome holds both wallets' balances after
and both transaction ids: one outcome for both wallets. `stepRecovery(credit, maxRetries(3))`; the
debit's refusal ends the flow with that refusal as its outcome. Buyer and seller naming one wallet is
refused `INVALID_REQUEST` at the boundary before the flow exists.

**Rationale**: FR-004: one command across two entities, completed on restart, never half done.
`ankka-workflows` rule 1: an entity handler calls nothing, so the two writes are a workflow's steps,
each journalled; a crash after `debit` resumes at `credit` (rule 3), and `settle-credit` keyed by the
trade id makes the re-run move money once. Every refusal happens before the first write, so there is
no compensation step (rule 6): the seller's existence is read first because a wallet is never deleted
(erasure marks fields, spec 002 FR-033), so the read cannot go stale between `check` and `credit`.
The predictions' ledger port (spec 001 FR-012) wants buyer == seller refused and one outcome.

**Alternatives**: the route calling `settle-debit` then `settle-credit` (rejected: the chain dies with
the request, FR-004's restart case); a settlement entity keyed by trade that both wallets consult
(rejected: still two writes, with an entity in the middle that owns no money); `unused` computed in
the wallet from the order's price (rejected: the wallet would hold order prices, the exchange's
knowledge; the wallet is told, never asked, Assumptions).

## R4. One key across two entities: the flow is the record, the entities answer re-runs

**Decision**: as spec 002 R17. The flow's `start` is the atomic claim (a second start is `Conflict`);
its state keeps the request's digest (`MoneyCommand.digest` over the start's fields) and the outcome.
The route answers a `Conflict` from the flow's `status`: the first outcome when the digest matches,
`KEY_REUSED` naming the first command's type and amount when it does not, `OUTCOME_PENDING` (503)
while the steps run past `Flows.Bound`. Each wallet keeps the trade id in its recent keys too, which
is what makes a step that ran and crashed before its transition was journalled move money once.
The same shape serves `AccountMoveWorkflow` (R6).

**Rationale**: FR-010 wants every command idempotent "exactly as spec 002 FR-002 states, within the
same window" and `KEY_REUSED` for a reused key; the window is the wallets' 1,000 keys for re-runs
and the flow's journal for the life of the ledger. Nothing new is invented.

**Alternatives**: a settlement's key in both wallets only (rejected: a crash before the credit leaves
a repeat with nowhere to read "half done" from); awaiting the workflow in the route (ankka 0.13 has
no await; `Flows` is the one place that changes when it does).

## R5. A non-player account is an event sourced entity of its own

**Decision**: `AccountEntity` (component `account`), id `<kind>/<name>/<currency>` (`pool/m1/EUR`,
`clearing/north/EUR`), kinds `pool` and `clearing` only (Assumptions). State: kind, name, currency,
`owner` (the service principal of the first move, `service:<project>/<name>`), `creditLine` (0 unless
set, R7), `balance: Long` (signed), `created`, `seen`/`keyOrder` as the wallet's (1,000 keys). Floor:
0 for a pool account, `−creditLine` for a clearing account. Commands: `claim(owner)` (creates the
account under this owner, or confirms it; `NOT_OWNER` when another owns it), `debit` and `credit`
(keyed, owner-checked, `debit` refused `INSUFFICIENT_FUNDS` on a pool account and
`CREDIT_LINE_REACHED` on a clearing account when the balance would pass the floor), `adjust`
(operator; `REASON_REQUIRED`; `DEBIT_EXCEEDS_BALANCE` naming both amounts when the floor would be
passed; zero a no-op), `set-credit-line` (owner; refused `NOT_CLEARING` on a pool account); queries
`balance` (balance, owner, credit line, floor) and `outcome-of`. Never deleted; no expiry.

**Rationale**: FR-006 and FR-007 describe a ledger with a check followed by the change it guards
(the floor), which is an entity (`ankka-design` step 1); it is not a wallet because it has no player,
no records, no rounds, no deposits, no erasure subject, and a floor below zero. Its own id is the
account's natural key, so "created by the first move that names it" is the entity's first event.
The owner is recorded, not configured: FR-006's "moved only by its owner" needs the owner known at
the first move, and the caller's principal is the only fact the wallet has.

**Alternatives**: accounts as `WalletEntity` instances with a synthetic player id (rejected: every
wallet rule would need a "not for accounts" branch, and the floor contradicts the wallet's); a key
value entity (rejected: the ledger is its history, SC-004); one entity per kind holding many
accounts (rejected: a pool's moves would serialise behind every other pool's).

## R6. A move between a wallet and an account is a workflow, refusals before writes

**Decision**: `AccountMoveWorkflow` (component `account-move-flow`), id `<kind>/<name>/<currency>/
<key>`, for every move with an account: a split (wallet → pool, purpose `split`), a merge (pool →
wallet, `merge`), a resolution payment (pool → wallet, `resolution_payment`, key `<marketId>/
<positionId>`), a clearing move (clearing ↔ wallet, `clearing`). Steps, towards an account:
`claim` (the account's `claim` with the caller's principal: `NOT_OWNER` ends the flow refused),
`debit-wallet` (the wallet's `debit-to-account`, keyed: `INSUFFICIENT_FUNDS` beyond available,
`WALLET_NOT_FOUND`), `credit-account` (keyed; cannot be refused). From an account: `check` (the
wallet's `balance` query: `WALLET_NOT_FOUND`), `debit-account` (keyed, owner and floor checks: the
refusals of R5), `credit-wallet` (the wallet's `credit-from-account`, keyed; cannot be refused). The
outcome carries both balances after and both transaction ids. `maxRetries(3)` on the two credit
steps; the route starts and polls as R4.

**Rationale**: FR-005, FR-008 and the clearing scenarios are two-entity moves; `ankka-workflows`
rules 1, 3 and 6. Ordering the one refusable write first and reading what could refuse before it
(ownership through `claim`, the wallet's existence through a query) means no step compensates.
The claim creates the account as it checks the owner, so a move refused after it (the wallet cannot
pay, or does not exist) leaves the account created, owned by the caller, at 0 (FR-006). That is
kept: only the project's services reach these routes, a service could take the name as well with a
move of 1, and undoing the claim, or claiming at the credit, would bring back a compensating step. The
ledger port answers done, insufficient, or a refusal (predictions spec 004 FR-012): the flow's
outcome is exactly that.

**Alternatives**: one route per purpose with the chain in the handler (rejected: not durable); the
account entity calling the wallet (rejected: handlers call nothing); a single `transfer` command on
the wallet that trusts the caller to have moved the account's side (rejected: the pool invariant
would hold nowhere).

## R7. The credit line is set by the owner; spec 024 decides the value

**Decision**: a clearing account's `creditLine` is a field set by its owner through `set-credit-line`
(`PUT /accounts/{kind}/{name}/credit-line`, body `currency`, `creditLine`), 0 until set, lowered
only as far as the current balance allows (a line below the balance's shortfall is refused
`CREDIT_LINE_BELOW_BALANCE`). Setting the line on an account that does not exist creates it under
the setter, as the owner's first move would (FR-006; spec Assumptions): a pool's clearing service
sets the line before any money moves, and the feature's "holds 0 with a credit line of 500" is one
call. It is audited on `platform.audit`, not a money command (no key, as `set-turnover-override`).
The entitlement that decides the value, and the clearing service that calls this, are spec 024's;
the wallet records what it is told.

**Rationale**: FR-007 says the line is "set by its owner's entitlement (spec 024)" and spec 024 is
unwritten; the wallet cannot read an entitlement it has no spec for, and the owner is the one caller
that may. A separate command keeps the moves' bodies free of configuration and makes the feature's
"with a credit line of 500" one call in the test.

**Alternatives**: the line as a field of every clearing move (rejected: the floor would be whatever
the last caller said); a `clearing` configuration key in the manifest (rejected: per pool and per
currency, which a brand's values file is not).

## R8. The eight families; the account's ledger is a fold

**Decision**: the eight families of FR-011 are added to `contract.facts.MoneyTransactions` and the
schema regenerated (`just schemas`). Seven are keyed by the wallet and extend `MoneyTransactionsFact`
unchanged, each carrying the wallet's real and restricted balance after and `reserved_balance_after`:
`reservation_placed`, `reservation_released`, `settlement_debit` (the buyer's side: trade, order,
`amount_cents`, `released_cents`, the seller as counterparty), `settlement_credit` (the seller's side:
trade, `amount_cents`, the buyer as counterparty), `resolution_payment` (market, position,
`amount_cents`), `account_debit` (the wallet debited into an account: kind, name, purpose, reference,
`amount_cents`) and `account_credit` (the wallet credited from one). The verb names what happened to
the keyed ledger, as `settlement_debit` does. The eighth, `account_adjusted`, is keyed by the account
(`<kind>/<name>/<currency>`) through a second sealed trait `AccountFact` in the same file, carrying
the account's balance after, its credit line, the category and the actor. An account's balance is
the fold over the topic of every fact naming it: `+ account_debit − account_credit −
resolution_payment ± account_adjusted` (SC-004); a wallet's balances are its own facts' `*_after`
fields as today.

**Rationale**: FR-011 lists eight families and says nothing existing changes (spec 001 FR-013);
seven of the eight moves have a wallet side, which is where the reader of a player's statement needs
them (User Story 4), and the account's moves are each the other side of one of those. Publishing the
account's side too would need family names the spec does not give.

**Alternatives**: account-keyed `account_debit`/`account_credit` from a consumer over `AccountEntity`
with `balance_after` per move (not taken: it leaves the wallet's side of a split or clearing move
with no family in the spec's list; if a reader needs the account's balance after every move, that is
two added families, `account_balance_debited`/`_credited`, in a later spec, additive).

## R9. Routes: the service class, the caller's name, and the pool's grant

**Decision**: every new write route is of the service class under `ServiceEndpoint`'s prefix,
`Routes.service()` admitting the project's services (`exchange` among them, with no change) and, for
the clearing routes, the pool's `clearing` service as a grantee the predictions module's manifest
makes (spec 023 FR-009, spec 024), read through `Layout.BrandGrantsMade` as the two deposit grants
are. `contract.access.Routes` gains `serviceNamed(grantedRoute)`: the admission of `service` as an
authenticating ACL that yields the caller's principal (`service:<project>/<name>`, as
`projectOrPaymentsOperator` already builds it), so the account routes know the owner. Reads: the
operator prefix gains an account's balance and adjustment; the player's `balance` and `transactions`
gain fields and types under their existing paths (FR-013). `contract/access/routes.md` gains the
rows. The brand manifest (`modules/core/module.json`) changes nothing: the wallet's descriptor is the
same and the grant is the predictions module's to make.

**Rationale**: FR-013; spec 001's classes table; two path parameters at most and no shared prefixes
between endpoints (ankka 0.11+), so every account route is `/accounts/{kind}/{name}/<verb>` with the
currency in the body and the handler refusing a kind that is not `pool` or `clearing`.

**Alternatives**: a separate `AccountsEndpoint` (rejected: it would share the service prefix);
`pool` and `clearing` as literal segments (rejected: a literal and a parameter at one position in
two routes is a router question the plan need not ask).

## R10. Reads change additively

**Decision**: `Balance` gains `reserved` and `available`; `MajorBalance` gains the same as major-unit
strings; `HoldPreview` gains `available`. `TransactionType` gains `reservation`, `reservation_release`,
`settlement_debit`, `settlement_credit`, `resolution_payment`, `account_debit`, `account_credit`;
`TransactionRows.of` writes one row per new wallet event with `walletType = real`, `balanceBefore`/
`balanceAfter` in real terms (a reservation moves real by 0 and records the reserved amount as its
`amount`). The deposit sums and `SumsView`'s existing sums are untouched; `LifetimeTotalsView`
gains nothing (the predictions' "reserved, settled, paid out" totals are a view spec 023 may add).
An account has no view: its balance is the entity's query, its history the topic (R8).

**Rationale**: FR-012; spec 002 FR-028, FR-029 shapes kept; `reads.feature`'s ignored scenario is
the one un-ignored.

## R11. Testing, and what parity means here

**Decision**: reservations, releases and the single-wallet sides of settlement and account moves
bind in `WalletFeatures` (unit kit, `features/wallet/reservations.feature` and the balance read).
`settlement.feature` and `accounts.feature` bind in an integration `GherkinSuite` over the
`Harness` (`AnkkaTestKit`, Postgres): two wallets, the workflows, the fake callers
(`Caller.Service(project, "exchange")`, `"clearing"`), the facts. The interrupted settlement: the
suite sets a test hook on `SettlementWorkflow` (an `AtomicReference`, as `DepositWorkflow.rg`) that
blocks the `credit` step on a latch; it settles, sees the debit on the buyer, calls
`testKit.restartService()`, which abandons the blocked step and resumes the flow at `credit` from
its journal, and sees the seller credited and the flow `Completed` through the lifecycle query. The
step timeout stays well above the test's wait. `EveryCommandIdempotentSuite` gains a case per new
`MoneyCommand` (it fails without one); `InvariantsSuite` gains reserve, release, settle-debit and
move steps and the invariants `reserved == Σ reservations`, `available ≥ 0` unless a correction
debit; a new `AccountInvariantsSuite` holds `balance ≥ floor` over random debits, credits,
adjustments and line changes (SC-003). `ScaleItSuite` gains SC-002 under `WALLET_PERF=1`: 500
reserve/release/settle/payment commands per second across 50 wallets for 20 s, each kind held to
its own figures (R15). `MappingSuite` and `FactsSuite` cover the eight families; `SchemaDriftSuite`
fails until `just schemas` runs.

Parity: there is no original of this feature (spec 023's exchange is new; ankka-predictions' ledger
was never in production). SC-001 is held by spec 002's own evidence unchanged: the Go tables, the
TL fixtures, every spec 002 scenario, and the recordings. No wallet recordings exist under
`tests/port/wallet/` (the directory is absent and `PortSuite` reports itself ignored), so "replay
with no difference" is vacuous today and becomes real the day the driver's recordings land; this
plan leaves no new parity test and changes no replayed route's behaviour (`Wallet.reserved` defaults
to 0, every existing body is unchanged).

**Rationale**: constitution I and VII; `ankka-workflows` "Testing": assert on the lifecycle; a
compensation-free flow is tested by its crash, not by its happy path alone.

## R12. The wallet asks nothing; the signature is the operator's token

**Decision**: no step of any new flow calls `responsible-gambling`: the exchange asks `may-bet` with
the order's cost before reserving and reports stakes and losses as the clarification of 2026-10-25
says; the wallet is told. An operator adjustment's "signature" (FR-009) is the operator's
authenticated staff token: the actor is its subject, as spec 002's `adjust` already carries it, and
the audit record on `platform.audit` names it. No separate signed payload is introduced.

**Rationale**: the clarification; spec 002 FR-026 names a reason and the actor and nothing more, and
FR-009 asks for what FR-026 requires. A detached signature would need a key the operator does not
have.

## R13. Erasure and a removed owner change nothing in the wallet

**Decision**: a reservation is money: the 042 erasure handler leaves `reservations` as it leaves
balances. An account whose owner's module is disabled keeps its balance and owner; the operator's
adjustment is the only other move, as FR-006 and the edge case say; nothing detects the removal.

## R14. A cancel's clawback takes only what is available

**Decision**: the original clamps a cancel's clawback to the real balance; here it is clamped to the
available amount (real balance less reserved; the real balance when nothing is reserved, so the
original's behaviour is unchanged for every wallet without a reservation). A clawback never draws on
money the exchange holds for an order.

**Rationale**: FR-003 says nothing but a settlement or a release draws on a reservation. Clamping to
real would let a cancel leave `reserved > realBalance`, which breaks the invariant every fold checks.

**Alternatives considered**: refusing the cancel while a reservation stands (the original never
refuses a cancel for want of money); clawing back the shortfall from the reservation (FR-003).

## R15. The workflows have a latency budget of their own

**Decision**: SC-002 holds reserve and release to 20 ms median and 100 ms p99, and settle and
resolution payment to 120 ms median and 300 ms p99. `ScaleItSuite` asserts each kind against its own
figures.

**Rationale**: measured on 2026-10-25 at 501 commands a second with a market per wallet: reserve
8.4/41 ms, release 11.5/46 ms, settle 95/278 ms, payment 82/171 ms. Reserve and release are one
entity command; settle and payment are workflows of three journalled steps, and the time is the
runtime's per step, not the wallet's work. The single 20 ms figure was set before the design made
these two workflows (R3, R6). Three more runs on 2026-10-30 (a laptop, Postgres in Docker) held the
medians at 73 to 93 ms, but the p99 ranged from 189 to 634 ms for payment and 196 to 308 ms for
settle: one of the two was over 300 ms in each run. 300 ms stands as the target, and the suite fails
when a run misses it, until a measurement on real infrastructure says whether the tail is the
laptop's or the wallet's.

**Alternatives considered**: one entity command across the accounts (ankka has no atomic command
over several entities); claiming the account at the credit instead of first, which shortens nothing
and brings back a compensating step (R6); polling the flow less often, which was tried and was
slower. A faster workflow step is ankka's to give; when it does, the budget tightens.

## R16. The movements cross spec 025's pools as spec 025 assumed

**Decision**: spec 025 (merged to main while this spec was in its worktree) keeps real balance as a
deposit pool and a converted pool, and asked this spec to confirm how its movements split them. It
is confirmed: a reservation and its release move neither pool, since nothing leaves real balance; a
settlement's buyer side and every move into an account leave the converted pool first; a
settlement's seller side and every move from an account (merge, resolution payment, clearing) join
the converted pool. A settlement draws on its reservation, which a correction debit may have left
above real balance, so the debit's shortfall beyond both pools goes to the converted pool, as an
allow-negative bet's does (spec 025 FR-003). Each event and each wallet-keyed family carries the
parts and both pools after; `account_adjusted`, keyed by the account, carries no pools.

**Rationale**: money another player or a market's pool pays in is not traceable to a deposit, and
spec 025's tie-break is the one that gives the affiliate least (Trevor, 2026-10-14). Nothing in spec
012 counts these families, so the choice moves no commission today; it fixes what the facts say if a
later spec counts them.

**Alternatives considered**: tracing a pool account's money back to the pools of the wallets that
split into it (a ledger per market of deposit and converted origin), which a market paying out to
many players across many splits makes a per-position allocation problem; and deposit first for the
buyer's debit, which would make a player's trading look like spent deposits.

The resolver's `available` is real balance itself when nothing is reserved, negative after a
correction debit, so spec 025's proportional bet on a negative balance is refused as the original
refuses it; the wallet's reads still clamp `available` at 0.

## Resolved unknowns

| Unknown | Resolved by |
|---|---|
| Where reservations live and how bets and holds see them | R1 |
| Reserve and release keys and refusals | R2 |
| A settlement across two wallets, and its completion after a crash | R3 |
| One key across two entities; `KEY_REUSED` from a flow | R4 |
| The non-player account's component, id, owner and floor | R5 |
| Split, merge, resolution payment and clearing moves | R6 |
| The credit line without spec 024 | R7 |
| The eight families and the account's keying | R8 |
| Routes, the caller's name, the pool's grant | R9 |
| What the reads gain | R10 |
| How each scenario is tested; parity with no original | R11 |
| RG and the signature | R12 |
| Erasure, removed owners | R13 |
| A cancel's clawback beside a reservation | R14 |
| SC-002's figures for the two workflows | R15 |
| The movements across spec 025's pools | R16 |
