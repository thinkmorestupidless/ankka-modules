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

  test("oldest first by when the request was made, a page the same slice from the other end (spec 030 Y2)"):
    // A method only this test's requests have, so the queue's other rows are outside the filter.
    val own  = TerminalRef("t-o", "sb-1", "Own", "unityfinance", s"OWN-$tag", true)
    val made = Vector(3, 1, 2).map { m =>
      val id = s"o-$tag-$m"
      entity(id).call(WithdrawalRequest.open).invoke(Open(id, s"op-$tag-$m", 5000L, 5000L, "EUR", own, payee, Forfeit(), "verified",
        start.plus(Duration.ofMinutes(m.toLong)))): Unit
      id
    }
    val f = WithdrawalQueue.Filter(status = None, method = Some(s"OWN-$tag"))
    kit.eventually("own rows")(Option.when(WithdrawalQueue.page(queue, f, 1, 200).total == 3)(()))
    val ordered = Vector(1, 2, 3).map(m => s"o-$tag-$m")
    assertEquals(WithdrawalQueue.page(queue, f, 1, 200, oldest = true).rows.map(_.requestId), ordered)
    assertEquals(WithdrawalQueue.page(queue, f, 1, 200).rows.map(_.requestId), ordered.reverse)
    val second = WithdrawalQueue.page(queue, f, 2, 2, oldest = true)
    assertEquals((second.rows.map(_.requestId), second.total), (Vector(s"o-$tag-3"), 3L))
    assertEquals(made.toSet, ordered.toSet)

  test("counts are the brand's requests pending review and its approved ones whose payment needs review (spec 030 Y2)"):
    val pending = WithdrawalQueue.Filter()
    val doubt   = WithdrawalQueue.Filter(status = Some("approved"), payoutStatus = Some("needs_review"))
    def now() = (queue.count(pending.condition), queue.count(doubt.condition))
    val id = s"n-$tag"
    entity(id).call(WithdrawalRequest.open).invoke(Open(id, s"np-$tag", 5000L, 5000L, "EUR", bank, payee, Forfeit(), "verified", start)): Unit
    entity(id).call(WithdrawalRequest.approve).invoke(Approve("op-1", start)): Unit
    entity(id).call(WithdrawalRequest.initiated).invoke(Initiated("initiated", Some(s"ref-$id"), start)): Unit
    entity(id).call(WithdrawalRequest.needsReview).invoke(NeedsReview("in_doubt", start)): Unit
    kit.eventually("in doubt")(queue.get(id).filter(_.payoutStatus == "needs_review"))
    val c = WithdrawalQueue.counts(queue)
    assertEquals((c.pendingReview, c.needsReview), now())
    assert(c.needsReview >= 1 && c.pendingReview >= 8, c.toString)

  test("several players by their ids, separated by commas (spec 030 Y2); none named is no row"):
    val f = WithdrawalQueue.Filter(status = None, player = Some(s"qp-$tag-1, qp-$tag-4,qp-$tag-1,"))
    assertEquals(WithdrawalQueue.page(queue, f, 1, 200).rows.map(_.requestId), Vector(s"q-$tag-4", s"q-$tag-1"))
    assertEquals(WithdrawalQueue.page(queue, f, 1, 200).total, 2L)
    assertEquals(WithdrawalQueue.page(queue, WithdrawalQueue.Filter(status = None, player = Some(" , ")), 1, 200).total, 0L)

  test("a player's requests in every state a page at a time, newest first, with their total (spec 030 Y4)"):
    val player = s"qh-$tag"
    val ids = (0 until 3).toVector.map { i =>
      val id = s"qh-$tag-$i"
      entity(id).call(WithdrawalRequest.open).invoke(Open(id, player, 1000L, 1000L, "EUR", bank, payee, Forfeit(), "verified",
        start.plus(Duration.ofMinutes(i.toLong)))): Unit
      id
    }
    entity(ids(0)).call(WithdrawalRequest.cancel).invoke(Cancel(Actor.Player, None, start)): Unit
    entity(ids(1)).call(WithdrawalRequest.approve).invoke(Approve("op-1", start)): Unit
    kit.eventually("the player's rows")(Option.when(WithdrawalQueue.ofPlayer(queue, player, 1, 200).rows.map(_.status).toSet ==
      Set("cancelled", "approved", "pending_review"))(()))
    val first  = WithdrawalQueue.ofPlayer(queue, player, 1, 2)
    val second = WithdrawalQueue.ofPlayer(queue, player, 2, 2)
    assertEquals((first.rows.map(_.requestId), first.total, first.pageSize), (Vector(ids(2), ids(1)), 3L, 2))
    assertEquals((second.rows.map(_.requestId), second.page), (Vector(ids(0)), 2))
