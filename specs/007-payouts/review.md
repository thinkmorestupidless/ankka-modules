# Review: 007 payouts — a brand's withdrawals from request to settlement on ankka

**Verdict:** Request changes. One blocking finding: the provider's free-text failure reason reaches `casino.withdrawals` and `platform.audit` unmarked. The should-fix items are expected before merge or answered with a reason.
**What it does:** Adds the `payouts` brand service. A request passes the original's gates in order (identity, the wallet's preview, the brand's per-currency limits, terminal eligibility, the payee). A workflow per request then holds the money, pauses for review, decides automated or manual payout, calls `psp-gateway` once with the request id as key, and settles from the outcome route, a status poll or an operator. The contract gains the player's email, `player_email_changed`, the deposit's `psp`/`scheme` and the withdrawals' gateway, terminal and reference. Players publishes the email, and `brand_finance` joins the operator realm.
**Should it exist:** yes, because it replaces the original's 3,088-line route file and its capped five-minute sweeper with a reconciliation that grows with requests in flight. It keeps the money in the wallet, and the spec, plan and tasks all call for it.
**Read:**
- Read in full: everything under `services/payouts/src/main` (workflow, request, player, limits, terminals, queue, consumers, facts, clients, api, codecs, settings, service), the contract diff, `services/players` and `services/wallet` publisher changes, the `psp-gateway` listing change, the realm, `ci.yml`, `Justfile`, `build.sbt`, `service.json`, spec.md, plan.md and `contracts/routes.md`.
- Read in part: `contracts/facts.md` and tasks.md; `LeakSuite`, `GatesSuite`, `QueueSuite` and `ScaleSuite`, for what they assert.
- Opened outside the diff: `contract/.../access/Routes.scala` (the operator and player ACLs), `psp-gateway`'s `delivery/Receivers.scala` and the providers' `PayoutFailed` sites (Morphosis, Cozeni, Unity Finance, Gigadat), spec 005's `contracts/payouts.md`, and ankka's `build/workflows.md` (pause timers, recovery, virtual threads).
- Skimmed: the Go driver under `tests/original/payouts-driver/`, research.md and data-model.md.
- Not reviewed: the recordings under `tests/port/payouts/` (data), and the feature bindings beyond spot checks.
**Size:** 207 files, +49,589 −81. Of that, about 36.7k lines are recordings and config, about 8.5k Scala (about 2.7k main), 2.2k Go and 2.1k docs. That is far past what a single review catches defects in. The split we would accept for the next spec of this size:
1. The contract, players, wallet and realm changes.
2. The service.
3. The driver, recordings and parity suite.

## Blocking

- `services/payouts/src/main/scala/io/eitheror/casino/payouts/facts/Facts.scala:46` — issue (blocking): `withdrawal_failed.failureReason` is the provider's free text, in the clear, on a topic kept as fact. `withdrawal_reversed.reason` (line 48) and the audit record's `reasonCode` (lines 101 and 109) carry the same text.
  - **How it gets there:**
    - The gateway delivers the provider's message as `reason`: `PayoutFailed(code, cb.message…)` in Morphosis.scala:268, `resultMsg` in Cozeni.scala:280, `stateName` in UnityFinance.scala:329 and `Receivers.scala:53`.
    - `OutcomeEndpoint.scala:23` passes that text through as `Outcome.reasonCode`.
    - `WithdrawalWorkflow.scala:219` turns it into `Fail.reasonCode`, and the poll path does the same at lines 396–399 from `Gateway.scala:107`.
    - `Initiation.Rejected(reason)` (`Gateway.scala:91`) records it as `OutcomeRecord.reasonCode`, which is a plain `String` (`request/Model.scala:82`) that `Erased` does not touch (`WithdrawalRequest.scala:154`).
  - **What it breaks:**
    - FR-025: "MUST NOT carry … reason text in the clear".
    - FR-027: "every free-text reason … MUST be `Personal`".
    - `contracts/facts.md:16`: `failureReason` is "a code".
    - A bank's or wallet provider's message can name the account holder. Once it is on `casino.withdrawals` it cannot be erased (SC-006).
    - `LeakSuite` does not catch it, because its markers are only operator reasons and notes.
  - **Suggestion:**
    - Keep codes on every fact and audit record: the provider's code, which `PayoutFailed` already carries and the gateway can send as `code`, else `payout_failed` / `chargeback` / `provider_rejected`.
    - Keep the provider's text, if it is kept at all, as `OutcomeRecord.note: Personal[String]`, so erasure blanks it.
    - Add a provider-reason marker to `LeakSuite` through `FakeGateway`'s failed, reversed and rejected answers, and through an outcome-route failure.
  Resolved: `request/ReasonCode` keeps a provider's reason only when it is a code (lower case, digits, `_`, up to 64); other text is the request's `Personal` note, which erasure blanks, and the fact and audit take a fixed code (`provider_failed`, `provider_rejected`, `chargeback`). Applied on the outcome route, the initiation's rejected and in-doubt answers, and the poll's failed and reversed ones. `LeakSuite` plants a provider message naming the holder on all three; `ReasonCodeSuite`; facts.md says where the text goes. 7668952

