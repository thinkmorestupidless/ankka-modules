package io.eitheror.casino.wallet.features

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.publish.Facts

/**
 * features/audit/records.feature's wallet scenarios (spec 018 US1, research R15): the record each action
 * leaves on the brand's `platform.audit`, as the wallet's audit publisher maps its events.
 */
class AuditRecordsFeatures extends WalletFeatures("records.feature", dir = "audit"):

  override protected def ranElsewhere: Map[String, String] = AuditRecordsFeatures.Elsewhere
  override protected def ranOutside: Map[String, String]   = AuditRecordsFeatures.Outside

  private var brand                         = "goldvibe"
  private var written: Vector[f.AuditRecord] = Vector.empty
  private var repeated: Vector[f.AuditRecord] = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    brand = "goldvibe"; written = Vector.empty; repeated = Vector.empty

  /** The records the last action's events publish. */
  private def recorded(r: Result): Vector[f.AuditRecord] =
    r.events.flatMap(Facts.audit(brand, s"player-1/$currency", _)).map(_.fact.asInstanceOf[f.AuditRecord])

  private def one: f.AuditRecord =
    assertEquals(written.size, 1, written.toString)
    written.head

  private def balanceChange(field: String, by: Long): Unit =
    val c = one.changes.find(_.field == field).getOrElse(fail(one.changes.toString))
    assertEquals(c.before.map(_.toLong), Some(c.after.get.toLong - by))
    assertEquals(c.after.map(_.toLong), Some(state.realBalance))

  Given("a player of brand {string}") { (b: String) => brand = b }

  Given("an operator with a role for the brand") { () => seeding(_.copy(real = 1000L)) }

  When("they adjust the player's real balance by {string} with the reason code {string}") { (a: String, code: String) =>
    written = recorded(act(run(walletKit.call(WalletEntity.adjust)(Adjust(nextKey("adj"), "real", long(a), "a duplicated deposit", code,
      "op-7", now())))))
  }

  Then("the brand's audit records hold one record whose actor is the operator, subject the player, part the wallet, action {string} and outcome done") {
    (action: String) =>
      val r = one
      assertEquals((r.actorKind, r.actorId, r.subjectKind, r.subjectId, r.service, r.action, r.brand),
        ("operator", "op-7", "player", "player-1", "wallet", action, brand))
      // Done is the action's own outcome, the wallet's `accepted`: anything but a refusal (FR-001).
      assertEquals(r.outcome, "accepted")
  }

  Then("its changes list the real balance's before and after") { () =>
    balanceChange("realBalance", one.changes.find(_.field == "amount").flatMap(_.after).get.toLong)
    assertEquals(one.changes.find(_.field == "currency").flatMap(_.after), Some(currency))
  }

  Given("the player has a real balance of {string}") { (r: String) => seeding(_.copy(real = long(r))) }

  When("the game provider places a bet of {string}") { (a: String) => written = recorded(act(bet(long(a), "r1"))) }

  Then("one record has outcome refused with reason code {string}") { (code: String) =>
    assertEquals((one.outcome, one.reasonCode), ("refused", Some(code)))
  }

  Then("its causation is the bet's key") { () => assertEquals(Some(one.causationId), lastBetKey) }

  Then("it lists no change") { () => assertEquals(one.changes, Vector.empty) }

  Given("a captured deposit handed to the wallet by the cashier") { () => () }

  When("the wallet credits it") { () => written = recorded(act(deposit(2500L, "pay-1"))) }

  Then("one record has actor kind {string} naming the cashier") { (kind: String) =>
    assertEquals((one.actorKind, one.actorId), (kind, "cashier"))
  }

  Then("its correlation is the payment id") { () => assertEquals(one.correlationId, "pay-1") }

  Then("it lists the balance's before and after") { () => balanceChange("realBalance", 2500L) }

  Given("an action a part of the brand performed under a key") { () => written = recorded(act(deposit(2500L, "pay-2"))) }

  When("the same action is repeated under the same key") { () => repeated = recorded(act(deposit(2500L, "pay-2"))) }

  Then("no second record is written, because the record id derives from the part, the entity and the key") { () =>
    assertEquals(written.map(_.recordId), Vector("wallet/player-1/EUR/pay-2"))
    // A repeat persists nothing, or what it records carries the first record's id.
    assert(repeated.forall(_.recordId == written.head.recordId), repeated.toString)
  }

object AuditRecordsFeatures:
  // Here, not in the class: GherkinSuite reads these while it is constructed.
  private val Elsewhere = Map(
    "a player setting a deposit limit is one record with the player as actor and subject and the limit's before and after" ->
      "services/responsible-gambling's AuditRecordsFeatures: the limit's record is the responsible gambling service's",
    "a change to the email address names the field and carries no before or after value" ->
      "services/players' AuditRecordsFeatures: the profile's record is the players service's"
  )
  private val Outside = Map(
    "a suspension by the support agent's tool is one record with actor kind agent, the approver, and the suspension's before and after" ->
      "spec 017 is not built"
  )
