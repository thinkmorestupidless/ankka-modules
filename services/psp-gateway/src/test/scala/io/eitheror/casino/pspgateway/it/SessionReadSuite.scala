package io.eitheror.casino.pspgateway.it

import io.eitheror.casino.pspgateway.sessions.Provider

import java.util.UUID
import scala.concurrent.duration.*

/** contracts/merchant.md §2: what `merchant` reads of a session, and who may read it. */
class SessionReadSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 5.minutes

  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.credential("sb-1", Provider.Digitarium)

  test("pending after an initiate; resolved, captured and delivered after the capture; refused and undelivered false") {
    kit.psp.control(Provider.Digitarium, "pending")
    val id = UUID.randomUUID().toString
    assertEquals(kit.initiate(id, Provider.Digitarium).status, 200)
    val pending = kit.session(id)
    assertEquals((pending.status, pending.str("state")), (200, "pending"), pending.text)
    for f <- Seq("delivered", "undelivered", "refused") do assertEquals(pending.json.get(f).asBoolean, false, f)

    kit.psp.webhook(Provider.Digitarium, id, "outcome" -> "success"): Unit
    kit.eventually("delivered")(Option(kit.session(id)).filter(_.json.get("delivered").asBoolean))
    val done = kit.session(id)
    assertEquals((done.str("state"), done.str("outcome")), ("resolved", "captured"), done.text)
    assertEquals((done.json.get("undelivered").asBoolean, done.json.get("refused").asBoolean), (false, false))
    assert(done.str("updatedAt").nonEmpty, done.text)
  }

  test("an unknown id is 404; another caller is 403") {
    assertEquals(kit.session("nobody-" + UUID.randomUUID()).status, 404)
    assertEquals(kit.request("GET", "/v1/psp/sessions/x", None, kit.asPayouts("goldvibe")).status, 403)
    assertEquals(kit.request("GET", "/v1/psp/sessions/x", None, kit.asService("payments", "ingest")).status, 403)
  }
