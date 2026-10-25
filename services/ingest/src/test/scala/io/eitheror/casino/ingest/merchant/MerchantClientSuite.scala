package io.eitheror.casino.ingest.merchant

import com.thinkmorestupidless.ankka.testkit.ScriptedServices
import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.Json.*

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

/** contracts/merchant.md: each command's path and body; a refusal is an answer, a lost connection a failed call. */
class MerchantClientSuite extends munit.FunSuite:

  private def scripted(status: Int = 200, body: String = "{}") =
    val s = ScriptedServices().answer("merchant")(_ => ScriptedServices.json(body, status))
    (s, MerchantClient.http(s, "merchant"))

  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 5.seconds)

  test("the create is POST /v1/cashier/sessions with the command as its body") {
    val (s, m) = scripted(body = """{"session_token":"tok"}""")
    val a      = await(m.create(Json.obj("subBrandId" -> "sb-1", "amount" -> 5000L)))
    assertEquals((a.status, a.text("session_token")), (200, Some("tok")))
    val r = s.requests.head
    assertEquals((r.method, r.path, r.contentType), ("POST", "/v1/cashier/sessions", Some("application/json")))
    assertEquals(Json.parse(r.text).flatMap(_.long("amount")), Some(5000L))
  }

  test("the submit, the abandon and the read go to their paths, the ticket and id escaped") {
    val (s, m) = scripted()
    await(m.submit("t 1", Json.obj("selectedTerminalEntityId" -> "sim-eur"))): Unit
    await(m.abandon("t-1")): Unit
    await(m.read("pay-1")): Unit
    assertEquals(s.requests.map(r => r.method -> r.path), Vector("POST" -> "/v1/cashier/sessions/t%201/submit",
      "POST" -> "/v1/cashier/sessions/t-1/abandon", "GET" -> "/v1/cashier/payments/pay-1"))
    assertEquals(Json.parse(s.requests.head.text).flatMap(_.text("selectedTerminalEntityId")), Some("sim-eur"))
  }

  test("a 400 NO_ROUTING_TREE is an answer, not a failure") {
    val (_, m) = scripted(400, """{"error":"NO_ROUTING_TREE"}""")
    val a      = await(m.create(Json.obj()))
    assertEquals((a.status, a.text("error")), (400, Some("NO_ROUTING_TREE")))
  }

  test("a merchant that cannot be reached is a failed call") {
    val m = MerchantClient.http(ScriptedServices().unanswered("merchant"), "merchant")
    assert(Try(await(m.create(Json.obj()))).isFailure)
  }

  test("the key index reads keys[{digest, keyId, subBrandId}]") {
    val (s, m) = scripted(body = """{"keys":[{"digest":"d1","keyId":"k-1","subBrandId":"sb-1"},{"digest":"d2","keyId":"k-2"}]}""")
    assertEquals(await(m.keys()), Vector(KeyEntry("d1", "k-1", "sb-1")))
    assertEquals(s.requests.head.path, "/v1/cashier/keys")
  }
