package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.engagement.reads.SendsView
import io.eitheror.casino.engagement.send.{Discard, Outcome, Send}

import java.time.Instant
import scala.concurrent.duration.*

/**
 * SC-008: what Customer.io accepted equals what the records say was accepted, and every refusal, parking and discard
 * is a record, the refusals and the discards each an audit record too.
 */
class RecordsSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit   = SharedKit.kit
  private lazy val facts = Facts(kit)

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("DepositConfirmed" -> "7"))

  private def audits(playerId: String, action: String) =
    kit.facts(f.PlatformAudit).collect { case a: f.AuditRecord if a.subjectId == playerId && a.action == action => a }

  test("the accepted calls at the fake match the accepted records; refusals, parkings and discards are recorded") {
    kit.crm.reset()
    val p = facts.player("rec")
    val accepted = Vector.fill(3)(facts.deposit(p, Instant.now()))
    accepted.foreach(facts.publish)
    val states = accepted.map(d => facts.settled(facts.key("deposit_confirmed", d)))
    assert(states.forall(_.outcome == Outcome.Accepted))
    val recorded = states.flatMap(_.calls).count(_.outcome == Outcome.Accepted)
    assertEquals(facts.calls(p).count(_.accepted), recorded)

    kit.crm.fail(503, 4)
    val parked = facts.deposit(p)
    facts.publish(parked)
    val k = facts.key("deposit_confirmed", parked)
    assertEquals(facts.settled(k).outcome, Outcome.Parked)
    kit.eventually("the parking in the view")(SendsView.parked(kit.views.forView(SendsView)).rows.find(_.key == k)): Unit
    kit.components.forWorkflow(EntityId(k)).call(Send.discard).invoke(Discard("op-1", "outage")): Unit
    kit.eventually("the discard audited")(audits(p, "engagement.send.discarded").headOption): Unit

    val q = facts.player("rec", consented = false)
    val refused = facts.deposit(q)
    facts.publish(refused)
    assertEquals(facts.settled(facts.key("deposit_confirmed", refused)).outcome, Outcome.MarketingSuppressed)
    kit.eventually("the refusal audited", 30.seconds)(audits(q, "engagement.send.refused").headOption): Unit
    val rows = kit.eventually("every send in the view")(Option(SendsView.byPlayer(kit.views.forView(SendsView), p)).filter(_.total == 4))
    assertEquals(rows.rows.map(_.outcome).sorted, (Vector.fill(3)(Outcome.Accepted) :+ Outcome.Discarded).sorted)
  }
