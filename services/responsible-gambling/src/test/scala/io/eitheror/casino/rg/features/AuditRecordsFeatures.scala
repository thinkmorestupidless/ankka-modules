package io.eitheror.casino.rg.features

import io.eitheror.casino.contract.facts.{AuditRecord, PlatformAudit}

/**
 * features/audit/records.feature's deposit limit (spec 018 US1, research R15): the record responsible
 * gambling writes on the brand's `platform.audit` when a player sets a limit through their route.
 */
class AuditRecordsFeatures extends RgFeatures("../audit/records.feature"):

  override protected def ranElsewhere: Map[String, String] = AuditRecordsFeatures.Elsewhere
  override protected def ranOutside: Map[String, String]   = AuditRecordsFeatures.Outside

  private def records: Vector[AuditRecord] =
    kit.facts(PlatformAudit).collect { case r: AuditRecord if r.subjectId == player && r.action == "limit-set" => r }

  Given("the player is signed in") { () => () }

  When("they set a daily deposit limit") { () => assertEquals(putLimit("DEPOSIT_DAILY", "50000").status, 200) }

  Then("one record has the player as actor and subject, the part responsible gambling, and the limit's before and after") { () =>
    val r = kit.eventually("the limit's record")(records.headOption)
    assertEquals(records.size, 1, records.toString)
    assertEquals((r.actorKind, r.actorId, r.subjectKind, r.subjectId, r.service), ("player", player, "player", player, "responsible-gambling"))
    assertEquals(r.changes.map(c => (c.field, c.before, c.after)), Vector(("DEPOSIT_DAILY/EUR", None, Some("50000"))))
  }

object AuditRecordsFeatures:
  // Here, not in the class: GherkinSuite reads these while it is constructed.
  private val Wallet = "services/wallet's AuditRecordsFeatures: the wallet writes these records"
  private val Elsewhere = Map(
    "an operator's adjustment with a reason code is one record with the operator as actor, the player as subject, the wallet as the part and the balance's before and after" -> Wallet,
    "a bet refused for insufficient funds is one record with outcome refused, the reason code, the bet's key as causation and no change" -> Wallet,
    "a captured deposit handed to the wallet by the cashier is one record with actor kind service, the payment id as correlation, and the balance's before and after" -> Wallet,
    "an action repeated under the same key writes no second record" -> Wallet,
    "a change to the email address names the field and carries no before or after value" ->
      "services/players' AuditRecordsFeatures: the profile's record is the players service's"
  )
  private val Outside = Map(
    "a suspension by the support agent's tool is one record with actor kind agent, the approver, and the suspension's before and after" ->
      "spec 017 is not built"
  )
