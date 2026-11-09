package io.eitheror.casino.engagement.it

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.profile.Suppression
import io.eitheror.casino.engagement.send.Outcome

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * `POST /v1/casino/brands/<slug>/engagement/sends` (US4; FR-013, FR-014; contracts/routes.md) as `players` of the
 * project: one send per key, the verification's fallback, `not_configured`, `suppressed`, `refused`, the body's
 * refusals, and a caller from another project refused. The links never reach the log above debug.
 */
class SendRouteSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit   = SharedKit.kit
  private lazy val facts = Facts(kit)

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("PasswordResetRequest" -> "21", "PasswordChangeVerification" -> "22"))

  override def beforeEach(context: BeforeEach): Unit =
    kit.crm.reset()
    kit.crm.expect(FakeCrm.SiteId, FakeCrm.TrackKey, FakeCrm.AppKey)

  private val players = kit.asService("players")

  private def body(p: String, trigger: String, key: String = UUID.randomUUID().toString, link: String = "https://x/l?t=secret-link") =
    Json.obj("key" -> key, "playerId" -> p, "trigger" -> trigger, "to" -> s"$p@example.com", "link" -> link)

  private def send(b: com.fasterxml.jackson.databind.JsonNode, as: Seq[(String, String)] = players): Reply =
    kit.json("POST", kit.engagementPath("/sends"), b, as)

  private def to(p: String) = kit.crm.sends.filter(_.path("to").asText == s"$p@example.com")

  test("one send per key: a repeat answers the first and sends nothing") {
    val p = facts.player("route")
    val b = body(p, "PasswordResetRequest")
    val a = send(b)
    assertEquals((a.status, a.str("status"), a.str("sendId")), (200, "accepted", s"PasswordResetRequest/${b.path("key").asText}"))
    val email = to(p).head
    assertEquals((email.path("transactional_message_id").asText, email.path("message_data").path("reset_link").asText),
      ("21", "https://x/l?t=secret-link"))
    val again = send(b)
    assertEquals(again.text, a.text)
    Thread.sleep(500)
    assertEquals(to(p).size, 1)
  }

  test("the verification uses the original's built-in template when the brand set none; another trigger is not configured") {
    val p = facts.player("route")
    val v = send(body(p, "EmailAddressVerification"))
    assertEquals(v.str("status"), "accepted")
    assertEquals(to(p).map(_.path("transactional_message_id").asText), Vector("email_address_verification"))
    val n = send(body(p, "EmailChangeNotification"))
    assertEquals((n.status, n.str("status")), (200, "not_configured"))
    assertEquals(to(p).size, 1)
  }

  test("a closed player is answered suppressed with the reason; the route's triggers are service class, so consent does not") {
    val withdrawn = facts.player("route", consented = false)
    assertEquals(send(body(withdrawn, "PasswordResetRequest")).str("status"), "accepted")
    val p = facts.player("route")
    facts.players(f.PlayerClosed(p, "active", "closed", "player", None, None, Instant.now().minusSeconds(10)))
    val a = send(body(p, "PasswordResetRequest"))
    assertEquals((a.str("status"), a.str("reason")), ("suppressed", Outcome.AccountClosed))
    assertEquals(to(p), Vector.empty)
  }

  test("a send the CRM refuses is answered refused with its status") {
    val p = facts.player("route")
    kit.crm.refuse(400, """{"meta":{"error":"bad template"}}""")
    val a = send(body(p, "PasswordChangeVerification"))
    assertEquals((a.status, a.str("status"), a.str("reason")), (200, "refused", "400"))
  }

  test("a send still being retried at the bound is answered accepted once claimed") {
    val p = facts.player("route")
    kit.crm.fail(503, 3)
    val a = send(body(p, "PasswordResetRequest"))
    assertEquals(a.str("status"), "accepted")
    facts.settled(a.str("sendId"))
    kit.eventually("the retried send made")(to(p).headOption): Unit
  }

  test("a body missing fields is 400 naming them; an unknown trigger 422; nothing is sent") {
    val p = facts.player("route")
    val missing = kit.json("POST", kit.engagementPath("/sends"), Json.obj("playerId" -> p, "trigger" -> "PasswordResetRequest"), players)
    assertEquals((missing.status, missing.str("error")), (400, "missing_fields"))
    assertEquals(missing.json.get("fields").toString, """["key","to"]""")
    val unknown = send(body(p, "ConsentUpdated"))
    assertEquals((unknown.status, unknown.str("error")), (422, "unknown_trigger"))
    Thread.sleep(300)
    assertEquals(to(p), Vector.empty)
  }

  test("a caller from another project is refused and nothing is sent; the link is in no log line above debug") {
    val p = facts.player("route")
    val other = send(body(p, "PasswordResetRequest"), kit.asService("players", project = "another-brand"))
    assert(other.status == 401 || other.status == 403, s"${other.status} ${other.text}")
    Thread.sleep(300)
    assertEquals(to(p), Vector.empty)
    assertEquals(kit.logText.filter(_.contains("secret-link")), Vector.empty)
  }
