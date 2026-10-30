package io.eitheror.casino.kyc.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.it.Reply
import io.eitheror.casino.kyc.policy.PolicyEntity
import io.eitheror.casino.kyc.record.{ClearSof, CountDeposit, FlagReason, KycRecord}


/**
 * features/kyc/gates.feature (US4, FR-019 to FR-024, FR-038). The checks are asked as `players` and
 * `game-provider` ask them; a status is set through the operator's routes, lifetime deposits on the record
 * as the deposits consumer counts them, and a `deposit_confirmed` fact published as the wallet publishes
 * it. Amounts are minor units.
 */
class GatesFeatures extends KycFeatures("gates.feature"):

  private var answer: Reply             = null
  private var ordinal                   = 0
  private var tightened: Vector[String] = Vector.empty
  private var untouched: Vector[String] = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    answer = null; ordinal = 0; tightened = Vector.empty; untouched = Vector.empty

  // ── the scene ─────────────────────────────────────────────────────────────────────────────────

  private def policy(fields: (String, Any)*): Unit =
    val r = kit.json("PUT", kit.operatorPath("/policy"), json(fields*), kit.asOperator())
    assertEquals(r.status, 200, r.text)

  private def statusOf(s: String, of: String = player): Unit = s match
    case "unverified" => ()
    case "pending"    => assertEquals(kit.json("PUT", kit.operatorPath(s"/$of/status"), json("status" -> "pending"), kit.asOperator()).status, 200)
    case other        => assertEquals(kit.json("POST", kit.operatorPath(s"/$of/override"), json("status" -> other), kit.asOperator()).status, 200)

  /** Lifetime deposits as the deposits consumer counts a confirmed deposit, in the policy's terms. */
  private def deposited(amount: Long, currency: String = "EUR", of: String = player): Unit =
    ordinal += 1
    val p = kit.components.forEventSourcedEntity(EntityId(kit.slug)).call(PolicyEntity.get).invoke()
    kit.components.forEventSourcedEntity(EntityId(of)).call(KycRecord.countDeposit)
      .invoke(CountDeposit(of, currency, amount, ordinal, p, kit.now())): Unit

  private def lifetime(currency: String = "EUR"): Long = state().deposits.get(currency).fold(0L)(_.total)

  private def confirmed(amount: Long, currency: String = "EUR"): Unit =
    ordinal += 1
    kit.publish(MoneyTransactions, s"$player/$currency", DepositConfirmed(player, Currency.unsafe(currency), s"t-$player-$ordinal",
      MinorUnits.unsafe(amount), s"ref-$ordinal", ordinal, SignedMinorUnits(amount), MinorUnits.unsafe(0), kit.now()))

  private def depositCheck(amount: Long, currency: Option[String] = Some("EUR"), of: String = player): Reply =
    answer = kit.json("POST", kit.servicePath("/deposit-check"), json("playerId" -> of, "amount" -> amount, "currency" -> currency),
      kit.asService("players"))
    assertEquals(answer.status, 200, answer.text)
    answer

  private def playCheck(): Reply =
    answer = kit.request("GET", kit.servicePath(s"/$player/play-check"), None, kit.asService("game-provider"))
    assertEquals(answer.status, 200, answer.text)
    answer

  // ── the deposit gate ──────────────────────────────────────────────────────────────────────────

  Given("the brand's deposit gate is verified and the player's KYC status is {word}") { (s: String) =>
    policy("depositGate" -> "KYC_APPROVED")
    statusOf(s)
  }

  When("the deposit check is asked") { () => depositCheck(1000) }

  Then("it refuses with {string} and {string}") { (code: String, words: String) =>
    assertEquals((answer.json.bool("allowed"), answer.str("code"), answer.str("message")), (Some(false), code, words))
    assertEquals(answer.str("kycStatus"), state().status.wire)
  }

  Given("the brand's deposit gate is none with a deposit threshold of {string}, and the player is unverified with lifetime deposits of {string}") {
    (threshold: String, total: String) =>
      policy("kycCurrency" -> "EUR", "depositThresholdMinorUnits" -> threshold.toLong)
      deposited(total.toLong)
  }

  When("a deposit of {string} is checked") { (amount: String) => depositCheck(amount.toLong) }

  Then("it refuses with {string}") { (code: String) =>
    assertEquals((answer.json.bool("allowed"), answer.str("code")), (Some(false), code), answer.text)
  }

  Then("the player is flagged for review with reason {string}") { (reason: String) =>
    val wire = reason.replace(' ', '_')
    eventually(s"the $wire flag")(FlagReason.of(wire).flatMap(state().openFlag))
  }

  Then("a {string} fact is published") { (name: String) =>
    assertEquals(name, "player_kyc_review_required")
    eventually(name)(kit.facts(CasinoPlayers).collectFirst { case f: PlayerKycReviewRequired if f.playerId == player => f })
  }

  Then("it is allowed") { () => assertEquals(answer.json.bool("allowed"), Some(true), answer.text) }

  // ── source of funds ───────────────────────────────────────────────────────────────────────────

  private def sofScene(threshold: String, total: String): Unit =
    policy("kycCurrency" -> "EUR", "sofThresholdMinorUnits" -> threshold.toLong)
    deposited(total.toLong)

  Given("a source of funds threshold of {string}, and the player's source of funds not cleared with lifetime deposits of {string}") {
    (threshold: String, total: String) => sofScene(threshold, total)
  }

  Given("a source of funds threshold of {string}, and the player's source of funds cleared with lifetime deposits of {string}") {
    (threshold: String, total: String) =>
      sofScene(threshold, total)
      kit.components.forEventSourcedEntity(EntityId(player)).call(KycRecord.clearSof).invoke(ClearSof("op-1", kit.now())): Unit
  }

  // ── the play gate ─────────────────────────────────────────────────────────────────────────────

  Given("the brand's play gate is verified and the player is unverified") { () => policy("playGate" -> "KYC_APPROVED") }

  When("the play check is asked") { () => playCheck() }

  Then("it refuses with {string} and the status's own words") { (code: String) =>
    assertEquals((answer.json.bool("allowed"), answer.str("error"), answer.str("code")), (Some(false), code, code))
    assertEquals((answer.str("kycStatus"), answer.str("message")), ("unverified", "Please verify your identity before playing"))
  }

  Given("the brand's play gate is verified and the player is verified") { () =>
    policy("playGate" -> "KYC_APPROVED")
    statusOf("verified")
  }

  Given("the brand's play gate is none and the player is unverified") { () => policy("playGate" -> "NONE") }

  // ── lifetime deposits ─────────────────────────────────────────────────────────────────────────

  Given("the player's lifetime deposits of {string}") { (total: String) =>
    policy("kycCurrency" -> "EUR", "depositThresholdMinorUnits" -> 200000L)
    deposited(total.toLong)
  }

  When("a {string} fact of {string} is read") { (name: String, amount: String) =>
    assertEquals(name, "deposit_confirmed")
    confirmed(amount.toLong)
  }

  Then("the player's lifetime deposits are {string}") { (total: String) =>
    eventually(s"lifetime deposits of $total")(Option.when(lifetime() == total.toLong)(()))
  }

  Then("a later check uses the new total") { () =>
    assertEquals(depositCheck(5000).json.bool("allowed"), Some(true), answer.text) // 195000 + 5000 is the threshold, allowed
    assertEquals(depositCheck(5001).str("code"), "DEPOSIT_BLOCKED_KYC_THRESHOLD")
  }

  // ── a tightened policy ────────────────────────────────────────────────────────────────────────

  Given("a brand whose KYC policy tightens while unverified players have lifetime deposits over the new deposit threshold") { () =>
    policy("kycCurrency" -> "EUR", "depositThresholdMinorUnits" -> 1000000L)
    tightened = Vector.fill(2)(newPlayer())
    tightened.foreach(p => deposited(150000, of = p))
    val under    = newPlayer()
    val verified = newPlayer()
    deposited(50000, of = under)
    deposited(150000, of = verified)
    statusOf("verified", verified)
    untouched = Vector(under, verified)
  }

  When("the policy is saved") { () => policy("depositThresholdMinorUnits" -> 100000L) }

  Then("each such player is flagged for review once") { () =>
    tightened.foreach(p => eventually(s"$p flagged")(state(p).openFlag(FlagReason.Threshold)))
    policy("kycWindowDays" -> 45) // a further save flags no one again
    Thread.sleep(1500)
    tightened.foreach(p => assertEquals(state(p).flags.count(_.reason == FlagReason.Threshold), 1, p))
    untouched.foreach(p => assertEquals(state(p).flags, Vector.empty, p))
  }

  // ── currencies ────────────────────────────────────────────────────────────────────────────────

  Given("the brand's KYC currency is {string} with a deposit threshold of {string}") { (c: String, threshold: String) =>
    policy("kycCurrency" -> c, "depositThresholdMinorUnits" -> threshold.toLong)
  }

  When("a deposit of {string} in {string} is checked") { (amount: String, c: String) => depositCheck(amount.toLong, Some(c)) }

  Given("the brand has no deposit threshold and no source of funds threshold, and its deposit gate is none") { () =>
    policy("depositGate" -> "NONE", "depositThresholdMinorUnits" -> null, "sofThresholdMinorUnits" -> null)
  }

  Given("the brand's KYC currency is {string} and the player's lifetime deposits of {string}") { (c: String, total: String) =>
    policy("kycCurrency" -> c, "depositThresholdMinorUnits" -> 1000000L)
    deposited(total.toLong, c)
  }

  When("a {string} fact of {string} in {string} is read") { (name: String, amount: String, c: String) =>
    assertEquals(name, "deposit_confirmed")
    confirmed(amount.toLong, c)
  }

  Then("the player's lifetime deposits are still {string}") { (total: String) =>
    eventually("the EUR deposit counted")(Option.when(state().deposits.contains("EUR"))(()))
    assertEquals(lifetime("GBP"), total.toLong)
  }

  // ── a large deposit ───────────────────────────────────────────────────────────────────────────

  Given("the brand's deposit threshold of {string} and the player's KYC status is {word}") { (threshold: String, s: String) =>
    policy("kycCurrency" -> "EUR", "depositThresholdMinorUnits" -> threshold.toLong)
    statusOf(s)
  }

  Given("the brand's deposit threshold of {string} and the player is verified") { (threshold: String) =>
    policy("kycCurrency" -> "EUR", "depositThresholdMinorUnits" -> threshold.toLong)
    statusOf("verified")
  }

  Then("the player is not suspended") { () =>
    Thread.sleep(500)
    assertEquals(kit.players.callsFor(player), Vector.empty)
  }

  Then("the player is not flagged") { () =>
    eventually("the deposit counted")(Option.when(lifetime() > 0)(()))
    assertEquals(state().flags, Vector.empty)
  }

