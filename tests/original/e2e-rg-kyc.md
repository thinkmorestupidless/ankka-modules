# Responsible-gambling and KYC scenarios for the original's suite

`e2e-rg-kyc.patch` adds four files to the original's Go end-to-end suite (`eitheror-platform/tests/e2e`,
build tag `e2e`, package `e2e_test`). Before it, the suite skipped every responsible-gambling and KYC
scenario, so `responsible-gambling` (spec 010) and `kyc` (spec 009) had nothing to be recorded against.
Spec 001 FR-030 requires these scenarios to be in the Go suite and run against the original before
either service is recorded.

The patch only adds files. It reuses the suite's own helpers: `setup.SkipIfMissing`, player registration
and sign-in from scenarios 14, 16 and 19, and the operator token from scenario 18. Operator tests skip
without `TEST_OPERATOR_EMAIL` / `TEST_OPERATOR_PASSWORD`, as the suite's other operator tests do.
All requests go through `http.DefaultClient`, so `e2e-recording.patch` records them.

| File | Tests | Drives |
|---|---|---|
| `scenario26_rg_limits_e2e_test.go` | 13, plus the `rk*` helpers shared by 26–29 | `RgRoutes` limits (player); `BackofficePlayerRoutes` `rg/limits` (operator) |
| `scenario27_rg_breaks_exclusion_e2e_test.go` | 24 | `RgRoutes` `cooling-off`, `permanent-exclusion`, `history`; operator `rg/cooling-off`, `self-exclude`; `login`; `sessions/{brand}/launch`; internal `notify/deposit` |
| `scenario28_kyc_documents_review_e2e_test.go` | 24 | player and operator `/{brand}/players/{player}/kyc/...` (upload-url, documents, view-url, approve, reject, verification-mode, status, required-doc-types, verify, reject); backoffice `profile` PATCH |
| `scenario29_kyc_policy_gates_e2e_test.go` | 13 | `backoffice/{brand}/kyc-policy` GET/PUT/profile; public `players/{brand}/kyc/requirements`; manual override `backoffice/{brand}/{player}/kyc/approve`; the deposit gate (`notify/deposit`) and the play gate (`launch`) |

There are 74 tests. 50 run in full. 12 skip in full, and 12 more run but skip one or more subtests
(13 subtests in all) for the part the original lacks.

## Test → feature scenario

Run means the test asserts what the original answers. Part means the test runs, and the named
part is a skipped subtest. Skip means the whole test skips as an original defect. Feature files
are under `features/responsible-gambling/` and `features/kyc/`.

### Responsible gambling

