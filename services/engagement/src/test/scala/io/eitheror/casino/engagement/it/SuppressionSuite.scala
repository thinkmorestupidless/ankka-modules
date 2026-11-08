package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.engagement.config.Seed
import io.eitheror.casino.engagement.events.Classes
import io.eitheror.casino.engagement.profile.{ProfileState, Suppression}
import io.eitheror.casino.engagement.send.{Kind, Outcome, Retry, Send, SendRequest, SendState}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * SC-003 (FR-011, FR-012): for every trigger of FR-011's two lists and every cause of suppression, a marketing class
 * send is refused with its reason and audited, its event untracked and its identify still made with the four
 * attributes, and a service class send is made; a closed player is refused both. The same holds at an operator's retry
 * of a parked send, and `rg_case_closed` and `player_reopened` lift it.
 */
class SuppressionSuite extends munit.FunSuite:
  override val munitTimeout = 300.seconds
  private lazy val kit   = SharedKit.kit
  private lazy val facts = Facts(kit)

  /** Every trigger with a transactional id, so a send that is not refused makes all three calls. */
  private val keyOf: Map[String, String] = Seed.events.map((k, e) => e -> k).toMap

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Seed.triggers.map(t => t -> s"t-$t").toMap)

  override def beforeEach(context: BeforeEach): Unit =
    kit.crm.reset()
    kit.crm.expect(FakeCrm.SiteId, FakeCrm.TrackKey, FakeCrm.AppKey)

  private val fourAttributes = Vector("email_subscribed", "sms_subscribed", "marketing_suppressed", "suppression_reason")

  /** A cause of suppression, applied to a registered and consented player, and the reason it gives. */
  private final case class Cause(name: String, reason: String, consented: Boolean = true)(val apply: String => Unit)

  private def ago = Instant.now().minusSeconds(30)
  private def openCase(p: String, caseType: String): String =
    val id = s"case-${UUID.randomUUID().toString.take(8)}"
    facts.players(f.RgCaseOpened(p, id, caseType, "player", None, ago))
    id

  private val causes = Vector(
    Cause("an open self-exclusion case", Suppression.SelfExcluded)(p => openCase(p, Suppression.SelfExclusionCase): Unit),
    Cause("an open cooling-off case", Suppression.CoolingOff)(p => openCase(p, Suppression.CoolOffCase): Unit),
    Cause("email consent withdrawn", Suppression.ConsentWithdrawn)(p =>
      facts.players(f.PlayerConsentChanged(p, "email", false, "preferences", ago))),
    Cause("no consent fact", Suppression.ConsentWithdrawn, consented = false)(_ => ()),
    Cause("the operator's message suppression", Suppression.Operator)(p =>
      facts.players(f.PlayerConsentChanged(p, "messages", false, "operator", ago))),
    Cause("a closed account", Suppression.AccountClosed)(p =>
      facts.players(f.PlayerClosed(p, "active", "closed", "operator", Some("op-1"), None, ago)))
  )

  /** A fact's send of `trigger` (identify, track, transactional), or a route's (the transactional alone). */
  private def request(p: String, trigger: String): SendRequest =
    if Classes.sendOnly(trigger) then
      SendRequest(s"$trigger/${UUID.randomUUID()}", p, trigger, None, Classes.of(trigger).get.wire, SendRequest.Email, "",
        Instant.now(), transactional = Some(trigger), recipient = Some(ProfileState.personal(p, s"$p@example.com")),
        calls = Vector(Kind.Transactional))
    else
      SendRequest(s"$trigger/${UUID.randomUUID()}", p, trigger, Some(trigger), Classes.of(trigger).get.wire, SendRequest.Crm,
        s"fact-${UUID.randomUUID()}", Instant.now(), transactional = keyOf.get(trigger))

  private def start(r: SendRequest): SendState = kit.components.forWorkflow(EntityId(r.key)).call(Send.start).invoke(r)
  private def settled(r: SendRequest): SendState = facts.settled(r.key, 60.seconds)

  private def audits(p: String) =
    kit.facts(f.PlatformAudit).collect { case a: f.AuditRecord if a.subjectId == p && a.action == "engagement.send.refused" => a }

  private def tracked(p: String): Set[String] = kit.crm.tracks(p).map(_.path("name").asText).toSet
  private def emailed(p: String): Set[String] =
    kit.crm.sends.filter(_.path("to").asText == s"$p@example.com").map(_.path("transactional_message_id").asText).toSet

  /** The transactional id configured for a trigger's send: a route's by its own name, a fact's by its key. */
  private def emailId(trigger: String): Option[String] =
    (if Classes.sendOnly(trigger) then Some(trigger) else keyOf.get(trigger)).map(k => s"t-$k")

  private val marketing = Classes.marketing.toVector.sorted
  private val service   = Classes.service.toVector.sorted

  for cause <- causes do
    test(s"${cause.name}: every marketing class trigger is refused as ${cause.reason}, every service class one ${
        if cause.reason == Suppression.AccountClosed then "too" else "is sent"}") {
      val p = facts.player("sup", cause.consented)
      cause.apply(p)
      val closed = cause.reason == Suppression.AccountClosed
      val sends  = (marketing ++ service).map(t => t -> request(p, t))
      sends.foreach((_, r) => start(r))
      val ends = sends.map((t, r) => t -> settled(r)).toMap

      for t <- marketing do
        val s = ends(t)
        val want = if closed then Outcome.AccountClosed else Outcome.MarketingSuppressed
        assertEquals((t, s.outcome, s.reason), (t, want, Some(cause.reason)))
        assert(s.calls.exists(c => c.kind == Kind.Identify && c.outcome == Outcome.Accepted), s"$t made no identify: ${s.calls}")
      for t <- service do
        val s = ends(t)
        if closed then assertEquals((t, s.outcome), (t, Outcome.AccountClosed))
        else assertEquals((t, s.outcome, s.reason), (t, Outcome.Accepted, None))

      val refusedEvents = if closed then (marketing ++ service).toSet else marketing.toSet
      assertEquals(tracked(p).intersect(refusedEvents), Set.empty[String])
      assertEquals(emailed(p).intersect(refusedEvents.flatMap(emailId)), Set.empty[String])
      if !closed then
        assertEquals(tracked(p), service.filterNot(Classes.sendOnly).toSet)
        assertEquals(emailed(p), service.flatMap(emailId).toSet)

      val identify = kit.crm.identifies(p).map(kit.crm.attributes).last
      assertEquals(fourAttributes.filterNot(identify.contains), Vector.empty, identify)
      assertEquals((identify("marketing_suppressed").asText, identify("suppression_reason").asText), ("true", cause.reason))
      val refusals = if closed then marketing.size + service.size else marketing.size
      kit.eventually(s"$refusals refusals audited")(Option(audits(p)).filter(_.size == refusals)): Unit
    }

  test("an operator's retry of a parked send judges the player afresh: a marketing send refused, a service one made") {
    val p = facts.player("retry")
    val m = request(p, "deposit_confirmed")
    kit.crm.fail(503, 4)
    start(m)
    assertEquals(settled(m).outcome, Outcome.Parked)
    val s = request(p, "withdrawal_failed")
    kit.crm.fail(503, 4)
    start(s)
    assertEquals(settled(s).outcome, Outcome.Parked)

    openCase(p, Suppression.SelfExclusionCase): Unit
    for r <- Vector(m, s) do kit.components.forWorkflow(EntityId(r.key)).call(Send.retry).invoke(Retry("op-1")): Unit
    assertEquals((settled(m).outcome, settled(m).reason), (Outcome.MarketingSuppressed, Some(Suppression.SelfExcluded)))
    assertEquals(settled(s).outcome, Outcome.Accepted)
    assertEquals(tracked(p), Set("withdrawal_failed"))
    kit.eventually("the retry's refusal audited")(audits(p).headOption): Unit
  }

  test("rg_case_closed lifts an exclusion and player_reopened a closure; marketing is sent again") {
    val p = facts.player("lift")
    val id = openCase(p, Suppression.SelfExclusionCase)
    val before = request(p, "player_logged_in")
    start(before)
    assertEquals(settled(before).outcome, Outcome.MarketingSuppressed)
    facts.players(f.RgCaseClosed(p, id, Suppression.SelfExclusionCase, "expired", Instant.now().minusSeconds(5)))
    val after = request(p, "player_logged_in")
    start(after)
    assertEquals(settled(after).outcome, Outcome.Accepted)
    assertEquals(kit.crm.identifies(p).map(kit.crm.attributes).last.get("marketing_suppressed").map(_.asText), Some("false"))

    val q = facts.player("lift")
    facts.players(f.PlayerClosed(q, "active", "closed", "player", None, None, ago))
    val shut = request(q, "deposit_confirmed")
    start(shut)
    assertEquals(settled(shut).outcome, Outcome.AccountClosed)
    facts.players(f.PlayerReopened(q, "closed", "active", "operator", Some("op-1"), None, Instant.now().minusSeconds(5)))
    val open = request(q, "deposit_confirmed")
    start(open)
    assertEquals(settled(open).outcome, Outcome.Accepted)
    assertEquals(tracked(q), Set("deposit_confirmed"))
  }
