package io.eitheror.casino.payouts.queue

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.it.SharedKit
import io.eitheror.casino.payouts.request.*

import java.time.{Duration, Instant}
import java.util.UUID

/** data-model.md's queue over Postgres: a row per request with no personal value, each filter, pages of 1 to 200, and `total`. */
class WithdrawalQueueSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private val tag   = UUID.randomUUID().toString.take(6)
  private val start = Instant.parse("2026-10-01T09:00:00Z")
  private val bank  = TerminalRef("t-1", "sb-1", "Bank", "unityfinance", "EUBANKING", true)
  private val usdt  = TerminalRef("t-2", "sb-1", "Crypto", "cozeni", "USDT", true)
  private val payee = Payee(destinationId = Some("d-1"), holder = Some(Holder(Personal("Ana"), Personal("Silva"))),
    iban = Some(Personal("DE89370400440532013000")))

  private def entity(id: String) = kit.components.forEventSourcedEntity(EntityId(id))
  private def queue              = kit.views.forView(WithdrawalQueue)

  /** Twelve requests of one player each, a minute apart: amounts across the three bands, two terminals, three KYC words. */
  private lazy val opened: Vector[String] =
    (0 until 12).toVector.map { i =>
      val id = s"q-$tag-$i"
      val amount = Vector(10000L, 100000L, 900000L)(i % 3)
      entity(id).call(WithdrawalRequest.open).invoke(Open(id, s"qp-$tag-$i", amount, amount, "EUR", if i % 2 == 0 then bank else usdt,
        payee, Forfeit(), Vector("verified", "pending", "unverified")(i % 3), start.plus(Duration.ofMinutes(i.toLong)))): Unit
      id
    }

  private def mine(f: WithdrawalQueue.Filter) = f.copy(player = None)
  private def ours(rows: Vector[QueueRow])    = rows.filter(_.requestId.startsWith(s"q-$tag-"))

  override def beforeAll(): Unit =
    super.beforeAll()
    opened.zipWithIndex.collect { case (id, i) if i % 4 == 3 => id }
      .foreach(id => entity(id).call(WithdrawalRequest.approve).invoke(Approve("op-1", start)): Unit)
    kit.eventually("every row") {
      Option.when(ours(queue.ordered(WithdrawalQueue.Filter(status = None).condition, com.thinkmorestupidless.ankka.runtime.SqlFragment.raw("1"), 10000)).size == 12 &&
        ours(queue.ordered(WithdrawalQueue.Filter(status = Some("approved")).condition, com.thinkmorestupidless.ankka.runtime.SqlFragment.raw("1"), 10000)).size == 3)(())
    }

  test("a row holds the request's columns, the IBAN as its last four and no personal value"):
    val row = queue.get(opened.head).get
    assertEquals((row.playerId, row.amount, row.terminalName, row.method, row.destinationLastFour, row.status, row.amountBand),
      (s"qp-$tag-0", 10000L, "Bank", "EUBANKING", Some("3000"), "pending_review", "lt500"))
    val text = String(QueueRow.given_Serializer_QueueRow.toBytes(row), java.nio.charset.StandardCharsets.UTF_8)
    assert(!text.contains("Silva") && !text.contains("DE89"), text)

  test("each filter, newest first"):
    def rows(f: WithdrawalQueue.Filter) = ours(WithdrawalQueue.page(queue, mine(f), 1, 200).rows).map(_.requestId)
    val pending = opened.zipWithIndex.collect { case (id, i) if i % 4 != 3 => id }.reverse
    assertEquals(rows(WithdrawalQueue.Filter()), pending)
    assertEquals(rows(WithdrawalQueue.Filter(status = Some("approved"))).size, 3)
    assertEquals(rows(WithdrawalQueue.Filter(status = None)).size, 12)
    assertEquals(rows(WithdrawalQueue.Filter(status = None, band = Some("gt5000"))).toSet, Set(2, 5, 8, 11).map(i => s"q-$tag-$i"))
    assertEquals(rows(WithdrawalQueue.Filter(status = None, method = Some("USDT"))).size, 6)
    assertEquals(rows(WithdrawalQueue.Filter(status = None, kyc = Some("pending"))).size, 4)
    assertEquals(WithdrawalQueue.byPlayer(queue, s"qp-$tag-5").map(_.requestId), Vector(s"q-$tag-5"))

  test("pages of 1 to 200 by newest first, with the filter's total"):
    val f     = WithdrawalQueue.Filter(status = None, method = Some("EUBANKING"))
    val first = WithdrawalQueue.page(queue, f, 1, 1)
    assertEquals((first.pageSize, first.rows.size), (1, 1))
    assert(first.total >= 6)
    val big = WithdrawalQueue.page(queue, f, 1, 5000)
    assertEquals(big.pageSize, 200)
    val small = WithdrawalQueue.page(queue, f, 0, 0)
    assertEquals((small.page, small.pageSize), (1, 1))
    val two = WithdrawalQueue.page(queue, f, 2, 1)
    assertNotEquals(two.rows.map(_.requestId), first.rows.map(_.requestId))