| Test | Feature · scenario | |
|---|---|---|
| `TestScenario26_RGLimits_FirstDailyDepositLimitAppliesAtOnce` | limits · a first daily deposit limit applies at once… | run |
| `TestScenario26_RGLimits_LoweringAppliesAtOnceAndCancelsPending` | limits · lowering a deposit limit applies at once… | run |
| `TestScenario26_RGLimits_RaisingKeepsOldLimitAndStagesNewOne` | limits · raising a deposit limit keeps the old one… | part: the new limit applying after 24h |
| `TestScenario26_RGLimits_RemovingKeepsLimitForADay` | limits · removing a deposit limit keeps it for a day… | part: the removal applying after 24h |
| `TestScenario26_RGLimits_NewRaiseReplacesPendingChange` | limits · a new raise while one is pending replaces… | run |
| `TestScenario26_RGLimits_SessionLimitBelowMinimumRefused` | limits · a session limit below the brand's minimum is refused… | run |
| `TestScenario26_RGLimits_SessionLimitAtMinimumAccepted` | limits · a session limit at the brand's minimum is accepted | run |
| `TestScenario26_RGLimits_SessionLimitNotPositiveWholeNumberRefused` | limits · (outline) a session limit that is not a positive whole number… | run |
| `TestScenario26_RGLimits_WeeklyLossLimitShowsWithLossesOfLastWeek` | limits · a weekly loss limit shows with the losses of the last week… | run (the read only, no bet) |
| `TestScenario26_RGLimits_LimitChangeRecordedInHistory` | limits · a limit change by the player is recorded in the history… | skip |
| `TestScenario26_RGOperator_SetLimitWithPendingRaiseAppliesAtOnce` | operators · an operator setting a limit with a pending raise… | part: pending cancelled; history names operator |
| `TestScenario26_RGOperator_SessionLimitBelowMinimumAccepted` | operators · an operator's session limit below the brand's minimum is accepted… | run |
| `TestScenario26_RGOperator_RemovingLimitLeavesNoLimitAtOnce` | operators · an operator removing a limit leaves no limit… | run |
| `TestScenario27_RGBreaks_PlayerBreakAppliesAtOnceAndOpensCase` | breaks · a break set by the player applies at once, opens a case… | run (the case through the history read) |
| `TestScenario27_RGBreaks_PlayerCannotEndBreak` | breaks · a player asking to end or shorten their break is refused | part: shortening |
| `TestScenario27_RGBreaks_BreakEndsByItself` | breaks · a break ends by itself when its end passes… | skip |
| `TestScenario27_RGBreaks_OperatorLiftsBreak` | breaks · an operator lifting a break with a reason ends it… | part: case closed naming operator and reason |
| `TestScenario27_RGBreaks_PlayerSelfExcludesPermanently` | breaks · a player excluding themselves permanently… | part: game sessions ended |
| `TestScenario27_RGBreaks_OperatorExclusionUnder183DaysRefused` | breaks · an operator's exclusion shorter than 183 days is refused… | run |
| `TestScenario27_RGBreaks_OperatorExclusionOf183DaysOrPermanentAccepted` | breaks · (outline) an operator's exclusion of 183 days or permanent… | run |
| `TestScenario27_RGBreaks_ReinstatementBeforePeriodEndRefused` | breaks · a reinstatement before an exclusion's period ends is refused… | skip |
| `TestScenario27_RGBreaks_ReinstatementAfterPeriodEndAccepted` | breaks · a reinstatement after an exclusion's period ends is accepted… | skip |
| `TestScenario27_RGBreaks_ReinstatementOfPermanentSelfExclusionRefused` | breaks · a reinstatement of a permanently self-excluded player is refused | skip |
| `TestScenario27_RGBreaks_ExcludedAndOnBreakAnsweredWithExclusion` | breaks · a player both excluded and on a break is answered with the exclusion | run (launch and deposit) |
| `TestScenario27_RGBreaks_BreakEndingOnSuspendedPlayerLeavesThemSuspended` | breaks · a break ending on a suspended player leaves them suspended | skip |
| `TestScenario27_RGPlay_SignInOnBreakRefused` | play · a sign-in by a player on a break is refused… | skip |
| `TestScenario27_RGPlay_SignInSelfExcludedRefused` | play · a sign-in by a self-excluded player is refused… | run |
| `TestScenario27_RGPlay_LaunchOnBreakRefused` | play · a launch for a player on a break is answered cooling off… | run |
| `TestScenario27_RGPlay_BreakEndedRefusesNothing` | play · a player whose break ended is refused nothing for the break | skip |
| `TestScenario27_RGDeposits_OverDailyLimitRefused` | deposits · a deposit over the daily deposit limit is refused naming… | part: no balance changes |
| `TestScenario27_RGDeposits_ExactlyAtRemainingAccepted` | deposits · a deposit exactly at what remains… is accepted | run |
| `TestScenario27_RGDeposits_MinorUnitOverRemainingRefused` | deposits · a deposit a minor unit over what remains… is refused | run |
| `TestScenario27_RGDeposits_FirstLimitPassedIsNamed` | deposits · a deposit that would pass any of the daily, weekly or monthly… | run |
| `TestScenario27_RGDeposits_OnBreakRefused` | deposits · a deposit for a player on a break is refused as cooling off… | run (the end is not named) |
| `TestScenario27_RGDeposits_SelfExcludedRefused` | deposits · a deposit for a self-excluded player is refused as excluded | run |
| `TestScenario27_RGDeposits_AfterBreakEndedNotRefused` | deposits · a deposit for a player whose break ended is not refused… | skip |
| `TestScenario27_RGDeposits_RefusedDepositRecordedCapturedNotCredited` | deposits · a deposit refused for a limit is recorded captured and not credited… | skip |

