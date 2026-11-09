package io.eitheror.casino.support.clients

import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.support.{Json, Settings}

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable

/** FR-012, R15: 5 s an attempt, one more on a timeout and never on a refusal, and the calls' paths and keys. */
class NeighbourSuite extends munit.FunSuite:

  /** A project whose services answer from a script, recording every request. */
  private final class Scripted(answers: String => Vector[Either[Throwable, (Int, String)]]) extends ServiceClients:
    val calls = mutable.ArrayBuffer.empty[(String, String, String, Option[String])]
    private val left = mutable.Map.empty[String, Vector[Either[Throwable, (Int, String)]]]
    def apply(project: String, name: String): ServiceClient = apply(name)
    def apply(name: String): ServiceClient = new ServiceClient:
      def target = s"local/$name"
      def request(method: String, path: String, body: Option[Array[Byte]], contentType: Option[String], headers: Seq[(String, String)]) =
        calls += ((name, method, path, body.map(String(_, UTF_8))))
        val queue = left.getOrElseUpdate(name, answers(name))
        val next  = queue.headOption.getOrElse(Right((200, "{}")))
        left(name) = queue.drop(1)
        next.fold(e => throw e, (s, t) => ServiceResponse(s, "application/json", t.getBytes(UTF_8), Vector.empty))

  private val timeout = ServiceUnanswered("wallet", "the timeout passed")

  test("each attempt waits the configured 5 s"):
    val conf = scala.io.Source.fromResource("application.conf").mkString
    assert(conf.contains("ankka.service-client.timeout = 5s"), conf)

  test("a timeout is tried once more, and the second answer is the call's"):
    val s = Scripted(_ => Vector(Left(timeout), Right((200, """{"realBalance":1}"""))))
    val r = Neighbour(s, "wallet").get("/x")
    assertEquals(r, Called.Answered(200, """{"realBalance":1}"""))
    assertEquals(s.calls.size, 2)

  test("two timeouts are TimedOut, after two attempts and no third"):
    val s = Scripted(_ => Vector(Left(timeout), Left(timeout), Right((200, "{}"))))
    assertEquals(Neighbour(s, "wallet").get("/x"), Called.TimedOut)
    assertEquals(s.calls.size, 2)

  test("a refusal is an answer carried as a value, not tried again"):
    val s = Scripted(_ => Vector(Right((409, """{"error":"break_active","message":"…"}"""))))
    val r = Neighbour(s, "responsible-gambling").put("/x", Json.obj("key" -> "ap-1"))
    assertEquals(r.refusal, Some("break_active"))
    assertEquals(s.calls.size, 1)
    assertEquals(Called.Answered(409, """{"code":"KEY_REUSED"}""").refusal, Some("KEY_REUSED"))

  test("an unreachable service is Unavailable, not tried again"):
    val s = Scripted(_ => Vector(Left(ServiceUnresolvable("wallet", "no such service"))))
    assertEquals(Neighbour(s, "wallet").get("/x"), Called.Unavailable)
    assertEquals(s.calls.size, 1)

  test("each client's route, the conversation's player in it, and the approval as the write's key"):
    val s = Scripted(_ => Vector.empty)
    val n = Neighbours.http(s, Settings(brand = "goldvibe"))
    n.players.status("p-1"): Unit
    n.players.agentProfile("p-1", Set("lastName", "email")): Unit
    n.players.endSession("gs-1", "ap-1"): Unit
    n.wallet.balance("p-1", "GBP"): Unit
    n.wallet.transactions("p-1", "GBP", 10): Unit
    n.wallet.holdPreview("p-1", "GBP", 5000L): Unit
    n.payouts.requestWithdrawal("p-1", 5000L, "GBP", "ap-2"): Unit
    n.rewards.grants("p-1"): Unit
    n.rg.limits("p-1", "GBP"): Unit
    n.rg.setLimit("p-1", "DEPOSIT_WEEKLY", 10000L, "GBP", "ap-3"): Unit
    n.rg.coolOff("p-1", "2026-12-01T00:00:00Z", "ap-4"): Unit
    n.rg.exclude("p-1", "ap-5"): Unit
    assertEquals(s.calls.map(c => (c._1, c._2, c._3)).toVector, Vector(
      ("players", "GET", "/v1/casino/brands/goldvibe/players/p-1/status"),
      ("players", "GET", "/v1/casino/brands/goldvibe/players/p-1/agent-profile?fields=email,lastName"),
      ("players", "POST", "/v1/casino/brands/goldvibe/players/game-sessions/gs-1/end"),
      ("wallet", "GET", "/v1/casino/brands/goldvibe/wallets/p-1/GBP/balance"),
      ("wallet", "GET", "/v1/casino/brands/goldvibe/wallets/p-1/GBP/transactions?page=0&pageSize=10"),
      ("wallet", "GET", "/v1/casino/brands/goldvibe/wallets/p-1/GBP/holds/preview?amount=5000"),
      ("payouts", "POST", "/v1/casino/payouts-service/goldvibe/players/p-1/withdrawal-requests"),
      ("rewards", "GET", "/v1/casino/brands/goldvibe/rewards/p-1/grants"),
      ("responsible-gambling", "GET", "/v1/casino/rg-service/goldvibe/p-1/limits?currency=GBP"),
      ("responsible-gambling", "PUT", "/v1/casino/rg-service/goldvibe/p-1/limits/DEPOSIT_WEEKLY"),
      ("responsible-gambling", "PUT", "/v1/casino/rg-service/goldvibe/p-1/cooling-off"),
      ("responsible-gambling", "PUT", "/v1/casino/rg-service/goldvibe/p-1/permanent-exclusion")))
    val keys = s.calls.flatMap(_._4).flatMap(Json.parse(_)).flatMap(b => Option(b.get("approvalId")).orElse(Option(b.get("key")))).map(_.asText)
    assertEquals(keys.toVector, Vector("ap-1", "ap-2", "ap-3", "ap-4", "ap-5"))
