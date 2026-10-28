package io.eitheror.casino.wallet.port

/**
 * The original's end-to-end recordings that reach the wallet through another service (spec 002
 * *Recordings*; research R13). They replay against those services, which call the wallet; until the
 * services exist they are listed here, reported ignored with what blocks them, never deleted.
 */
object Blocked:
  private val gameProvider = "spec 003 (game-provider)"
  private val rewards      = "spec 011 (rewards)"
  private val payouts      = "spec 007 (payouts)"
  private val players      = "spec 008 (players) and spec 006 (ingest)"
  private val attribution  = "spec 012 (attribution)"

  private val st8 = Vector(
    "BasicBalance", "BasicDebit", "BasicCredit", "LargeWinOnLargeStake", "BasicCancel", "BasicBuyin", "BasicPayout", "ZeroDebit",
    "NotEnoughMoney", "ZeroBalanceFreshSpinDeclined", "ZeroCredit", "StandaloneCredit", "BigRoundMultipleDebits",
    "BigRoundMultipleCredits", "BigRoundMultipleCancels1", "BigRoundMultipleCancels2", "BigRoundFailedDebit", "IdempotentDebit",
    "IdempotentCredit", "IdempotentCancel", "BalanceWithoutToken", "LimitedCancel", "WrongSignature", "NewParams", "TableSwitch",
    "CorrectionDebitNegativeBalance", "Resettlement", "StandaloneCancel", "CancelEverythingWithinRound"
  ).map(n => s"TestScenario15_St8Wallet_$n" -> gameProvider)

  /**
   * Spec 025's pool tests (research R7): they enter through the ST8 adapter and assert on the
   * original's affiliate GGR facts, and record only with the original's affiliate stack running.
   */
  private val pools = Vector(
    "SplitRoundEarnsRealGGROnly", "ConvertedMoneyRoundEarnsNoAffiliateGGR", "MixedPoolsLossEarnsDepositShareOnly",
    "MixedPoolsWinEarnsDepositShareOnly", "CancelledRoundRestoresPoolsAndEarnsNothing"
  ).map(n => s"TestScenario15_St8Wallet_$n" -> s"$gameProvider, $attribution")

  private val resolver = Vector(
    "real_only_legs_and_concept", "bonus_active_conversion", "rules_by_seeding", "unmigrated_guard", "cutover_settlement",
    "two_records_priority_order"
  ).map(n => s"TestScenario22_FundingResolver/$n" -> gameProvider)

  private val casinoApi = Vector(
    "TestScenario11_CasinoHTTPAPIGate3_DepositUpdatesBalance"      -> players,
    "TestScenario11_CasinoHTTPAPIGate3_DepositIdempotent"          -> players,
    "TestScenario11_CasinoHTTPAPIGate3_ProviderBetDeductsBalance"  -> gameProvider,
    "TestScenario11_CasinoHTTPAPIGate3_ProviderBetInsufficientFunds" -> gameProvider,
    "TestScenario11_CasinoHTTPAPIGate3_ProviderWinCreditsBalance"  -> gameProvider,
    "TestScenario11_CasinoHTTPAPIGate3_ProviderRollbackSettledRound" -> gameProvider,
    "TestScenario14_CasinoDeposit_PlayerToWallet"                  -> players,
    "TestScenario7_CasinoGate3_FullPlayerJourney"                  -> s"$gameProvider, $payouts",
    "TestScenario7_CasinoGate3_InsufficientFunds"                  -> gameProvider,
    "TestScenario19_BonusWageringCompletesToRealMoney"             -> s"$rewards, $gameProvider",
    "TestScenario16_RewardGrant (2)"                               -> rewards,
    "TestScenario23_RewardGrantExpiry"                             -> rewards,
    "TestScenario244 (4)"                                          -> s"$rewards, $payouts",
    "TestScenario245 (3)"                                          -> s"$rewards, $payouts",
    "TestScenarioFreeSpinsPlay (4)"                                -> s"$rewards, $gameProvider"
  )

  val recordings: Vector[(String, String)] = st8 ++ pools ++ resolver ++ casinoApi