### KYC

| Test | Feature · scenario | |
|---|---|---|
| `TestScenario28_KYCDocuments_NoDocumentsShowsRequiredCategories` | documents · a player with no documents sees all six categories… | run |
| `TestScenario28_KYCDocuments_UploadAddressValidForAnHourUnderPlayersPrefix` | documents · asking to upload a file for a category gives an upload address valid for an hour… | run |
| `TestScenario28_KYCDocuments_ConfirmedUploadIsPendingAndLocked` | documents · confirming a completed upload shows the category pending review and locks… | run |
| `TestScenario28_KYCDocuments_PendingOrApprovedCategoryRefusesAnotherUpload` | documents · (outline) a category whose latest document is pending or approved refuses… | run |
| `TestScenario28_KYCDocuments_CategoryOtherNeverLocked` | documents · the category other is never locked… | run |
| `TestScenario28_KYCDocuments_ReplacementForRejectedIsPending` | documents · a replacement for a rejected document shows the category pending review… | run |
| `TestScenario28_KYCDocuments_RejectedReasonIsAReasonCode` | documents · a rejected document's reason is shown as one of the reason codes… | run |
| `TestScenario28_KYCDocuments_PlayerViewsOwnDocument` | documents · a player viewing one of their documents is given a view address valid for fifteen minutes… | part: every view recorded |
| `TestScenario28_KYCDocuments_PlayerViewingAnotherPlayersDocumentRefused` | documents · a player asking to view another player's document is refused | run |
| `TestScenario28_KYCDocuments_FileOfUnacceptedTypeOrSizeRefused` | documents · (outline) a file of a type or size the brand does not take is refused… | skip |
| `TestScenario28_KYCReview_ApprovePendingDocumentSecondReviewRefused` | review · approving a pending document records the operator and time… | part: reviewer recorded |
| `TestScenario28_KYCReview_RejectWithKnownReasonCode` | review · rejecting a pending document with a known reason code records the code | run |
| `TestScenario28_KYCReview_RejectWithUnknownReasonCodeRefused` | review · rejecting a document with an unknown reason code is refused | run |
| `TestScenario28_KYCReview_AutomaticModeVerifiesWhenRequiredApproved` | review · in automatic mode an account whose required categories are approved… becomes verified… | run |
| `TestScenario28_KYCReview_AutomaticModeOnlyIdentityApprovedStaysPending` | review · in automatic mode an account with only the identity front approved stays pending | run |
| `TestScenario28_KYCReview_RejectedLatestDocumentRejectsAccount` | review · a rejected latest document in any category makes the account rejected… | run |
| `TestScenario28_KYCReview_RejectedOtherDoesNotRejectAccount` | review · a rejected document in the category other does not reject the account | run |
| `TestScenario28_KYCReview_IncompleteProfileStaysPending` | review · (outline) every required category approved but an incomplete profile is pending… | run (missing-field row) |
| `TestScenario28_KYCReview_ManualModeApprovingChangesNothing` | review · in manual mode approving every required document changes nothing | run |
| `TestScenario28_KYCReview_ManualModeOperatorSetsVerified` | review · in manual mode the operator setting the account verified verifies it at once | run |
| `TestScenario28_KYCReview_SetVerifiedWithIncompleteProfileRefused` | review · setting an account verified while the profile is incomplete is refused… | run |
| `TestScenario28_KYCReview_MarkingCategoryRequiredForOnePlayer` | review · marking a category required for one player puts the account in manual mode… | run |
| `TestScenario28_KYCReview_OperatorViewsDocument` | review · (outline) an operator with a compliance or admin role… views a document… | run (with TEST_OPERATOR's role) |
| `TestScenario28_KYCReview_OperatorVerifiesOrRejectsDirectly` | review · (outline) an operator verifying or rejecting an account directly… | run |
| `TestScenario29_KYCPolicy_BrandWithNoPolicyReadsCuracaoDefault` | policy · a brand with no KYC policy reads the Curaçao default | run (requirements read only) |
| `TestScenario29_KYCPolicy_ApplyingUKGCProfile` | policy · applying the UKGC profile sets its gates… | run |
| `TestScenario29_KYCPolicy_ApplyingMGAProfile` | policy · applying the MGA profile sets its thresholds… | run |
| `TestScenario29_KYCPolicy_ChangingOneFieldChangesOnlyThatField` | policy · changing one field of a profile's policy changes only that field and marks the profile custom | part: profile reads custom |
| `TestScenario29_KYCPolicy_UnknownProfileRefused` | policy · applying an unknown licence profile is refused… | run |
| `TestScenario29_KYCPolicy_UnauthenticatedRequirementsRead` | policy · an unauthenticated client asking the brand's requirements… | run |
| `TestScenario29_KYCGates_DepositGateRefusesUnverifiedPendingRejected` | gates · (outline) a brand whose deposit gate is verified refuses a deposit… | run |
| `TestScenario29_KYCGates_DepositOverThresholdRefused` | gates · a deposit that would take an unverified player over the deposit threshold… | part: flagged for review |
| `TestScenario29_KYCGates_DepositUnderThresholdAllowed` | gates · a deposit that keeps an unverified player under the deposit threshold is allowed | run |
| `TestScenario29_KYCGates_DepositOverSofThresholdRefused` | gates · a deposit that would take a player whose source of funds is not cleared over the threshold… | part: flagged for review |
| `TestScenario29_KYCGates_DepositOverSofThresholdClearedAllowed` | gates · a deposit over the source of funds threshold by a player whose source of funds is cleared… | skip |
| `TestScenario29_KYCGates_PlayGateRefusesUnverified` | gates · a brand whose play gate is verified refuses play by an unverified player… | run |
| `TestScenario29_KYCGates_PlayAllowedForVerifiedOrGateNone` | gates · (outline) play is allowed for a verified player or on a brand whose play gate is none | run (asserted as not refused by the gate) |

## What skips as an original defect, and why

The citations are spec 001 *Known defects of the original fixed by the port*, and the *Changes from
the original* sections of specs 010 and 009.

- **No deposit is refused before it is credited** (spec 001; 010). The limit, cooling-off and exclusion
  checks run on the internal `notify/deposit` route. `deposit-on-payment-capture-fn` calls that route
  after the wallet has credited, and its 403 is only logged. The tests record that route's refusals.
  "No balance changes" and "recorded captured, not credited" skip.
- **A staged loosening never applies** (spec 001; 010). `rg-pending-apply` is not registered. The
  pending stage is recorded, and the "after 24 hours" parts skip.
- **A break never ends by itself, and reinstating leaves `rg_is_cooling_off` set** (spec 001; 010).
  Skipped: break ends by itself, break ended refuses nothing (sign-in, play, deposit), break ending
  on a suspended player, and reinstatement after the period.
- **A period exclusion's period is never read** (spec 001; 010). Reinstatement before the end is never
  refused. The reinstate route also checks no exclusion, so reinstating a permanent self-exclusion
  is not refused either.
- **Sign-in on a break is not refused by casino-service** (spec 010 Context, *Login*). Its login
  refuses only `self_excluded`, `suspended` and `blocked`. The RG Keycloak authenticator is wired by
  hand into the browser flow only.
- **History lists RGCase twins only**, with no limit changes and no actors (010). This skips the
  player's limit-change history and the operator being named. The operator limit route also leaves
  the player's pending change in place, which skips "pending change cancelled".
- **Break bounds and case reasons** (010). The player can shorten a break, because any `until` is
  accepted. The operator lift takes no reason and closes no case. Self-exclusion ends no game
  session.
- **Source of funds can never be cleared** (spec 001; 009). The cleared-and-allowed scenario skips.
- **Only the latest document view is audited** (spec 001; 009). `kyc_view_log` is overwritten.
- **Upload type and size limits** (009). `upload-url` maps any content type to `.jpg`. The 5 MiB limit
  was the player site's ingress.
- Not in the defect lists, cited from the code instead:
  - A document approval records no reviewer (`applyApproval`).
  - The policy PUT keeps `licenceProfile` unless the caller sends one, so the profile never reads "custom".
  - The threshold and source-of-funds review flags are action proposals on the reasoner's topic,
    not an HTTP read (009: review proposals become a queue).

## How the tests stand in for the feature's numbers

- **Deposit limits.** The feature has a limit of 100 with 80 already deposited. The tests use a limit
  of 20 with nothing deposited. Twenty remains either way, and a fresh player needs no wallet
  deposits seeded.
- **Thresholds.** The feature has lifetime deposits of 190000 plus 20000. The tests use lifetime
  deposits of 0 and a deposit of `2000.01` (200001 minor units).
- **The brand's policy is shared.** Every test that changes it snapshots the policy first and
  restores it on cleanup. RG tests that use launch or deposit first make sure the policy has no
  gate or threshold, because the original checks KYC before RG on both paths.
- **Direct verify and reject use players with no documents.** The original's `kyc-status-rollup`
  recomputes the status on every player write within 15 minutes of a document change. A direct
  decision taken just after an upload is overwritten by it.

## Not covered

These scenarios have no test. Either the original has no HTTP surface to drive, or driving it
needs set-up outside this patch:

- Session-limit launch answers. These need `HAS_SESSION` edges and session twins seeded.
- Bets refused as locked. These need the ST8 adapter signing from scenario 15.
- The RG-unreachable answers.
- Reality checks and facts.
- Case notes and audits.
- The brand minimum session limit change.
- KYC queue and tab reads, the verification window, retention, and the profile-change downgrade.
- Lifetime deposits growing, and a tightened policy flagging players.
- An operator of another brand.
- The legacy-identity and "pending among approved" derivation rows.

## Environment the tests expect

- **URLs.** `EITHEROR_CASINO_URL` serves `/v1/casino/*`. `EITHEROR_CASINO_INTERNAL_URL` is the
  casino-service port that serves `/internal/v1/casino/*/notify/deposit`.
- **Internal secret.** `EITHEROR_WALLET_INTERNAL_SECRET` must equal casino-service's
  `WALLET_SERVICE_INTERNAL_SECRET`. The default is `dev-wallet-secret`.
- **Keycloak admin.** The admin password clears email verification on fresh players. Set
  `EITHEROR_KEYCLOAK_ADMIN_PASSWORD`.
- **Operator.** `TEST_OPERATOR_EMAIL` / `TEST_OPERATOR_PASSWORD` must belong to an operator in the
  `eitheror` realm with `brand_admin` (or `brand_compliance`) on the casino brand. The brand is
  `EITHEROR_CASINO_BRAND_ID`, default `spinvibe`.
- **MinIO.** casino-service needs it configured for the upload and view addresses.
- **TL function.** `kyc-status-rollup` must be registered for the automatic-mode derivation tests
  (`just kind-register-casino-functions`).

## Applying it

Apply it only in a local checkout of the original, on a local branch. Never push it.

```bash
cd ~/Documents/projects/eitheror/eitheror-platform
git switch -c local/recording
git apply ../ankka-casino/tests/original/e2e-rg-kyc.patch          # with e2e-recording.patch when recording
cd tests/e2e && go vet -tags e2e ./... && EITHEROR_SUBSTRATE_URL=… go test -tags e2e -count=1 -run 'Scenario2[6-9]' ./...
```

Without `EITHEROR_SUBSTRATE_URL`, the suite skips itself, as it always has. These tests have
compiled and vetted, but they have not yet run against a live original. The first run is the check
that the routes answer as written here.