## Should fix

- `services/payouts/src/main/scala/io/eitheror/casino/payouts/workflow/WithdrawalWorkflow.scala:332-336` — issue (non-blocking): a payout that may have been sent can still be parked for manual payment.
  - The `initiate` step is retried by its recovery (line 161: `maxRetries(5)`), for example after it times out at 30 s while the gateway is still calling the provider.
  - On a retry it re-decides from the request. If the payee or email was erased between attempts, `Approval.decide` answers `Manual` and the request becomes awaiting manual payout.
  - That breaks FR-018's "A request waiting MUST NOT become awaiting manual payout by any path but an operator's" and SC-002, for a payout the provider may hold. The window is narrow (an erasure during a retry), but this is the costliest mistake the spec names.
  - Suggestion: in `initiateStep`, take a `Manual` answer to `waitStep` as in doubt, not to `AwaitingManual`. The poll then answers `no_record` (404) when nothing reached the gateway, and the request goes to needs review for an operator. Only `payStep`, which runs before any call, may choose manual.
- `services/payouts/src/main/scala/io/eitheror/casino/payouts/workflow/WithdrawalWorkflow.scala:155-165` — issue (non-blocking): several steps can end the workflow failed after the wallet has moved money, and nothing reconciles a failed workflow, because the sweeper is gone.
  - `open`, `releaseStep`, `approveStep`, `pay`, `review`, `confirm` and `reverse` have bounded retries and no failover. Each calls the request or player entity, which throws on an ask timeout or a database blip.
  - When the retries run out, the workflow fails. Two examples:
    - A failed `open` leaves the money held, no request in the queue, and the player's endpoint answering 503 for the key forever.
    - A failed `confirm` leaves a payout the provider made with its hold never confirmed.
  - FR-024 asks every money step to retry until it is answered. That holds for the wallet's transport failures inside a step, not for the step's own exceptions.
  - Suggestion: give each of these steps a failover. The failover pauses with `Flow.backoff` and transitions back to the step recorded in `currentState.step`, as the wallet-unreachable branches already do. At least log and count a failed withdrawal workflow so someone sees it.
  - Question: does ankka's `RecoverStrategy` space its retries? If it retries at once, ten retries last milliseconds.
- `services/payouts/src/main/scala/io/eitheror/casino/payouts/workflow/WithdrawalWorkflow.scala:410-411` — issue (non-blocking): a poll answered `pending` or unavailable is neither recorded on the request nor audited.
  - FR-029 says every poll result is written to `platform.audit`, and `contracts/facts.md:40` says "every poll result". An operator reading a request stuck in waiting sees no polls at all.
  - Suggestion: `recorded("pending" | "unavailable", reason, None)` before the transition. If the volume (every 5 minutes, unbounded) is the reason this was left out, say so in the contract and record the first and every Nth instead.
