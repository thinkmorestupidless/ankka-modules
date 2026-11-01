package io.eitheror.casino.payouts.clients

import io.eitheror.casino.payouts.Json

import java.time.Duration

/** Every answer of research R7 and R8, decoded as the workflow reads it. */
class GatewaySuite extends munit.FunSuite:
  private def answered(status: Int, body: String) = Reply.Answered(status, Json.parse(body), body)
  private def ok(status: String, ref: String = "", reason: String = "") =
    answered(200, Json.render(Json.obj("requestId" -> "r-1", "status" -> status, "providerReference" -> Option(ref).filter(_.nonEmpty),
      "reason" -> Option(reason).filter(_.nonEmpty))))

  test("R7: each initiation answer"):
    assertEquals(Gateway.initiation(ok("completed", "x")), Initiation.Completed(Some("x")))
    assertEquals(Gateway.initiation(ok("initiated", "x")), Initiation.Initiated(Some("x")))
    assertEquals(Gateway.initiation(ok("already_initiated")), Initiation.AlreadyInitiated(None))
    assertEquals(Gateway.initiation(ok("rejected", reason = "unknown_terminal")), Initiation.Rejected("unknown_terminal"))
    assertEquals(Gateway.initiation(ok("in_doubt", reason = "timeout")), Initiation.InDoubt("timeout"))
    assertEquals(Gateway.initiation(answered(409, """{"error":"initiation_in_progress"}""")), Initiation.InProgress)

  test("R7: 400, 403 and 502 provider_not_configured reached no provider"):
    assertEquals(Gateway.initiation(answered(400, """{"error":"bad_request"}""")), Initiation.Refused(400, "bad_request"))
    assertEquals(Gateway.initiation(answered(403, """{"error":"forbidden"}""")), Initiation.Refused(403, "forbidden"))
    assertEquals(Gateway.initiation(answered(502, """{"error":"provider_not_configured"}""")), Initiation.Refused(502, "provider_not_configured"))

  test("R7: unreachable, a 5xx, another 409, or a body it cannot read is unreadable, never a refusal"):
    for reply <- Vector(Reply.Unreachable("down"), answered(503, "{}"), answered(502, """{"error":"other"}"""),
        answered(409, """{"error":"conflict"}"""), answered(200, "not json"), ok("surprising")) do
      assert(Gateway.initiation(reply).isInstanceOf[Initiation.Unreadable], reply.toString)

  test("R8: each status answer; 502 status_unavailable and the unreadable wait; no session is no record"):
    assertEquals(Gateway.status(ok("completed", "x")), PayoutStatus.Completed(Some("x")))
    assertEquals(Gateway.status(ok("failed", "x", "declined")), PayoutStatus.Failed(Some("x"), "declined"))
    assertEquals(Gateway.status(ok("reversed", "x")), PayoutStatus.Reversed(Some("x"), "reversed"))
    assertEquals(Gateway.status(ok("pending")), PayoutStatus.Pending)
    assertEquals(Gateway.status(ok("no_record")), PayoutStatus.NoRecord)
    assertEquals(Gateway.status(answered(404, """{"error":"not_found"}""")), PayoutStatus.NoRecord)
    assert(Gateway.status(answered(502, """{"error":"status_unavailable"}""")).isInstanceOf[PayoutStatus.Unavailable])
    assert(Gateway.status(Reply.Unreachable("down")).isInstanceOf[PayoutStatus.Unavailable])

  test("a listing row: the fields eligibility and the payout read, the coins normalised, the window parsed"):
    val row = Json.parse("""{"id":"t-1","subBrandId":"sb-9","name":"Crypto","scheme":"USDT","gateway":"COZENI","active":true,
      "supportsWithdrawal":true,"requiresPayoutDestination":false,"coins":["usdt"," BTC","usdt"],"hasStatus":false,
      "reviewWindow":"PT24H"}""").get
    val t = Gateway.terminal(row).get
    assertEquals((t.subBrandId, t.coins, t.reviewWindow, t.crypto), ("sb-9", Vector("USDT", "BTC"), Some(Duration.ofHours(24)), true))
    assertEquals(Gateway.terminal(Json.parse("""{"name":"no id"}""").get), None)
