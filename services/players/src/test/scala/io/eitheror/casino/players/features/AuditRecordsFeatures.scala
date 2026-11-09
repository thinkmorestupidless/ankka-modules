package io.eitheror.casino.players.features

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.facts.Facts
import io.eitheror.casino.players.player.PlayerEvent.EmailChanged

import java.time.Instant

/**
 * features/audit/records.feature's email change (spec 018 US1, research R15): the record the players
 * service maps from a profile change, the field named and its value left off (FR-003).
 */
class AuditRecordsFeatures extends GherkinSuite("../../features/audit/records.feature") with LogCapturing:

  override protected def ranElsewhere: Map[String, String] = AuditRecordsFeatures.Elsewhere
  override protected def ranOutside: Map[String, String]   = AuditRecordsFeatures.Outside

  private val address = "player.one@example.com"
  private var brand   = "goldvibe"
  private var written: Option[f.AuditRecord] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    brand = "goldvibe"; written = None

  private def change = written.getOrElse(fail("no record")).changes.find(_.field == "email").getOrElse(fail(written.toString))

  Given("a player of brand {string}") { (b: String) => brand = b }

  Given("a record for a profile change") { () => () }

  When("the changed field is the email address") { () =>
    written = Facts.playerAudit(brand, "p-1", 4, EmailChanged(Personal(address), Instant.parse("2026-11-09T08:00:00Z"))).map(_.fact)
  }

  Then("the change names the field") { () =>
    assertEquals(change.field, "email")
    assertEquals((written.get.actorKind, written.get.actorId, written.get.subjectId), ("player", "p-1", "p-1"))
  }

  Then("it carries no before or after value") { () =>
    assertEquals((change.before, change.after), (None, None))
    assert(!written.get.toString.contains(address), written.toString)
  }

object AuditRecordsFeatures:
  // Here, not in the class: GherkinSuite reads these while it is constructed.
  private val Wallet = "services/wallet's AuditRecordsFeatures: the wallet writes these records"
  private val Elsewhere = Map(
    "an operator's adjustment with a reason code is one record with the operator as actor, the player as subject, the wallet as the part and the balance's before and after" -> Wallet,
    "a bet refused for insufficient funds is one record with outcome refused, the reason code, the bet's key as causation and no change" -> Wallet,
    "a captured deposit handed to the wallet by the cashier is one record with actor kind service, the payment id as correlation, and the balance's before and after" -> Wallet,
    "an action repeated under the same key writes no second record" -> Wallet,
    "a player setting a deposit limit is one record with the player as actor and subject and the limit's before and after" ->
      "services/responsible-gambling's AuditRecordsFeatures: the limit's record is the responsible gambling service's"
  )
  private val Outside = Map(
    "a suspension by the support agent's tool is one record with actor kind agent, the approver, and the suspension's before and after" ->
      "spec 017 is not built"
  )