- `services/payouts/src/main/scala/io/eitheror/casino/payouts/api/OperatorEndpoint.scala:65-66,79` — issue (non-blocking) (if-minor): `?stale=true` on its own always answers an empty page.
  - The status filter defaults to `pending_review`, and a request whose payout is `initiated` is never pending review.
  - `ScaleSuite` and `QueueSuite` pass `status=APPROVED` or `status=ALL` and so never see this. The brand's stale count (FR-022) is wrong for any caller that does not.
  - Suggestion: when `stale` is set and no status is given, default the status to `approved`.
  Resolved: a `Manual` answer inside `initiate` is recorded `in_doubt` / `details_erased` and goes to the wait, never to awaiting manual payout. The trigger named here cannot occur as described: `ankka.service-client.timeout` is 2 s, so the gateway call never outlives the 30 s step, and a slow gateway is already in doubt. A retry needs a later request-entity call in the step to throw, or an erasure between `pay` and `initiate`. The kit cannot inject either, so the fix is covered by reading and by the existing suites staying green. 233645b
  Resolved: every step that calls an entity or the wallet now fails over, past its retries, to a `recover` step. It logs at error, waits with `Flow.backoff`, and runs the step its state names again (`WithdrawalWorkflow.resumable`). An initiation resumes at the wait, never at a second payout. `WithdrawalWorkflowSuite` pins which positions resume. On the question: ankka's docs state no spacing between recovery retries, which is why the failover backs off itself. 233645b
  Resolved (settled with the user): a `pending` or `unavailable` (`gateway_unavailable`) poll is recorded and audited the first time and whenever the answer changes (`Flow.lastPoll`), not at every poll, which would be unbounded. FR-029, facts.md and research.md say so; `PayoutFailureSuite` checks pending → unavailable → pending → completed. 73e671c
  Resolved (settled with the user): with `stale` and no status, the status is `APPROVED`; an explicit status still wins. routes.md says so; `QueueFilterSuite`. The original has no stale filter, so nothing there differs. 9e74be6

## Nits

