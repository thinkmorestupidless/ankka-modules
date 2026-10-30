package io.eitheror.casino.kyc.record

import io.eitheror.casino.kyc.policy.{Gate, Profiles}

/** data-model.md's deposit check, row by row in its order, and the play check, with R5's messages. */
class GatesSuite extends munit.FunSuite:
  private val gated = Profiles.Curacao.copy(depositGate = Gate.KycApproved, playGate = Gate.KycApproved)
  private val gbp   = Profiles.Curacao.copy(currency = Some("GBP"), depositThreshold = Some(200000L), sofThreshold = Some(500000L))
  private val GBP   = Some("GBP")

  private def deposit(
      policy: io.eitheror.casino.kyc.policy.Policy = gbp,
      status: Status = Status.Unverified,
      sof: Sof = Sof.Unverified,
      lifetime: Long = 0L,
      currency: Option[String] = GBP,
      amount: Long = 5000L
  ) = Gates.deposit(policy, status, sof, lifetime, currency, amount)

  test("1. the deposit gate refuses an account not verified, with the status's message"):
    val rows = Vector(
      Status.Unverified -> "Please verify your identity before depositing",
      Status.Pending    -> "Your identity documents are under review",
      Status.Rejected   -> "Your identity documents were not accepted — please re-submit"
    )
    for (status, message) <- rows do
      assertEquals(deposit(gated, status), GateAnswer(false, Some("DEPOSIT_BLOCKED_KYC"), Some(message)), status)
    assertEquals(deposit(gated, Status.Verified), Gates.Allowed)

  test("DEPOSIT_BLOCKED_KYC comes before the currency rule"):
    assertEquals(deposit(gated.copy(currency = GBP, depositThreshold = Some(1L)), currency = Some("EUR")).code, Some("DEPOSIT_BLOCKED_KYC"))

  test("2. a currency other than the KYC currency, or none, is refused while the policy has either threshold"):
    val refusal = GateAnswer(false, Some("DEPOSIT_BLOCKED_KYC_CURRENCY"), Some("Deposits in this currency are not accepted until your account is verified"))
    assertEquals(deposit(currency = Some("EUR")), refusal)
    assertEquals(deposit(currency = None), refusal)
    assertEquals(deposit(gbp.copy(depositThreshold = None), currency = Some("EUR")), refusal)
    assertEquals(deposit(gbp.copy(sofThreshold = None), status = Status.Verified, currency = Some("EUR")), refusal)

  test("2. with neither threshold the currency is not compared"):
    assertEquals(deposit(Profiles.Curacao, currency = Some("EUR")), Gates.Allowed)
    assertEquals(deposit(Profiles.Curacao, currency = None), Gates.Allowed)

  test("3. the deposit threshold: exactly at it allowed, a minor unit over refused and flagged"):
    assertEquals(deposit(lifetime = 195000L, amount = 5000L), Gates.Allowed)
    assertEquals(
      deposit(lifetime = 195000L, amount = 5001L),
      GateAnswer(
        false,
        Some("DEPOSIT_BLOCKED_KYC_THRESHOLD"),
        Some("Your account requires identity verification before further deposits"),
        Some(FlagReason.Threshold)
      )
    )

  test("3. a verified account passes the deposit threshold"):
    assertEquals(deposit(status = Status.Verified, lifetime = 300000L, amount = 1000L), Gates.Allowed)

  test("4. the source of funds threshold: exactly at it allowed, a minor unit over refused and flagged, cleared passes"):
    val verified = Status.Verified
    assertEquals(deposit(status = verified, lifetime = 495000L, amount = 5000L), Gates.Allowed)
    assertEquals(
      deposit(status = verified, lifetime = 495000L, amount = 5001L),
      GateAnswer(
        false,
        Some("DEPOSIT_BLOCKED_SOF"),
        Some("Your account requires a source of funds review before further deposits"),
        Some(FlagReason.SourceOfFunds)
      )
    )
    assertEquals(deposit(status = verified, sof = Sof.Cleared, lifetime = 495000L, amount = 5001L), Gates.Allowed)

  test("the deposit threshold before the source of funds threshold"):
    assertEquals(deposit(lifetime = 600000L).code, Some("DEPOSIT_BLOCKED_KYC_THRESHOLD"))

  test("5. otherwise allowed"):
    assertEquals(deposit(), Gates.Allowed)

  test("the play check: the gate refuses an account not verified, with the status's message"):
    val rows = Vector(
      Status.Unverified -> "Please verify your identity before playing",
      Status.Pending    -> "Your identity documents are under review",
      Status.Rejected   -> "Your identity documents were not accepted — please re-submit"
    )
    for (status, message) <- rows do assertEquals(Gates.play(gated, status), GateAnswer(false, Some("PLAY_BLOCKED_KYC"), Some(message)), status)
    assertEquals(Gates.play(gated, Status.Verified), Gates.Allowed)
    assertEquals(Gates.play(Profiles.Curacao, Status.Unverified), Gates.Allowed)
