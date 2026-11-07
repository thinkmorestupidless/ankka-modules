package io.eitheror.casino.engagement.config

/** Research R12: thirty-two identify+track pairs at `_seed_<slug>`, the original's attributes and data. */
class SeedSuite extends munit.FunSuite:
  private val pairs = Seed.pairs("spinvibe")

  test("thirty-two triggers, each once, in the original's CrmEvent order"):
    assertEquals(Seed.triggers.size, 32)
    assertEquals(Seed.triggers.distinct, Seed.triggers)
    assertEquals(Seed.triggers.take(4), Vector("PlayerRegistered", "DepositConfirmed", "PaymentFailed", "BonusGranted"))
    assertEquals(Seed.triggers.takeRight(2), Vector("ConsentUpdated", "BankAccountAdded"))
    assertEquals(Seed.triggers(10), "KycAwaitingDeposit")

  test("thirty-two pairs, one per trigger, each sent as its event name"):
    assertEquals(pairs.map(_.trigger), Seed.triggers)
    assertEquals(pairs.find(_.trigger == "DepositConfirmed").map(_.event), Some("deposit_confirmed"))
    assertEquals(pairs.find(_.trigger == "RgLimitLoosened").map(_.event), Some("rg_limit_loosened"))
    assertEquals(pairs.map(_.event).distinct.size, 32)
    assertEquals(Seed.eventName("KycApproved"), Some("kyc_approved"))
    assertEquals(Seed.eventName("CoolingOffRemoved"), None)

  test("the person is _seed_<slug>"):
    assertEquals(Seed.person("spinvibe"), "_seed_spinvibe")

  test("every pair identifies with the original's nineteen attributes, under the brand"):
    val attrs = pairs.head.attributes.toMap
    assertEquals(pairs.head.attributes.size, 19)
    assertEquals((attrs("email"), attrs("brand_id"), attrs("currency"), attrs("total_balance")),
      ("seed@spinvibe.example.com", "spinvibe", "USD", "135.00"))
    assert(pairs.forall(_.attributes == pairs.head.attributes))

  test("each trigger's data is the original's"):
    def data(t: String) = pairs.find(_.trigger == t).get.data
    assertEquals(data("PaymentFailed").toMap,
      Map("amount" -> "100.00", "currency" -> "USD", "transaction_id" -> "seed-txn-001", "failure_reason" -> "Payment declined"))
    assertEquals(data("GameSessionEnded").toMap,
      Map("game_id" -> "seed-game-001", "session_id" -> "seed-session-001", "session_duration_minutes" -> "30"))
    assertEquals(data("PasswordResetRequest"),
      Vector("reset_link" -> "https://example.com/reset-password?token=seed&playerId=seed-player"))
    assertEquals(data("ConsentUpdated").toMap,
      Map("email_subscribed" -> "true", "sms_subscribed" -> "false", "consent_source" -> "player_profile"))
    Vector("PlayerLoggedIn", "WageringRequirementMet", "SelfExclusionActivated", "KycApproved")
      .foreach(t => assertEquals(data(t), Vector.empty, t))
    assertEquals(pairs.count(_.data.isEmpty), 4)

  test("the track sends the attributes with the trigger's data over them"):
    val bonus   = pairs.find(_.trigger == "BonusGranted").get
    val tracked = bonus.tracked.toMap
    assertEquals(bonus.tracked.size, 19 + 2)
    assertEquals((tracked("amount"), tracked("bonus_name"), tracked("currency"), tracked("email")),
      ("50.00", "Welcome Bonus", "USD", "seed@spinvibe.example.com"))
    assertEquals(bonus.tracked.map(_._1).distinct.size, bonus.tracked.size)
