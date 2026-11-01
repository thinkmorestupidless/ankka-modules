package io.eitheror.casino.payouts.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.clients.Gateway
import io.eitheror.casino.payouts.terminals.{Refresh, TerminalCatalogue}
import io.eitheror.casino.payouts.workflow.Flow

import java.time.Duration as JDuration
import scala.concurrent.duration.*

/**
 * The brand's queue (FR-014; contracts/routes.md): each filter lists its requests with the right total, pending
 * review is the default, and a page is a slice of the newest first. The queue holds every test's requests, so
 * the totals are taken for one player, whose four requests cover each status, band and method.
 */
class QueueSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit   = SharedKit.kit
  private lazy val scene = Scene(kit)
  override def munitTimeout = 300.seconds
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val card = FakeGateway.terminal("t-card", "paymentiq", destination = true, scheme = "CARD")

  private def queue(query: String): Reply =
    kit.request("GET", s"/v1/casino/brands/${kit.slug}/withdrawal-requests?$query", None, kit.asOperator())

  private def ids(r: Reply) = r.json.objects("requests").flatMap(_.text("requestId"))

  test("each filter lists its requests with the right total; the default is pending review"):
    kit.gateway.terminals("EUR", scene.polled, card)
    kit.components.forKeyValueEntity(EntityId(kit.slug)).call(TerminalCatalogue.refresh)
      .invoke(Refresh("EUR", Vector(scene.polled, card).flatMap(Gateway.terminal), kit.now())): Unit
    val p = scene.player("q", balance = 1000000L)
    def next() = kit.advance(JDuration.ofSeconds(1))

    val cancelled = scene.request(p, amount = 1000L)
    assertEquals(kit.request("DELETE", kit.playerPath(s"/$p/withdrawal-requests/$cancelled"), None, kit.asPlayer(p)).status, 200)
    next()
    val rejected = scene.request(p, amount = 60000L)
    val r = kit.json("POST", s"/v1/casino/brands/${kit.slug}/withdrawal-requests/$rejected/reject", Json.obj("reason" -> "unclear"),
      kit.asOperator())
    assertEquals(r.status, 200, r.text)
    next()
    val completed = scene.request(p, amount = 2000L)
    kit.gateway.initiateFor(completed, FakeGateway.answer(completed, "initiated", Some(s"ref-$completed")))
    assertEquals(scene.approve(completed).status, 200)
    scene.at(completed, Flow.Waiting)
    // The wallet holds one withdrawal a player at a time; this one settles before the next is asked.
    kit.gateway.statusFor(completed, FakeGateway.answer(completed, "completed", Some(s"ref-$completed")))
    scene.at(completed, Flow.Completed)
    next()
    val pending = scene.request(p, terminalId = "t-card", amount = 600000L)
    val newest  = Vector(pending, completed, rejected, cancelled)

    val cases = Vector(
      ""                                   -> Vector(pending),
      "status=ALL"                         -> newest,
      "status=CANCELLED"                   -> Vector(cancelled),
      "status=REJECTED"                    -> Vector(rejected),
      "status=COMPLETED"                   -> Vector(completed),
      "status=pending_review"              -> Vector(pending),
      "status=ALL&payoutStatus=RESOLVED"   -> Vector(completed),
      "status=ALL&payoutStatus=NONE"       -> Vector(pending, rejected, cancelled),
      "status=ALL&kyc=VERIFIED"            -> newest,
      "status=ALL&kyc=PENDING"             -> Vector.empty,
      "status=ALL&amountRange=lt500"       -> Vector(completed, cancelled),
      "status=ALL&amountRange=500to5000"   -> Vector(rejected),
      "status=ALL&amountRange=gt5000"      -> Vector(pending),
      "status=ALL&method=CARD"             -> Vector(pending),
      "status=ALL&method=EUBANKING"        -> Vector(completed, rejected, cancelled),
      "status=ALL&stale=true"              -> Vector.empty)
    cases.foreach { (query, expected) =>
      val q = s"playerSearch=$p" + (if query.isEmpty then "" else s"&$query")
      val a = kit.eventually(query)(Some(queue(q)).filter(a => a.status == 200 && ids(a) == expected))
      assertEquals(a.json.long("total"), Some(expected.size.toLong), query)
    }

    val second = queue(s"playerSearch=$p&status=ALL&pageSize=2&page=2")
    assertEquals((ids(second), second.json.long("total"), second.json.long("page")), (Vector(rejected, cancelled), Some(4L), Some(2L)))
    val row = queue(s"playerSearch=$p").json.objects("requests").head
    assertEquals((row.text("kycStatus"), row.long("amount"), row.text("method"), row.text("destinationLastFour")),
      (Some("verified"), Some(600000L), Some("CARD"), Some("3000")))

  test("without a player, the default lists only requests pending review"):
    val a = queue("pageSize=200")
    assertEquals(a.status, 200, a.text)
    assert(a.json.objects("requests").forall(_.text("status").contains("PENDING_REVIEW")), a.text)

  test("a filter value the queue does not know is refused, naming it"):
    Vector("status=SHIPPED" -> "status", "payoutStatus=LOST" -> "payoutStatus", "kyc=maybe" -> "kyc",
      "amountRange=huge" -> "amountRange", "stale=often" -> "stale", "page=0" -> "page", "pageSize=201" -> "pageSize")
      .foreach { (query, field) =>
        val a = queue(query)
        assertEquals((a.status, a.str("field")), (400, field), a.text)
      }
