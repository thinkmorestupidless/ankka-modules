package io.eitheror.casino.ingest.it

import io.eitheror.casino.ingest.keys.KeyIndex
import io.eitheror.casino.ingest.requests.{Outcome, RecordState, RequestRecord, RequestsView, Route}
import io.eitheror.casino.ingest.shape.Digests
import com.thinkmorestupidless.ankka.core.EntityId

import java.time.Instant
import java.util.UUID

/** T022: the service starts and refreshes its keys, and each prefix admits its audience and no other (contracts/routes.md). */
class SmokeSuite extends munit.FunSuite:
  private val raw = Vector("mk_smoke_one", "mk_smoke_two")
  private lazy val kit = Kit(keys = raw.zipWithIndex.map((k, i) => (Digests.ofKey(k), s"k-smoke-$i", "sb-smoke")))

  override def afterAll(): Unit = kit.stop()

  test("the start refresh issued merchant's two keys, and the index answers each digest") {
    assertEquals(kit.refreshed, 2)
    raw.foreach(k => assertEquals(kit.eventually(s"$k indexed")(KeyIndex.find(kit.components, Digests.ofKey(k))).subBrand, "sb-smoke"))
  }

  test("a create with no platform identity reaches the handler, and a bearer that is no token does too (research R11)") {
    val key = RawKey(raw.head, "k-smoke-0", "sb-smoke")
    assertEquals(kit.create(key).status, 200)
    val bearer = kit.create(Some(key), kit.body(), "Authorization" -> "Bearer not-a-token")
    assertEquals(bearer.status, 200, bearer.text)
  }

  test("the operator routes: no token 401, a staff token without payments 403, an operator 200") {
    assertEquals(kit.request("GET", "/v1/ingest/lost-answers", headers = kit.asNobody).status, 401)
    assertEquals(kit.request("GET", "/v1/ingest/lost-answers", headers = kit.asStaff).status, 403)
    assertEquals(kit.request("GET", "/v1/ingest/lost-answers", headers = kit.asOperator()).status, 200)
  }

  test("a record written through the entity is found by request id within 2 s, once the view is running (SC-007)") {
    def write(requestId: String): String =
      val id = UUID.randomUUID().toString
      kit.components.forKeyValueEntity(EntityId(id)).call(RequestRecord.write)
        .invoke(RecordState(id, Some("sb-smoke-2"), Route.Create, Some(Instant.now()), Some(requestId), outcome = Outcome.Served)): Unit
      id
    def found(requestId: String, within: Int) =
      kit.eventually(s"$requestId in the view", scala.concurrent.duration.DurationInt(within).seconds)(
        RequestsView.byId(kit.views, "sb-smoke-2", requestId, 10).headOption)
    write("t-warm"): Unit
    found("t-warm", 30).recordId: Unit // the projection's first start is not its lag
    val id = write("t-smoke")
    assertEquals(found("t-smoke", 2).recordId, id)
  }

  test("the routes the original exposed and the shared payments does not are the runtime's not-found (FR-006)") {
    Seq("POST" -> "/v1/payments", "POST" -> "/v1/payouts", "POST" -> "/v1/payments/verify", "GET" -> "/v1/cashier/sessions").foreach { (m, p) =>
      val r = kit.request(m, p, if m == "POST" then Some("{}") else None, kit.asSubBrand)
      assert(r.status == 404 || r.status == 405, s"$m $p: ${r.status} ${r.text}")
    }
  }

  test("a preflight is not 200 and carries no Access-Control header (FR-020)") {
    val r = kit.request("OPTIONS", "/v1/cashier/sessions", headers = kit.asSubBrand :+ ("Origin" -> "https://goldvibe.example"))
    assertNotEquals(r.status, 200)
    assert(!r.headers.keys.exists(_.startsWith("access-control-")), r.headers.toString)
  }