- `services/payouts/src/main/scala/io/eitheror/casino/payouts/workflow/WithdrawalWorkflow.scala:222` — nitpick (non-blocking): `case _` treats every outcome word other than completed and failed as a reversal. It is safe today only because `Outcomes.Known` filters first. Writing `case "reversed"` with an explicit refusal for anything else keeps the workflow safe on its own. (The import `PayoutStatus as Status2` at line 8 reads oddly beside the request's `PayoutStatus`; `GatewayStatus` would say what it is.)
- `services/payouts/src/main/scala/io/eitheror/casino/payouts/api/PlayerEndpoint.scala:71-72` — nitpick (non-blocking): the `KEY_REUSED` fingerprint is a SHA-256 of `Draft.toString`. That ties the idempotency check to the case class's shape: a field added to `Draft` answers `KEY_REUSED` to an in-flight repeat. It also stores, in the workflow's state past erasure, a digest of the mobile and account numbers, which are low-entropy. Digest an explicit, ordered list of the non-personal fields, and the personal ones salted per request, or leave the personal ones out.
  Resolved: the match names `"reversed"` and refuses anything else `UNRECOGNISED_OUTCOME`; the import is `GatewayStatus`. 7668952
  Resolved in part: the digest names its fields in order (`FingerprintSuite`), so `Draft`'s shape no longer matters. Left: the personal fields stay in the digest. It lives only in the workflow's stored state, one of the named-gap tables that hold the raw values until ankka 042, so it adds no exposure; it goes with them when 042 lands. eee2ed2

## Questions

- `services/payouts/src/main/scala/io/eitheror/casino/payouts/clients/Wallet.scala:66-68` — question: `REQUEST_NOT_PENDING` is read as "done" for release, confirm and reverse alike, and `WithdrawalWorkflow.scala:433` relies on it for confirm.
  - Does the wallet answer the same code when the hold was settled the other way, for example a confirm against a hold already released?
  - If it does, a crash-window repeat or a key past the wallet's 1,000 recent ones would record the opposite of what the ledger did.
  - The answer that settles it: the wallet's refusal names the outcome the hold ended with. Then `step` accepts only the matching outcome and treats any other as a refusal to surface.
- `services/payouts/src/main/scala/io/eitheror/casino/payouts/request/Refusals.scala:36-41` — question: a player whose document is `submitted` gets "KYC verification required", and `GatesSuite.scala:87` pins that. The spec's Assumptions say the "under review" message stands for "a document pending or an account set pending". Does the original answer `submitted` with its default message, as recorded? If so, a comment citing the recording settles it. If not, `Submitted` should get "Your identity documents are under review".
  Answered: yes, the wallet answers `REQUEST_NOT_PENDING` for a confirm of a released hold and a release of a confirmed one (`WalletEntity.scala:550, 572`). payouts cannot meet it the other way round. A workflow settles a hold in one direction only: a released workflow ends, and a late `completed` is refused `ALREADY_RESOLVED`. A repeat key is answered with its first outcome, and the `money` set never resends a step past the wallet's recent keys. Only payouts calls the holds routes. Having the refusal name the hold's end state is a wallet change (spec 002), noted as a follow-up.
  Answered: yes, it is the original's. Its table is the same, word for word (`WithdrawalRoutes.scala:252-256`), and `submitted` falls through to the default. A comment now cites it. eee2ed2

## Praise

- `services/payouts/src/main/scala/io/eitheror/casino/payouts/clients/Gateway.scala:83-99` — praise: the initiation answer releases money only where the gateway proves nothing reached a provider: 400, 403 and `502 provider_not_configured`. Every other status, an unrecognised word, an unreadable body and no answer are in doubt. That is the right default for the duplicate-payout risk, and it is stated in the enum's doc comments.
- `services/payouts/src/main/scala/io/eitheror/casino/payouts/workflow/WithdrawalWorkflow.scala:68-80` — praise: the `money` set records each wallet step once it is answered, beside the wallet's own keys. A repeat past the wallet's 1,000 recent keys is answered from the workflow's state and never resent, which is exactly what R9 and spec 002's note ask.
- `services/payouts/src/main/scala/io/eitheror/casino/payouts/api/OutcomeEndpoint.scala:29-34` — praise: the outcome route's ACL is derived from the grant the layout declares, with a `require` that fails at start if no grant names it. It does not use `Routes.service`, which would have admitted every service of the brand's project.
- `services/payouts/src/test/scala/io/eitheror/casino/payouts/it/LeakSuite.scala` — praise: the suite proves its own frame finds a planted marker, and it asserts that the named erasure gap still holds names, so it goes red when ankka 042 lands. A leak suite that cannot fail silently is rare.

## Out of scope

- note: the journal, snapshots and workflow state keep `Personal` values after erasure until ankka 042. This is the named gap, shared with `players` and `kyc`, and `LeakSuite` tracks it. No new issue is needed beyond ankka 042.

## Checks applied

- **General groups applied:** Correctness, Design and scope, Error handling, Concurrency and state, Security (ACLs, personal data, CI job), Data and migrations (stored and wire formats, contract additions, all additive with defaults), Performance (per-event entity reads in the publishers, view paging), Tests, Observability, API and compatibility, Dependencies and build, Documentation and naming, Commit hygiene.
- **General groups skipped:** none.
- **Language skills:** `pr-review-scala`, the bundled one (no repository override). Applied in its order: concurrency and actors, serialization (wire-name enum codecs, transient defaults, manifests), types and totality, error handling, collections, tests, build, API, idioms.
- **Not reviewed for Go:** the Go driver is a recording harness under `tests/original/`. It was skimmed, not reviewed with `pr-review-go`.
- **Project rules read:** the root `CLAUDE.md` and the workspace `CLAUDE.md`. The repository has no `REVIEW.md` and no `.claude/rules/`.
- **Enforced by tooling:** only `-deprecation -feature -Wunused:all`, and no scalafmt check, so unused code was not reviewed by hand.
- **Run:** `contract/publishLocal`, then the unit suites of request, limits, terminals, player, facts and clients, plus `WireTranslationSuite` and `ApprovalSuite`. 124 passed, 0 failed.
- **Not run:** the integration, feature and parity suites, which need Docker and several minutes. The commit log says they are green on kind; that was not checked here.
- **Not verified by running:** the two workflow findings (the retry after an erasure, a failed workflow after exhausted recovery) were confirmed by reading, not by a run. Each needs a fault-injection case in `PayoutFailureSuite` to prove.
