# Review: 013 affiliate programme (origin/main...HEAD)

**Verdict:** Request changes
**What it does:** Adds `services/affiliate-programme`, the shared programme project's one ankka service. It does three things:
- reads every brand's attribution facts, from topics and from signed webhooks, into an immutable per-(affiliate, brand, currency) commission ledger;
- settles that ledger through period closes into per-currency payouts;
- serves the original's portal and operator routes with the brand as a filter.

It also adds the contract's `affiliateManager`/`brandsOf`, the realm role, the manifest and render changes, a Go recording driver and exporter, and the parity replays.
**Should it exist:** yes. Spec 013 and decision 0055 call for one programme serving every brand. The design (encrypted identities, inbox dedupe, deterministic fact ids, paged closes) is sound, and most of it is carefully built.
**Read:**
- Read in full by this review: the contract, realm, manifest and Render diffs; `Settings`, `ProgrammeService`, `Wiring`; every file under `api/`; `intake/*`; `accounts/EmailIndex`, `players/ClickRecord`, `players/PlayerAttribution` (decide/next); `settlement/Close` (Closer, CloseRun).
- Read in full by three slice reviewers whose findings were confirmed here before inclusion:
  - every main file under `commission/`, `ledger/`, `settlement/`, `periods/`, `csv/`, `network/`, `accounts/`, `sealing/`, `tags/`, `links/`, `postbacks/`, `comms/`, `activity/`, `players/`, `imports/`, `brands/`;
  - `port/*`, `it/` (Kit, fakes, Close, Commission, Network, Leak, Erasure, Latency, Start, Intake, Managers), `features/CommunicationSteps`, `commission/OriginalCasesSuite`;
  - `tests/original/programme-driver/*.go`, `tests/original/programme-export/*.go`, `tests/local/programme-walk.py`, and the Justfile and CI diffs.
- Skimmed: the remaining feature step bindings (scanned for assertion-free `Then`s) and the unit suites by name.
- Not read line by line: the recordings JSON under `tests/port/programme/` and `import/bundle.json`. They were analysed by script for PII, tokens and secrets; none were found.
- Opened outside the diff: the original's `affiliate-service` (`CommissionEngine.scala`, `InternalRoutes.scala`, `OperatorRoutes.scala`, `Postback.scala`, `CommissionEngineSpec.scala`) and the ankka secrets doc.
**Size:**
- 238 files, +67,250 −52. About 44,800 of those lines are recordings JSON and about 2,300 are spec documents.
- Hand-written code: about 7,500 lines of main Scala, about 9,400 of test Scala and about 3,000 of Go.

This is far past the size at which line review stays reliable. The findings below are the ones confirmed; the remainder had less scrutiny per line than a split change would get. Next time, the split we would accept is:
1. contract, realm, manifest and render;
2. the pure packages and the ledger and settlement entities;
3. accounts, sealing and the API;
4. the driver, the recordings and parity;
5. the import.

## Blocking

- `api/OperatorEndpoint.scala:66-67, 78-79, 143-144, 233-243` with `api/DataSubjects.scala:79`, `api/Patching.scala:16-42` and `api/Backoffice.scala:229` — issue (blocking): a manager of one brand can change account-level data of an account that also works other brands, including another company's hosted brand. This breaks FR-041.
  - **What a single-brand manager can change.** The `member(brand, aff)` check only proves a membership at the path's brand. Behind it:
    - update-email re-seals the account's email and changes the realm user's email;
    - the PATCH re-seals name and payment method and changes `minimumPayout` and `postbackUrl`;
    - approve with `parentAffiliateId` re-parents the whole account.
  - **Takeover chain.** A hosted brand's manager calls `POST /{brand}/accounts` (O8) with the email of any known affiliate. `createAccount` gives that existing account an active membership at their brand without the affiliate's consent. `update-email` then points the account at an address the manager controls, and `reset-password` (O17) mails them a reset link. They now hold the affiliate's portal for every brand and can redirect payouts through `paymentMethod`.
  - **Leak.** O8 for a known email also hands the manager the account's sealed identity, payment details included, through O20.
  - **The code already has the rule.** `DataSubjects.whole` (decided 2026-11-02, research T082) refuses close and erasure unless the caller holds every brand of the account.

  Suggestion:
  - Apply `whole` (or a platform-admin-only rule) to every account-level write: update-email, identity and payment fields, `postbackUrl`, `minimumPayout`, parent and override.
  - Keep per-brand writes (plan, `revShareOverride`, status) at the brand.
  - Make O8 for an email already known create a pending membership (an application the affiliate must accept), not an active one. Answer it without the identity.
  - Add an `ErasureSuite`-style test for each route with a manager who holds only one of the account's brands.
  Resolved: account-wide writes (patch, email, close, erasure) are for a holder of every brand the account is a member of, 7be659c8.

