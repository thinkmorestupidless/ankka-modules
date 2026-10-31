package io.eitheror.casino.payouts.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.CasinoWithdrawals
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.player.PlayerPayouts
import io.eitheror.casino.payouts.request.Status

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

/**
 * SC-006 and R12: erasing a player leaves every destination, account number, name, email and reason unreadable
 * on the player's record, on each request and in the queue, and every id, amount, status and time readable.
 * The facts never carried them. Only `players` may ask, and a repeat answers the same.
 */
class ErasureSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit   = SharedKit.kit
  private lazy val scene = Scene(kit)
  override def munitTimeout = 300.seconds
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    scene.terminals()

  private val Iban   = "DE89370400440532013000"
  private val Reason = "passport photo blurred"

  private def erase(p: String, headers: Seq[(String, String)] = kit.asService("players")): Reply =
    kit.request("POST", s"/v1/casino/brands/${kit.slug}/payouts/erasure/players/$p", Some("{}"), headers)

  /** None of the player's personal values in `text`. */
  private def clean(what: String, text: String, p: String): Unit =
    Vector("Ana", "Silva", Iban, s"$p@example.test", Reason).foreach(v => assert(!text.contains(v), s"$what still holds $v: $text"))

  test("erasure blanks every personal value of the player and each request, and keeps the rest"):
    val p        = scene.player("er")
    val rejected = scene.request(p, amount = 1500L)
    val r = kit.json("POST", s"/v1/casino/brands/${kit.slug}/withdrawal-requests/$rejected/reject", Json.obj("reason" -> Reason),
      kit.asOperator())
    assertEquals(r.status, 200, r.text)
    val pending = scene.request(p, amount = 2500L)
    kit.eventually("both queued")(Option.when(kit.request("GET",
      s"/v1/casino/brands/${kit.slug}/withdrawal-requests?playerSearch=$p&status=ALL", None, kit.asOperator()).json.long("total").contains(2L))(()))

    val a = erase(p)
    assertEquals(a.status, 200, a.text)
    assertEquals(a.json.get("erased").size(), 2, a.text)
    assertEquals(erase(p).text, a.text, "a repeat answers the same")

    val record = kit.components.forEventSourcedEntity(EntityId(p)).call(PlayerPayouts.get).invoke()
    assert(record.erased)
    clean("the player's record", record.toString, p)
    Vector(rejected -> (Status.Rejected, 1500L), pending -> (Status.PendingReview, 2500L)).foreach { case (id, (status, amount)) =>
      val s = scene.state(id)
      assert(s.erased, id)
      clean(s"request $id", s.toString, p)
      assertEquals((s.status, s.amount, s.playerId, s.requestId), (status, amount, p, id))
      val detail = kit.request("GET", s"/v1/casino/brands/${kit.slug}/withdrawal-requests/$id", None, kit.asOperator())
      assertEquals(detail.status, 200, detail.text)
      clean(s"the detail of $id", detail.text, p)
      assertEquals(detail.json.text("createdAt"), Some(s.createdAt.toString))
    }
    val q = kit.eventually("the queue blanked")(Some(kit.request("GET",
      s"/v1/casino/brands/${kit.slug}/withdrawal-requests?playerSearch=$p&status=ALL", None, kit.asOperator()))
      .filter(_.json.objects("requests").forall(_.text("destinationLastFour").isEmpty)))
    clean("the queue", q.text, p)
    assertEquals(q.json.objects("requests").flatMap(_.long("amount")), Vector(2500L, 1500L))

    val facts = kit.facts(CasinoWithdrawals).filter(_.playerId == p)
    assert(facts.nonEmpty)
    facts.foreach(f => clean("a fact", new String(CasinoWithdrawals.serializer.toBytes(f), UTF_8), p))

  test("the export is the player's destinations and requests, with no whole IBAN and no operator's reason; players alone may read it"):
    val p        = scene.player("ex")
    val rejected = scene.request(p, amount = 1500L)
    assertEquals(kit.json("POST", s"/v1/casino/brands/${kit.slug}/withdrawal-requests/$rejected/reject", Json.obj("reason" -> Reason),
      kit.asOperator()).status, 200)
    val pending = scene.request(p, amount = 2500L)
    def read(h: Seq[(String, String)]) = kit.request("GET", s"/v1/casino/brands/${kit.slug}/payouts/exports/players/$p", None, h)
    val a = kit.eventually("both exported")(Some(read(kit.asService("players"))).filter(_.json.objects("requests").size == 2))
    assertEquals((a.status, a.str("service"), a.str("playerId")), (200, "payouts", p), a.text)
    val rs = a.json.objects("requests")
    assertEquals(rs.flatMap(_.text("requestId")), Vector(rejected, pending))
    assertEquals(rs.flatMap(_.text("status")), Vector("REJECTED", "PENDING_REVIEW"))
    assertEquals(rs.flatMap(_.long("amount")), Vector(1500L, 2500L))
    val d = a.json.objects("destinations")
    assertEquals(d.flatMap(_.text("ibanLastFour")), Vector("3000"))
    assertEquals(d.flatMap(_.text("firstName")), Vector("Ana"))
    assert(!a.text.contains(Iban) && !a.text.contains(Reason), a.text)
    Vector(kit.asService("wallet"), kit.asPlayer(p), kit.asOperator()).foreach(h => assert(Set(401, 403)(read(h).status)))

  test("only players may erase"):
    val p = scene.player("er")
    Vector(kit.asService("wallet"), kit.asService("players", "payments"), kit.asPlayer(p), kit.asOperator(), kit.asGateway).foreach { h =>
      val r = erase(p, h)
      assert(Set(401, 403)(r.status), s"${r.status} ${r.text}")
    }
    assert(!kit.components.forEventSourcedEntity(EntityId(p)).call(PlayerPayouts.get).invoke().erased)

  test("a player payouts never heard of is erased with nothing to name"):
    val a = erase(s"unknown-${java.util.UUID.randomUUID()}")
    assertEquals((a.status, a.json.get("erased").size()), (200, 0), a.text)
