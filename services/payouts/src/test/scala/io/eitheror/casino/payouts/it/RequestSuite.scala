package io.eitheror.casino.payouts.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.player.*
import io.eitheror.casino.payouts.queue.WithdrawalQueue
import io.eitheror.casino.payouts.request.*
import io.eitheror.casino.payouts.terminals.{Refresh, TerminalCatalogue}
import io.eitheror.casino.payouts.clients.Gateway

import java.util.UUID
import java.util.concurrent.Executors
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** US1 end to end (FR-003, FR-004, SC-005): the request through the route, its facts and its count, a repeat, a crash repaired, and the bound. */
class RequestSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(600, "s")
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    val bank = Gateway.terminal(FakeGateway.terminal("t-bank", "unityfinance", destination = true)).get
    kit.gateway.terminals("EUR", FakeGateway.terminal("t-bank", "unityfinance", destination = true))
    kit.components.forKeyValueEntity(EntityId(kit.slug)).call(TerminalCatalogue.refresh).invoke(Refresh("EUR", Vector(bank), kit.now())): Unit

  private def record(p: String) = kit.components.forEventSourcedEntity(EntityId(p))

  /** A verified player with a saved destination; its id. */
  private def ready(p: String): String =
    record(p).call(PlayerPayouts.seeNames).invoke(SeeNames(Personal("Ana"), Personal("Silva"), kit.now())): Unit
    record(p).call(PlayerPayouts.seeKyc).invoke(SeeKyc(Kyc.Verified, kit.now())): Unit
    val d = s"d-$p"
    record(p).call(PlayerPayouts.saveDestination).invoke(SaveDestination(d, "Ana", "Silva", "DE89370400440532013000", "main", true, kit.now())): Unit
    d

  private def ask(p: String, d: String, key: String = UUID.randomUUID().toString, amount: Long = 5000L): Reply =
    kit.json("POST", kit.playerPath(s"/$p/withdrawal-requests"),
      Json.obj("amount" -> amount, "currency" -> "EUR", "terminalId" -> "t-bank", "destinationId" -> d, "key" -> key), kit.asPlayer(p))

  private def newPlayer() = s"rq-${UUID.randomUUID().toString.take(8)}"

  test("a request holds, opens, is counted, listed in the account and the queue, and is published"):
    val p   = newPlayer()
    val key = UUID.randomUUID().toString
    val r   = ask(p, ready(p), key)
    assertEquals(r.status, 201, r.text)
    val id = r.str("requestId")
    assertEquals(id, io.eitheror.casino.payouts.workflow.WithdrawalWorkflow.requestId(p, key))
    assert(record(p).call(PlayerPayouts.get).invoke().recent.exists(_.id == id), "counted before the answer")
    kit.eventually("listed") {
      val list = kit.request("GET", kit.playerPath(s"/$p/withdrawal-requests"), None, kit.asPlayer(p)).json.objects("requests")
      Option.when(list.exists(_.text("requestId").contains(id)) && kit.views.forView(WithdrawalQueue).get(id).isDefined)(())
    }
    kit.eventually("published")(kit.facts(CasinoWithdrawals).collectFirst { case f: WithdrawalRequested if f.payoutId == id => f })

  test("a repeat with the same key answers the first, and holds once; another body under it is KEY_REUSED"):
    val p   = newPlayer()
    val d   = ready(p)
    val key = UUID.randomUUID().toString
    val first  = ask(p, d, key)
    val repeat = ask(p, d, key)
    assertEquals((repeat.status, repeat.str("requestId"), repeat.str("createdAt")), (201, first.str("requestId"), first.str("createdAt")))
    assertEquals(kit.wallet.calls("").size, 1)
    val other = ask(p, d, key, amount = 6000L)
    assertEquals((other.status, other.str("error")), (409, "KEY_REUSED"))

  test("a request opened but not counted, as after a crash before the answer, is counted by RequestsConsumer"):
    val p  = newPlayer()
    val id = s"crash-$p"
    record(id).call(WithdrawalRequest.open).invoke(Open(id, p, 5000L, 5000L, "EUR",
      TerminalRef("t-bank", "sb-1", "Bank", "unityfinance", "EUBANKING", true), Payee(), Forfeit(), "verified", kit.now())): Unit
    kit.eventually("counted")(record(p).call(PlayerPayouts.get).invoke().recent.find(_.id == id))

  test("SC-005: 1,000 requests, p99 under 2 s, the preview and the hold the only calls out"):
    val players = (1 to 1000).map(_ => newPlayer()).toVector
    val pool    = Executors.newFixedThreadPool(16)
    given ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      val dests = Await.result(Future.traverse(players)(p => Future(p -> ready(p))), 5.minutes)
      kit.gateway.clearReceived()
      kit.wallet.clearReceived()
      val timed = Await.result(Future.traverse(dests) { (p, d) =>
        Future {
          val t0 = System.nanoTime()
          val r  = ask(p, d)
          (r.status, (System.nanoTime() - t0) / 1000000L, r.text)
        }
      }, 5.minutes)
      assertEquals(timed.map(_._1).distinct, Vector(201), timed.find(_._1 != 201).map(_._3).getOrElse(""))
      val p99 = timed.map(_._2).sorted.apply((timed.size * 99) / 100 - 1)
      assert(p99 < 2000L, s"p99 $p99 ms")
      // The catalogue's own refresh lists the terminals; a request asks the gateway nothing.
      assertEquals(kit.gateway.received.filterNot(_.path == "/v1/payments/withdrawal-terminals").size, 0)
      val calls = kit.wallet.received.map(r => if r.path.endsWith("/holds/preview") then "preview" else if r.path.endsWith("/holds") then "hold" else r.path)
      assertEquals(calls.groupBy(identity).view.mapValues(_.size).toMap, Map("preview" -> 1000, "hold" -> 1000))
    finally pool.shutdown()