- `api/Networks.scala:142-158` and `:161-180` (called from `api/PortalEndpoint.scala:63-71`) — issue (blocking): an ancestor can approve, suspend, reactivate or re-plan a sub's membership at a brand where the ancestor has no membership. FR-029 limits a plan change to "a brand where the ancestor also holds a membership", and the same scoping is the only thing that keeps the brand's manager review meaningful.
  - **Approval bypass.** A sub that applied to brand B on its own is pending for B's managers. Its parent can approve it on B's default plan.
  - **The fraud case.** That parent may be a second account of the same person. Brand B's manager review is skipped and CPA accrues.
  - **Plan changes.** `patch` checks only that the sub is a member and the plan exists, not that the ancestor holds the brand or that the plan is active.

  Suggestion: in `transitionAt` (when `kind == Ancestor`) and in `patch`, require `api.membership(caller, brand).active`, and refuse a plan whose status is not `Active`. The portal route has the caller (`asTarget(aff)(caller => …)`), so pass it through.
  Resolved: an ancestor acts on a sub only at a brand it holds itself, onto an active plan, f79a2cda.

- `ledger/CommissionAccount.scala:239-244` with `settlement/Close.scala:45-48` — issue (blocking): a re-close below the threshold releases the payout's earlier claims, but leaves the payout pending or approved with its old figures. `Closer` only touches the payout when `closing.claimed`.
  - **Path to a double payment.**
    1. Close P.
    2. A negative late fact arrives.
    3. Close P again; the total is now below the minimum.
    4. The stale payout is still approvable and payable.
    5. Paying it runs `markPaid`, which stamps nothing, because the facts were released.
    6. Next period's close claims the same facts again.
  - **The original avoids this.** `CommissionEngine.scala:884` keeps earlier claims and leaves the payout as it is.
  - **The tests miss it.** `CommissionAccountSuite` ("below the threshold … earlier claims are released") and `LedgerViewSuite:62` check only the account half.

  Suggestion: on `BelowThreshold` with `prior.nonEmpty`, keep the claims and reply as claimed (the original's behaviour). Otherwise, move the payout to a state that cannot be paid, with zero figures, in the same close step. Add a `CloseSuite` case: close, negative late fact, re-close, try to pay.
  Resolved: a re-close below the threshold keeps the claims its payout was made from, c9eb54d6.

- `api/Settlement.scala:131-133` with `ledger/CommissionAccount.scala:246-248` — issue (blocking): paying is two unrelated commands, `markPaid` on the account and then `Payout.pay`. Nothing ties what is marked to what is paid.
  - **Lost fact.** A concurrent close can claim a late fact for the approved payout between the route's read and `markPaid`. That fact is then stamped paid, but it was never in the paid total.
  - **Split state.** A close can revert the payout to pending between the two calls. `markPaid` succeeds and `pay` fails. The account now says paid while the payout is pending, and every later close of that account fails with `paid`.

  Suggestion: `Payout.pay` first, carrying the claim ids or figures it pays. Then `markPaid(payoutId, expectedIds)`, with the account refusing when its claims differ. Alternatively, a small pay workflow keyed by the payout id.
  Resolved: paying marks paid only the claims the payout shows, refused otherwise, d40e5b55.

- `ledger/ToLedger.scala:91-92` with `ledger/CommissionAccount.scala:105-108` — issue (blocking): RevShare is skipped only when the membership is not active. With `bp = 0`, `RevShare.delta` writes minus everything already emitted in the window. That happens for a CPA plan after a RevShare one, for a plan switched off RevShare, and for a `revShareOverride` of 0.
  - In the lifetime window this claws back the affiliate's whole RevShare history, paid periods included, on the next GGR fact after a manager changes the plan.
  - The original emits only when `revSharePct > 0 && isRevShareEligible` (`InternalRoutes.scala:227`).

  Suggestion: `if !c.rate.payable || c.rate.revShareBp <= 0 then None`, and add a plan-change test.
  Resolved: a plan at no RevShare writes no RevShare fact, 334c3db3.

- `src/test/scala/io/eitheror/casino/programme/port/Parity.scala:181-191` and `:64-65` — issue (blocking): parity cannot fail where it matters most.
  - **`$`-varied lists.** For a list marked varied at `$`, `under(at)` and `!varied(at)` are both true for every leaf, so no row is compared, not even the replay's own. 13 steps across 6 recordings are hit, among them:
    - every "list the period's payouts" step of the close recording (threshold, late credit, paid, next period);
    - the fraud flags ("raised once");
    - plans and managers;
    - the application, approval and rejection listings.
  - **Divergence 13.** It waives `.status` on every PATCH `/status` whose original answer was 4xx, so "pay the rejected payout" passes even if it answers 200. The payout status machine therefore has no parity check, and the close recording's `Covers` claims rest on comparisons that do not run.

  Suggestion:
  - For a `$`-varied list, compare the rows whose key field (`RowKeys`: `payoutId`, `affiliateId`, …) matches a value this replay filled, and waive only the rest.
  - Narrow Divergence 13 to a recorded 4xx observed as `409 status_transition` (give `holds` the observed reply).
  - Break one behaviour (pay a rejected payout) and confirm the replay goes red.
  Resolved: a shared list's own rows are compared by key, divergence 13 holds only for a refused transition, and paying a rejected payout turns the replay red (checked), 517481c5 and 69477cc5; tightened further in dca80e8b.

- `intake/FactInbox.scala:19, 48` and `players/PlayerAttribution.scala:130, 150` — issue (blocking): FR-038 says the programme MUST forget country and click details on `player-closed`. Only `PlayerState.country`/`clickId` and the view are cleared.
  - `FactInbox` stores every fact's full JSON body (`InboxState.fact`) for ever. That includes the registration's `country` and `clickId`.
  - `binding.clickId` stays in `PlayerState` and its snapshots.
  - The rendered postback URL (`{clickid}`) stays in `PostbackState`.
  - Separately, the `Applied` events in the journal keep the registration fact as well.

  Suggestion:
  - Do not keep the body after the outcome is recorded (store only kind, day, currency and amount, which is all `Inbox` reads), or strip personal fields at claim.
  - Clear `binding.clickId` on `Closed`.
  - Record in research whether journal retention waits for ankka 042, and say so in FR-038's traceability.
  - Extend `LeakSuite` with a closed player whose country and click id must be absent from every table it scans.
  Resolved: a closed player's click id and country are kept nowhere but the journal, whose retention waits for ankka 042 (research R9), 739a11af.

## Should fix

**Security**

- `postbacks/Postback.scala:110-114`, `api/Patching.scala:41`, `Settings.scala:25` — issue (non-blocking): server-side request forgery. An affiliate sets `postbackUrl`, and the service GETs it with no scheme check and no refusal of loopback, private, link-local or metadata addresses. `lastStatus`/`lastError` are shown back to the affiliate, which makes it an oracle into the cluster.
  - `Settings.plainHttp`, documented as "an http postback or webhook address admitted: for a local run and the suites only", is never read anywhere, so `http://` is admitted in production.
  - The original had the same hole, but parity does not cover a security defect.

  Suggestion: validate the URL at write time in `Patching` (https unless `plainHttp`, a public hostname) and check the resolved address at send time.
  Resolved: a postback address must be public https, refused `400 postback_url_refused` when written and checked again on its resolved address when sent, a94950db.
- `comms/EmailSend.scala:24, 67` with `api/Passwords.scala:116-118`, `api/Networks.scala:234-236` and `api/Managers.scala:92-94` — issue (non-blocking): the raw reset, invite and manager-invite tokens go into `SendEmail.data` as links, and so into the workflow's state and journal. This contradicts `Token.scala:13` ("the token itself is never stored"): anyone reading the database or a backup can use a live reset token.
  Suggestion: mint and issue the token inside the send step, or seal `data` and clear it when the send ends.
  Resolved: a token is minted in the send's step, so only its hash is stored, 0132a60b.
- `accounts/Secrets.scala:45-51` with `api/Enrolment.scala:17` — issue (non-blocking): `emailIndexKey` is made on read on the request path. Two requests before `Seed` has run can mint different keys; the last write wins and the other's digests are orphaned, which silently breaks email uniqueness. `DataSubjects.scala:93` likewise uses the creating `dataKey` where `existingDataKey` is meant.
  Suggestion: only `Seed` creates the key, and the request paths answer 503 when it is absent.
  Resolved: the seed alone makes the email index key; a read before it answers unavailable, 2f67e7a1.
- `tests/original/programme-export/export.go:107, 118` — issue (non-blocking): the production Spinvibe bundle holds plaintext affiliate identities, payment details, notes and live invite tokens, and it is written with `os.Create` (0644 after umask).
  Suggestion: `os.OpenFile(..., 0o600)`; a doc line saying the bundle is personal data, never committed and deleted after import; leave out expired or revoked invite tokens.
  Resolved: the bundle is written 0600, e8ff6f57; and no sealed field leaves in an audit row's details, 7d9b6834.

**Money and settlement**

- `ledger/CommissionAccount.scala:181-186` — issue (non-blocking): `recordPaymentFee` writes an NGR deduction but no RevShare delta. FR-014 says every NGR-moving fact folds afresh, and the original recomputes after the payment fee (`InternalRoutes.scala:1062-1117`). A deposit after a period's last GGR leaves RevShare overstated by the fee.
  `it/CommissionSuite.scala:64-74` sends the deposit before the GGR, which hides this. Add a GGR-then-deposit case.
  Resolved: a payment fee folds RevShare afresh, ca9878c7.
- `settlement/Close.scala:88, 101` and `:95-100` — issue (non-blocking): `pageStep` has no per-account error handling, and `CloseRun.ended` is only called from `finishStep`.
  - One account that keeps throwing (a `paid` Conflict, a timeout) fails the page.
  - If the workflow then gives up, `CloseRun.running` stays true. `Settlement.close` joins the dead run and answers 202 for that brand and period for ever.
  - `finishStep` reads every carried payout in one step, unbounded, which breaks the plan's 50-a-step bound.

  Suggestion: catch per account and record the failure in `CloseState`; add a failover step that ends the run; page `finishStep`, or answer from `Payouts.byBrandAndPeriod`.
  Resolved: a close passes over an account that fails, logged and answered under `failed`, gives up a failing run, and pages its finish, d3984711.
- `ledger/CommissionAccount.scala:26, 66` — issue (non-blocking): idempotency relies on `written`, the last 10,000 ids. That is about 2,500 GGR facts. A `ToLedger` offset reset or rebuild redelivering older events counts them twice, and `has` scans the Vector on every command.
  Suggestion: idempotency on the source event's sequence per account, or a per-period id set.
  Resolved: the ledger is idempotent on the source event's subject and sequence, daf3829b.
- `api/Settlement.scala:166` — suggestion: a manual adjustment's id is a random UUID, so a POST retried after a timeout writes twice. This breaks the plan's "every command idempotent on a deterministic id".
  Suggestion: take an `Idempotency-Key` header (or a client `adjustmentId`) and derive the id from it.
  Resolved: an adjustment sent with `Idempotency-Key` is written once, on an id named from it, 73ed53e9.
- `api/Hosting.scala:179-180` — issue (non-blocking): the usage report's "commission recorded" reads only the ledger rows of periods ym−1..ym+1. A fact recorded this month for an older period (a late correction, an import) is left out, so the invoicing figure is understated.
  Suggestion: query by `recordedAt`, not by period.
  Resolved: the usage report counts commission by when it was recorded, 4f8bf2a5.

**Error handling and partial writes**

- `api/OperatorEndpoint.scala:239-243` — issue (non-blocking): approve writes `setParent` and `setOverride` before `Membership.approve`. Approving a membership that is not pending changes the account's parent and override, then answers 409.
  Suggestion: approve first, or check `m.status == Pending` before any write.
  Resolved: an approval the membership refuses is refused before any account write, 207fd1a1.
- `api/Wire.scala:50-55` — issue (non-blocking): `refused` maps every `CommandError` that is not Conflict, NotFound or Forbidden to 400, including timeouts and internal failures. A client then sees a server fault as its own mistake.
  Suggestion: map `Timeout`/`Unavailable` to 503 and anything unknown to 500.
  Resolved: Timeout and Unavailable answer 503, Unauthorized 401, anything unknown 500, 227cf89f.
- `api/Enrolment.scala:25-26` with `accounts/EmailIndex.scala:19-23` — issue (non-blocking): `open` claims the email index before the data key and the account are made. If either fails, the index names an account that does not exist. `lookup` filters that account out, the next application mints a new id, and `claim` refuses it for ever: the address can never register again.
  Suggestion: make the key and the account first and claim last, or let `claim` take over from a holder with no account.
  Resolved: an index entry naming an account never made is released by the next claim of the address, 2f67e7a1.
- `api/Hosting.scala:111-145` — issue (non-blocking): `add` makes the brand first and the webhook secret last. If any step in between fails, the brand exists without a secret, a retry answers `409 brand_exists`, and no route issues or rotates a secret, so FR-040's webhook can never be issued.
  Suggestion: make the steps re-runnable for a brand with no secret yet, or add a secret-rotation route for platform administrators.
  Resolved: an unfinished add runs again from where it stopped, 4f8bf2a5.
- `accounts/Provisioning.scala:85-99` — issue (non-blocking): when two brands' approvals race, `createUser` answers `AlreadyExists`. That becomes `None`, no roles are assigned, the consumer completes, and the affiliate has no realm user and nothing retries.
  Suggestion: on `AlreadyExists`, `findByEmail` again.
  Resolved: on AlreadyExists the user is found by email and given its roles, 32651be3.
- `accounts/Membership.scala:145-150` — issue (non-blocking): FR-029's "an operator's suspension stands over an ancestor's reactivation" holds only when the operator suspends first. If an ancestor suspended first, the operator's suspend is refused, and the ancestor can reactivate.
  Suggestion: allow an operator to suspend an ancestor-suspended membership, recording `suspendedBy = operator`, and test that order.
  Resolved: an operator may suspend over an ancestor's suspension, recorded as the operator's, 207fd1a1.
- `accounts/KeycloakAdmin.scala:90-113, 137, 158` — issue (non-blocking): every admin call fetches a new master-realm token with a password grant. That is load on Keycloak and looks like brute force. Also, `parse(body).get` and `.collect{…}.get` throw outside the `Either` on a non-JSON 200.
  Suggestion: cache the token until shortly before `expires_in`, and map parse failures to `RealmError.Failed`.
  Resolved: the admin token is kept until shortly before it expires, a 401 fetches another once, and a body that is not JSON is `RealmError.Failed`, e8df95dc.

**Leaks between brands and performance**

- `api/Networks.scala:98-118` (from `OperatorEndpoint.scala:85-93`) — issue (non-blocking): the operator's network tree and health at a brand list every descendant, with names decrypted, including subs that have no membership at that brand. A manager therefore sees accounts and names of other brands, which breaks FR-041.
  Suggestion: for the operator's reads, keep only the nodes with a membership at the brand. Decide whether a non-member ancestor link is shown at all.
  Resolved: a manager's network reads show only subs with a membership at the brand, and nothing below one without, d0677b98.
- `api/Backoffice.scala:194-227` — issue (non-blocking): O7 makes 2 calls per membership (account and secret) and decrypts one identity per membership, all unpaginated. A brand with thousands of affiliates makes thousands of calls per page load.
  Suggestion: paginate, and filter by the view's own columns before opening identities.
  Resolved: O7 answers a page (`limit` 100, at most 500, `offset`), filters on the view's and the account's fields first, and opens identities only for the page unless a search or a name sort needs them; routes.md O7 says so, aee5da6e.

**Tests**

- `port/Parity.scala:53-54, 68-73, 186, 254` and `port/CoverageSuite.scala:28, 46` — issue (non-blocking): beyond the blocking item:
  - **Wide waivers.**
    - Divergence 6 waives all of `$.payouts`.
    - Divergence 15 waives `$.period` on every path.
    - Divergence 17 waives all of `$` on audit.
    - Divergence 38 waives all of `$.players` at every step.
  - **Empty answers.** An expected `[]`/`{}` flattens to nothing, and array lengths are never compared. A leak into the read-only view or the deductions would pass.
  - **No check for stale divergences.** Nothing fails on a divergence no recording uses.
  - **Coverage.** CoverageSuite counts `>= 13` instead of requiring the set of recordings to equal the 13 `Def` names.

  Suggestion: narrow each divergence to its fields, emit a length leaf per array and a leaf for empty containers, fail on unused divergences, and assert set equality.
  Resolved: a length leaf per array and a leaf per empty object, six stale divergences removed, 17 narrowed to its seven fields, an unused divergence fails the suite, and both suites require exactly the driver's thirteen recordings, dca80e8b. The new leaves found two real differences, fixed in 20d42ca0.
- `features/CommunicationSteps.scala:86-90, 142-145` — issue (non-blocking): `customerIo.default = Left(…)` fails every attempt, not just the first. The "sent with the brand's sender configuration" scenario passes only because `FakeCustomerIo` logs failed attempts as sends.
  Suggestion: make only the first attempt fail and assert a successful send.
  Resolved: only the first attempt fails, a send records its answer, and the steps wait for a delivered send after a failed one, 30fa7d17.
- `it/LeakSuite.scala:104-105` — issue (non-blocking): `Sealing.open(…, new Array[Byte](32), …).isLeft` holds for any wrong key, so it proves nothing.
  Suggestion: capture the key before erasure and assert the store no longer returns it.
  Resolved: the key is captured before the erasure and the store asserted not to hold it after, 0132a60b.
- `it/ManagersSuite.scala:54, 158`, `it/StartSuite.scala:17-21`, `it/LatencySuite.scala:28-32`, `port/ImportParitySuite.scala:12-17, 69-73` — issue (non-blocking): four tests can pass while what they name is false.
  - `ManagersSuite`: `audited(...)` matches any earlier row on goldvibe in the shared database.
  - `StartSuite`: "claimed once" never asserts the duplicate count.
  - `LatencySuite`: the load thread's exception is swallowed, and the load can end before the probes start.
  - `ImportParitySuite`: "the import run again writes nothing" is never asserted, and `figureOf` matches any currency.

  Suggestion: filter `audited` on the plan or user id; assert the duplicate count as `IntakeSuite:20` does; capture the load thread's failure and start the probes while it runs; assert a second import's counts and pick the EUR figure.
  Resolved: all four, 30fa7d17 (ImportParitySuite reads the bundle's own currency, GBP, not EUR).
- `.github/workflows/ci.yml:89-95` — issue (non-blocking): the path filter leaves out `services/attribution/src/main/scala/io/eitheror/casino/attribution/{Types,Json}.scala` and `tag/**`, which `build.sbt:43-46` compiles into this service's tests. A change there does not run this job.
  Resolved: the filter names attribution's Types, Json and tag sources and the exporter, 5b7379f2.
- `tests/original/programme-export/bundle.go:258-265` and `export.go:179-192` — issue (non-blocking): two silent errors in the exporter.
  - A missing timestamp silently becomes the export's own time, which fabricates history in a production import.
  - `minor` assumes two decimals whatever `-currency` says, and wraps on overflow.

  Suggestion: make a missing time an error for facts, payouts and memberships; use the currency's exponent; check `IsInt64()`.
  Resolved: a missing time is refused, never dated now, and amounts take their currency's places and never wrap, e8ff6f57.

## Nits

- `intake/FactInbox.scala:51-54` — nitpick (non-blocking): the doc says "the first stands", but `outcome` overwrites a different outcome.
  Resolved: the doc says the last outcome stands, f7028434.
- `api/Passwords.scala:51` — nitpick (non-blocking): `catch case NonFatal(_) => ()` drops the failure without a log line. The answer must stay the same, but the operator should see why a reset did not send.
  Resolved: logged, the answer unchanged, f7028434.
- `links/BonusCode.scala:47, 70` — nitpick (non-blocking): `toUpperCase` with the default locale (use `Locale.ROOT`). Also, the same affiliate re-claiming its own live code gets `code_already_exists`, so a client retry is not idempotent.
  Resolved: Locale.ROOT, f7028434. Left: an affiliate re-claiming its own live code answers `code_already_exists`, as the original's.
- `activity/Activity.scala:71, 75` and `activity/FraudFlag.scala:96` — nitpick (non-blocking): `ORDER BY payload->>'at'` sorts ISO instants as text, which misorders events within a second.
  Left: nit.
- `commission/Commission.scala:140-141` — nitpick (non-blocking): `candidates` is inclusive and relies on callers passing `periodEnd − 1ms`. Several suites pass the exclusive end, and no test pins the midnight boundary.
  Left: nit.
- `commission/OriginalCasesSuite.scala` (~130, ~226, ~320) — nitpick (non-blocking): three cases assert arithmetic on literals and never call the code they are named for.
  Left: nit.
- `accounts/Secrets.scala:21-26` — nitpick (non-blocking): the 60-second cache never hits, because every caller builds a new `Secrets`. The doc and `invalidate()` mislead.
  Resolved: the doc says the cache lasts as long as its caller, which keeps a destroyed key from being read on another node, f7028434.
- `imports/Import.scala:77` — nitpick (non-blocking): `ledgers(b)` is recomputed for each item, O(ledgers × facts).
  Left: nit.
- `port/Translate.scala:284, 337, 385, 406, 435` — nitpick (non-blocking): unknown kinds become uncounted `Skip`s, and the revenue currency is the head of a map. Fail a recording that compares nothing.
  Left: nit.
- `port/ImportParitySuite.scala:28`, `it/LatencySuite.scala:16` — nitpick (non-blocking): `super.afterAll()` runs before `kit.stop()`.
  Resolved: kit.stop() runs first, 30fa7d17.
- `csv/Export.scala:43` — nitpick (non-blocking): the formula guard does not cover a leading `\r`.
  Resolved: a leading carriage return is guarded, f7028434.
- `Justfile` — nitpick (non-blocking): the `programme-run` comment says to export `ANKKA_SECRET_KEY`, but the recipe now generates it.
  Resolved: f7028434.
- `tests/port/programme/README.md` — nitpick (non-blocking): it says `import/` "will hold" the files and the suite "fails until both files are here", but both are committed.
  Resolved: f7028434.

## Questions

- `accounts/Membership.scala:153-155` — question: `activateInvited` moves rejected→active. FR-002 lists rejected→pending as the only exit from rejected. Should an invitation over a rejected membership be refused, or the exception be written into FR-002?
  Answered: allowed, as the original's invitation went to an address whose account was rejected (`AffiliateRoutes.scala:1112`); FR-002 now says so, 6b6720d9.
- `api/PortalEndpoint.scala:40-41` — question: through `asTarget`, an ancestor can PATCH a sub's `paymentMethod`. FR-029 lets an ancestor edit "account fields". Are payment details meant to be among them? If not, `Patching` should refuse identity and payment fields when the caller is not the account.
  Answered: yes. The original's `PATCH /network/{sub}` lets an ancestor set `paymentMethod`, `displayName`, `postbackUrl` and the threshold (`AffiliateRoutes.scala:804-811`).
- `accounts/Erasure.scala:64-78` — question: after erasure, the journal keeps `emailDigest` under a service-wide key that is never destroyed, so a guessed email can be confirmed. Do backups and Secret Manager versions of the data key count as "destroyed"? Separately, `RealmError.NotConfigured` is swallowed in the erase step, so a misconfigured production erasure deletes no realm user and nothing reports it. Is that intended?
  Answered: the digest in the journal stays until ankka 042 gives the journal a retention (research R9); a destroyed key is the secret store's to forget, its versions with it, and backups wait for 042 as the journal does. Resolved: a realm that is not configured no longer counts as a user deleted, 03c4a35b.
- `imports/Import.scala:104, 107` — question: re-importing an erased account mints a new data key and re-points the released email index at the erased account. Should the import skip erased accounts?
  Resolved: an import leaves an account the programme holds as it is, an erased one too, 594df0d8.
- `activity/Note.scala` and the imported audit's `before`/`after` — question: these are stored in clear and untouched by erasure. Does the exporter strip identities from them?
  Answered: a note is a manager's text, not one of FR-037's fields, and is kept as the original kept it. The bundle's audit carries no before or after, only details, from which the exporter strips the identity; it now strips the payment method, website and traffic source too, 7d9b6834.
- `postbacks/Postback.scala:63-91` — question: Postback sets no step timeout, while one attempt can take 20 s or more (10 s connect and 10 s request). Is the default step timeout above that?
  Answered: yes, the default step timeout is 30 s (`Workflow.scala:76` of ankka's SDK), above an attempt's 20 s.
- `settlement/Close.scala:40` — question: a rejected payout's claimed facts stay stamped for ever. Is rejection meant to forfeit them? If so, say it in FR-019.
  Answered: yes, a rejected payout is frozen as a paid one is (research T056); FR-019 now says so, 6b6720d9.
- `api/Settlement.scala:139-151` — question: an override figure appears under `adjustments`, not `overrideCommission`, in O41 and in the export, so finance's CSV differs from the original field by field. Is this divergence recorded?
  Answered: yes, spec change 14 (the override figure as an adjustment fact), marked in tests/port/programme/README.md; the export's header is the original's.
- `ledger/Ledger.scala:54, 57` — question: does ankka's keyed view guarantee that an `updateRows` earlier in the same batch is visible to `change.rows.ask(ofPayout)`? `LedgerViewSuite` stubs it.
  Answered: yes. A keyed view handles one change at a time, each in its own transaction under the view's lock, committed before the next (`KeyedViewHandlers.scala:23-33`), so a later change's ask sees it; ImportSuite's paid rows show it.
- Recording names — question: "… a manager for another brand cannot see it" has no other-brand step, and "adding, resetting or revoking a manager" has no revoke step. Rename them, or move those halves to `NotDriven`?
  Answered: a recording is named as its feature's scenario (CoverageSuite). The other-brand half cannot be driven, since the original serves one brand per deployment, and it has no route that revokes a manager (`scenarios.go:432`); MembershipsSuite and ManagersSuite hold both halves.

## Praise

- `sealing/Sealing.scala`, `sealing/Digest.scala` — praise: AES-256-GCM with a fresh 96-bit nonce and the affiliate id as associated data, and a keyed HMAC digest for email lookups. Erasure as key destruction is a clean answer to FR-037 ahead of ankka 042.
- `accounts/Token.scala`, `accounts/EmailIndex.scala`, `links/BonusCode.scala` — praise: an entity per hashed token, digest or upper-cased code makes the single claim atomic. That fixes races the original admitted.
- `api/Hosting.scala:86-95` — praise: the webhook HMAC is over the raw bytes, compared in constant time, and the header fact id is cross-checked against the body.
- `network/Network.scala`, `commission/` — praise: the override walk and the HALF_UP basis-point rounding match the original case for case, and the period arithmetic is DST-correct in the brand's zone.
- `tests/original/programme-driver/` — praise: the driver refuses non-local hosts and records no credentials, and its offline test checks that. The recordings hold only `@example.com` data.

## Checks applied

- **General groups applied:** all of them. Data and migrations was applied to persisted event and state types and the journal; there is no SQL DDL in the diff beyond the vendored ankka schema.
- **Language skills:**
  - `pr-review-scala` (bundled; the repository has no skill of its own). Applied: concurrency and actors (event handler purity, entities making no calls), serialization (codecs in companions via `ProgrammeCodecs`), totality, error handling, collections, tests and build.
  - No Go skill was loaded: the Go files are test tooling, reviewed against the general checklist and the Security group.
- **Project rules read:**
  - `CLAUDE.md` (root and workspace); `.specify/memory/constitution.md` (principles I to VI);
  - spec.md, plan.md, tasks.md (97 of 97 tasks marked done), research.md, data-model.md and contracts/.
  - The repository has no `.claude/rules/` and no `REVIEW.md`.
- **Tooling:** `-deprecation -feature -Wunused:all`, with no `-Werror` and no scalafmt configuration. Formatting was not reviewed; unused-code warnings were not re-reported.
- **Tests:** not run. The suites that matter here (integration, parity, features) need Docker and a Postgres, and the unit suites alone would not settle any finding above.
