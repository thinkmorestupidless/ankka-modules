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

## Second round

# Review: 007 payouts — the first round's fixes (5f86cb0...607cf67)

**Verdict:** Approve with comments. Nothing blocks. The two should-fix items are small, and we expect them before merge or answered with a reason.
**What it does:** It fixes the first round's findings:
- A provider's reason text now reaches only the request's `Personal` note. Facts and audit records take a code.
- Every step that calls an entity or the wallet fails over to a `recover` step that backs off and runs the step again. A `Manual` answer inside `initiate` is now in doubt, not awaiting manual payout.
- A waiting poll is recorded the first time and at each change.
- `stale` on its own means the approved requests.
- The idempotency digest lists its fields.
- The two questions are answered.
**Should it exist:** yes, because each commit closes a finding of the first round, and nothing outside those findings is touched.
**Read:**
- Read in full: the whole diff, and the first round's report.
- Opened outside the diff:
  - `WithdrawalWorkflow.scala`, in full: `start`, every step and the commands, to check each position in `resumable` against the step that sets it.
  - `request/WithdrawalRequest.scala`: the commands' guards, and whether a refusal is a reply or a throw. It is a reply.
  - `facts/Facts.scala`: the audit actor for each source.
  - `clients/Gateway.scala`: where each `Initiation` and `PayoutStatus` reason comes from.
  - The `"error"` bodies of psp-gateway.
  - ankka's `build/workflows.md`: a command that ends a pause removes its timer, so a stale `recover` timer cannot fire `waitStep` on a request that has moved on.
- Not reviewed again: anything the first round covered and these commits leave alone.
**Size:** 17 files, +370 −45. About 290 of those lines are Scala and 126 are docs. The size suits a fix round.

## Resolutions of the first round

- **Blocking (provider reason text on facts and audit): holds.**
  - `ReasonCode.split` is applied at every place a provider's reason enters:
    - the outcome route (`OutcomeEndpoint.scala:46`)
    - the initiation's `rejected` and `in_doubt` answers (`WithdrawalWorkflow.scala:357-359, 371, 379`)
    - the poll's `failed` and `reversed` answers (`:415, 422`)
  - The fallback codes match `contracts/facts.md`. `OutcomeRecord.note` is blanked by `Erased` (`WithdrawalRequest.scala:154`), and the audit names the note without writing it (`Facts.scala:82`).
  - The other reasons left unsplit are payouts' own words or the gateway's codes, not a provider's text: `Unreadable` (status words and transport errors), `Refused` (the gateway's `"error"` code) and `Unavailable` (logged, then recorded as `gateway_unavailable`).
  - `LeakSuite` plants a holder's name through a rejection, a failed poll and an outcome-route reversal, and checks the facts, the log and the tables. The initiation's `in_doubt` and the poll's `reversed` are covered by `ReasonCodeSuite` alone.
- **Should fix 1 (manual payout after an initiation may have reached a provider): holds.** Inside `initiate`, a `Manual` answer is now recorded in doubt and goes to the wait. Only `pay` parks a request for manual payout. The explanation that a 2 s client timeout cannot outlive a 30 s step is sound. See the second should-fix below for who the audit says gave that answer.
- **Should fix 2 (a workflow failed with money moved): holds, with one gap.**
  - `resumable` was checked against each step that sets its position:
    - `start` sets Holding, `hold` sets Opening, the commands set Releasing, Approving and Confirming, `approve` sets Paying, `pay` sets Initiating, the waits set Waiting, the poll sets NeedsReview, and `confirm` sets Reversing.
    - A step that throws commits no state, so `recover` reads the last position recorded.
    - Every target is safe to run again: wallet steps behind the `money` set, entity commands that answer `same` to a repeat, and `initiate` replaced by the wait.
  - The gap: the wait, reached after `initiate` failed before it recorded anything, can leave the request marked nowhere. That is the first should-fix below.
