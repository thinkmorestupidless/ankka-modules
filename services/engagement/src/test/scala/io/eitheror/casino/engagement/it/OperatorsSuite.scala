package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.events.Classes
import io.eitheror.casino.engagement.profile.Suppression
import io.eitheror.casino.engagement.send.{Outcome, Send, SendRequest}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The backoffice's sends routes and presence (US7; FR-023, FR-025; contracts/routes.md): a player's sends newest first,
 * 50 a page; the parked sends and their search; a retry accepted, parked again or refused for suppression; a discard
 * with its reason; each act an audit record naming the operator; the refusals; presence with `since`.
 */
class OperatorsSuite extends munit.FunSuite:
  override val munitTimeout = 300.seconds
  private lazy val kit   = SharedKit.kit
  private lazy val facts = Facts(kit)

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("DepositConfirmed" -> "7"))

  override def beforeEach(context: BeforeEach): Unit =
    kit.crm.reset()
    kit.crm.expect(FakeCrm.SiteId, FakeCrm.TrackKey, FakeCrm.AppKey)

  private def path(rest: String) = s"/v1/casino/backoffice/${kit.slug}$rest"
  private def get(rest: String)  = kit.request("GET", path(rest), None, kit.asOperator())
  private def post(rest: String, body: Option[String] = None) = kit.request("POST", path(rest), body, kit.asOperator())
  private def id(key: String)    = URLEncoder.encode(key, UTF_8)

  private def rows(r: Reply) = r.json.path("sends").elements.asScala.toVector
  private def parked(search: String) = rows(get(s"/parked-sends?search=$search")).map(_.path("sendId").asText)

  private def audits(p: String, action: String) =
    kit.facts(f.PlatformAudit).collect { case a: f.AuditRecord if a.subjectId == p && a.action == action => a }

  /** A deposit's send, parked after Customer.io failed its four attempts. */
  private def parkedSend(p: String): String =
    kit.crm.fail(503, 4)
    val d = facts.deposit(p)
    facts.publish(d)
    val key = facts.key("deposit_confirmed", d)
    assertEquals(facts.settled(key).outcome, Outcome.Parked)
    kit.eventually("listed as parked")(Option.when(parked("deposit").contains(key))(()))
    key

  test("a player's sends newest first, 50 a page, with each row's fields; the page and the total") {
    val p  = s"o-${UUID.randomUUID().toString.take(8)}"
    val at = Instant.now().minus(java.time.Duration.ofDays(3))
    // Stale sends end at once, with no call: 55 rows of the player's at no cost.
    for i <- 0 until 55 do
      val r = SendRequest(s"player_logged_in/$p-$i", p, "player_logged_in", Some("player_logged_in"),
        Classes.of("player_logged_in").get.wire, SendRequest.Crm, s"fact-$i", at.plusSeconds(i))
      kit.components.forWorkflow(EntityId(r.key)).call(Send.start).invoke(r): Unit
    kit.eventually("55 rows")(Option.when(get(s"/players/$p/sends").json.path("total").asLong == 55)(()))
    val first  = get(s"/players/$p/sends")
    val second = get(s"/players/$p/sends?page=2")
    assertEquals((rows(first).size, first.json.path("page").asInt, rows(second).size, second.json.path("page").asInt), (50, 1, 5, 2))
    val all = (rows(first) ++ rows(second)).map(r => Instant.parse(r.path("time").asText))
    assertEquals(all, all.sortWith((a, b) => !a.isBefore(b)))
    val row = rows(first).head
    assertEquals(row.fieldNames.asScala.toVector, Vector("sendId", "trigger", "event", "class", "channel", "time", "outcome", "reason"))
    assertEquals((row.path("outcome").asText, row.path("class").asText), (Outcome.SkippedStale, "marketing"))
    assertEquals(rows(get("/players/nobody/sends")), Vector.empty)
  }

  test("a retry the CRM accepts is accepted and leaves the parked sends; it is audited naming the operator") {
    val p   = facts.player("retry")
    val key = parkedSend(p)
    val r   = post(s"/parked-sends/${id(key)}/retry")
    assertEquals((r.status, r.str("outcome"), r.str("sendId")), (200, Outcome.Accepted, key), r.text)
    kit.eventually("gone from the parked sends")(Option.when(!parked("deposit").contains(key))(()))
    val a = kit.eventually("the retry audited")(audits(p, "engagement.send.retried").headOption)
    assertEquals((a.actorKind, a.actorId), ("operator", "op-1"))
  }

  test("a retry that fails again parks again after one more attempt") {
    val p   = facts.player("again")
    val key = parkedSend(p)
    kit.crm.fail(503, 1)
    val r = post(s"/parked-sends/${id(key)}/retry")
    assertEquals((r.status, r.str("outcome")), (200, Outcome.Parked), r.text)
    assert(parked("deposit").contains(key) || kit.eventually("parked again")(Option.when(parked("deposit").contains(key))(true)))
  }

  test("a retry for a player suppressed since is refused, audited naming the operator, and is no longer parked") {
    val p   = facts.player("suppressed")
    val key = parkedSend(p)
    facts.players(f.RgCaseOpened(p, s"case-$p", Suppression.SelfExclusionCase, "player", None, Instant.now().minusSeconds(5)))
    val r = post(s"/parked-sends/${id(key)}/retry")
    assertEquals((r.status, r.str("outcome"), r.str("reason")), (200, Outcome.MarketingSuppressed, Suppression.SelfExcluded), r.text)
    assertEquals(kit.crm.tracks(p), Vector.empty)
    val a = kit.eventually("the refusal audited")(audits(p, "engagement.send.refused").headOption)
    assertEquals((a.actorKind, a.actorId), ("operator", "op-1"))
    kit.eventually("gone from the parked sends")(Option.when(!parked("deposit").contains(key))(()))
  }

  test("a discard records who and why, audited without the reason; a discard needs a reason") {
    val p   = facts.player("discard")
    val key = parkedSend(p)
    assertEquals(post(s"/parked-sends/${id(key)}/discard", Some("{}")).str("error"), "missing_fields")
    assertEquals(post(s"/parked-sends/${id(key)}/discard", Some("[]")).str("error"), "invalid_body")
    val r = post(s"/parked-sends/${id(key)}/discard", Some(Json.render(Json.obj("reason" -> s"duplicate for $p"))))
    assertEquals((r.status, r.str("outcome"), r.str("discardedBy"), r.str("discardReason")),
      (200, Outcome.Discarded, "op-1", s"duplicate for $p"), r.text)
    val a = kit.eventually("the discard audited")(audits(p, "engagement.send.discarded").headOption)
    assertEquals(a.actorId, "op-1")
    assert(!a.toString.contains("duplicate"), a.toString)
  }

  test("not_parked for a send that is not parked; send_not_found for none; another brand's operator is refused") {
    val p   = facts.player("ended")
    val d   = facts.deposit(p)
    facts.publish(d)
    val key = facts.key("deposit_confirmed", d)
    assertEquals(facts.settled(key).outcome, Outcome.Accepted)
    for act <- Vector("retry", "discard") do
      val body = Option.when(act == "discard")("""{"reason":"x"}""")
      val r    = post(s"/parked-sends/${id(key)}/$act", body)
      assertEquals((r.status, r.str("error")), (409, "not_parked"), r.text)
      val none = post(s"/parked-sends/${id("deposit_confirmed/nothing")}/$act", body)
      assertEquals((none.status, none.str("error")), (404, "send_not_found"), none.text)
    val other = kit.request("GET", path(s"/players/$p/sends"), None, kit.bearer("staff|op-2|brand_another,brand_admin"))
    assertEquals(other.status, 403, other.text)
  }

  test("presence lists each player with a connection, since they came online, and no longer once they go") {
    val p = s"o-${UUID.randomUUID().toString.take(8)}"
    val s = SseReader(kit.baseUrl + "/v1/stream", kit.bearer(s"player|$p").toMap)
    try
      s.next("ready", 10.seconds): Unit
      val row = kit.eventually("online")(get("/presence").json.path("online").elements.asScala.find(_.path("playerId").asText == p))
      assert(java.time.Duration.between(Instant.parse(row.path("since").asText), Instant.now()).abs.toSeconds < 30, row.toString)
    finally s.close()
    kit.eventually("offline")(Option.when(!get("/presence").json.path("online").elements.asScala.exists(_.path("playerId").asText == p))(()))
  }