- **Should fix 3 (waiting polls not recorded): holds.** `lastPoll` records a waiting answer the first time and at each change. The new `PayoutFailureSuite` case would fail if every poll were recorded, and also if none were. FR-029, facts.md and research.md agree with the code.
- **Should fix 4 (`stale` alone answers nothing): holds.** `stale` is now parsed before `status`, `None if stale` gives `APPROVED`, and an explicit status still wins. `QueueFilterSuite` pins all three, and routes.md says the same.
- **Nit 1 (`case _` read as a reversal; `Status2`): holds.** The match names `"reversed"` and refuses anything else with `UNRECOGNISED_OUTCOME`. The import is `GatewayStatus`.
- **Nit 2 (digest of `Draft.toString`): holds as far as it goes.** The fields are listed and ordered, and `FingerprintSuite` tells an absent field from an empty one. The reason for keeping the personal fields (they sit only in the workflow's state, a named-gap table) is accepted.
- **Question 1 (`REQUEST_NOT_PENDING` either way): answered, and the answer holds.** A workflow settles a hold in one direction only, and the follow-up for spec 002 is recorded in research.md.
- **Question 2 (KYC `submitted`): answered, and the answer holds.** The comment cites the original.

## Should fix

- `services/payouts/src/main/scala/io/eitheror/casino/payouts/workflow/WithdrawalWorkflow.scala:393, 441` — issue (non-blocking) (if-minor): a request whose initiation failed before it recorded anything is marked needs review nowhere, though its money stays held.
  - **How it happens:**
    1. `initiate` throws before the gateway call on every one of its six attempts, for example because `WithdrawalRequest.get` or `PlayerPayouts.get` times out.
    2. It fails over to `waitStep` with the step still `Initiating`. `recover` resumes that position the same way (`resumable(Initiating) = waitStep`).
    3. Nothing has called `WithdrawalRequest.initiated`, so the request is `APPROVED` with payout status `none`.
    4. The poll answers `no_record`, or the provider's window passes, and `reviewStep` calls `needsReview`.
    5. `needsReview` writes only from `payoutStatus == Initiated` (`WithdrawalRequest.scala:64`), so it answers `same`.
  - **What it breaks:** the workflow sits at `NeedsReview` while the request shows in neither the needs-review set, nor the default queue, nor `stale`, which wants `initiated`. An operator can settle it only if they find it.
  - It needs an entity outage across six attempts, and the failover to `waitStep` came before this round. But this round adds a second way in, and it is the "nothing reconciles it" case the first round's second finding was about.
  - **Suggestion:** at the top of `waitStepRun`, when the request's payout status is `None`, call `initiated("in_doubt")` first. The new `Manual` branch does this already. The alternative is to let `needsReview` accept `PayoutStatus.None` on an approved request.
  Resolved: `waitStepRun` first marks an approved request with payout status none as initiated, answer `initiation_unconfirmed` (in doubt), so `no_record` or the window marks it needs review and it is listed. As with the first round's fix, no test injects the entity outage this needs. 8f2398b
- `services/payouts/src/main/scala/io/eitheror/casino/payouts/workflow/WithdrawalWorkflow.scala:348-349` — issue (non-blocking) (if-minor): the `details_erased` in-doubt answer is recorded with `Source.Gateway`, so `platform.audit` records `psp-gateway` as having said `in_doubt` (`Facts.scala:98-99`). The paired `PayoutInitiated` is likewise audited as the gateway's (`Facts.scala:95`). In that run the gateway was not called.
  - FR-029 asks the audit to name the actor. A compliance reader would take this as the gateway's answer.
  - **Suggestion:** record the answer with `Source.Poll`, which the audit names as the workflow. Or add a `Source.Workflow("workflow")`: an additive wire value, with `settledBy` and the `OutcomeRecorded` actor mapping taught to read it.
  Resolved: a new `Source.Workflow` (`workflow`, additive) for what the workflow records with no gateway answer behind it (`details_erased`, `initiation_unconfirmed`). `PayoutInitiated` with either answer, and `OutcomeRecorded` from that source, are audited as the workflow. `PublishersSuite` checks both; data-model.md and facts.md say so. 8f2398b

## Nits

- `services/payouts/src/main/scala/io/eitheror/casino/payouts/api/PlayerEndpoint.scala:78-82` — nitpick (non-blocking): the field list is inferred as `Vector[Any]` and taken apart by a runtime `case o: Option[?]`. That is the inferred-`Any` shape the Scala checklist warns about. A typed `Vector[Option[String]]`, with the amount and `confirmForfeit` wrapped as `Some(…toString)`, says the same and lets the compiler check it.
  Resolved: `Vector[Option[String]]`. bae7cac

## Questions

- `services/payouts/src/main/scala/io/eitheror/casino/payouts/workflow/WithdrawalWorkflow.scala:504` — thought: `recover` now retries a deterministic fault, such as a `.get` on an absent `terminal`, `held` or `settle`, every 30 s without end.
  - It logs at error each time, but it counts nothing. The ankka lifecycle reads `Paused`, not `Failed`.
  - While the position is in `Flow.Busy` (Holding, Opening, Approving, Paying, Initiating), an operator's commands answer `BUSY` for as long as the fault lasts.
  - That trade is better than a silent failed workflow, and the first round asked for it. Still, a counter or a gauge of workflows in `recover`, which an alert can watch, would turn "said loudly" into "seen". No change is needed for this spec if the logs are alerted on; say which.
  Answered: the logs, for this spec. Each `recover` logs at error with the request id and the position. No alert on them exists yet; one, or a gauge of workflows in `recover`, is a follow-up for spec 015's operational views.
- `services/payouts/src/main/scala/io/eitheror/casino/payouts/request/ReasonCode.scala:12` — thought: the shape rule decides what may be published.
  - Upper-case provider codes (`INSUFFICIENT_FUNDS`, `E1023`) become `provider_failed` on the fact, which loses information. A lower-case single token that is a name (`smith`) passes as a code.
  - Both are unlikely to matter with the four providers' known reasons. If one of them sends upper-case codes, an allowlist per provider in psp-gateway, sending `code` apart from `message`, would serve better than widening the regex. That is a follow-up for spec 005, not this change.
  Answered: agreed. It is a follow-up for spec 005: psp-gateway sends the provider's `code` apart from its `message`.

## Praise

- `services/payouts/src/main/scala/io/eitheror/casino/payouts/workflow/WithdrawalWorkflow.scala:549` — praise: `resumable` turns "where does a failed step pick up" into one table that can be checked, and `WithdrawalWorkflowSuite` pins it in both directions: every working position is resumed, and no paused or ended one is. Mapping `Initiating` to the wait rather than to `initiate` is exactly the duplicate-payout guard the spec needs.
- `services/payouts/src/test/scala/io/eitheror/casino/payouts/it/PayoutFailureSuite.scala:98-113` — praise: the waiting-poll test drives pending, then unavailable, then pending, then completed, and asserts the exact sequence. It goes red under both wrong designs: recording every poll, and recording none.

## Out of scope

- note: on a case-insensitive file system (macOS) `specs/007-payouts/review.md` is also `REVIEW.md`, so the pr-review gather script reports this report as the repository's own review checklist. Name the report `review-report.md`, or name it in the speckit-review template, so that a later review does not read the last report as its rules.

## Checks applied

- **General groups applied:**
  - Correctness, Design and scope, Error handling, Concurrency and state (workflow single-writer, pause timers).
  - Security (personal data on facts, audit and logs).
  - Data and migrations: `Flow.lastPoll`, an additive field with a default. `Source` is unchanged.
  - Tests, Observability, API and compatibility (the queue's default when `stale` is given; the fingerprint, with no service deployed), Documentation, and Commit hygiene (one finding per commit, each naming the finding it resolves).
- **General groups skipped:** Performance (no new queries or loops) and Dependencies (none changed).
- **Language skills:** `pr-review-scala`, the bundled one, applied in its order. Totality: the `.get`s in steps, which feed the `recover` thought. Error handling: entity refusals are replies, not throws. Then tests and idioms.
- **Project rules read:** `CLAUDE.md` at the repository and the workspace. There is no `REVIEW.md` other than this file (see Out of scope) and no `.claude/rules/`.
- **Run:** `ReasonCodeSuite`, `FingerprintSuite` and `QueueFilterSuite`: 8 passed, 0 failed. They compile against the branch's main code.
- **Not run:** `LeakSuite`, `PayoutFailureSuite` and `WithdrawalWorkflowSuite`, which need Docker and several minutes. The failover to `recover` is exercised by no test that injects a fault, so it is confirmed by reading.
