package io.eitheror.casino.support.agent

import com.thinkmorestupidless.ankka.agent.Json as AgentJson
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.clients.Called
import io.eitheror.casino.support.config.ConfigBody
import io.eitheror.casino.support.conversation.Outcome

import java.time.Instant

/**
 * The six read tools (contracts/tools.md, contracts/neighbours.md *Reads*; T025): each calls its neighbour for the
 * bound player, answers the original's fields, and records its call; one that does not answer is told to the model
 * as "cannot be read right now", and the turn goes on (FR-012). No read takes a player or a currency (FR-005, FR-011).
 */
class ReadToolsSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-09T10:00:00Z")

  private def setUp(body: ConfigBody = ConfigBody()) =
    val r = Recorded()
    r.ok("status", """{"status":"ACTIVE","verification":"VERIFIED","displayName":"Ann","currency":"GBP"}""")
    (r, Tools(r.neighbours, r.trail, "p-1", body, () => at))

  private def run(t: Tools, name: String, args: String = "{}"): Either[String, String] =
    t.reads.find(_.name == name).getOrElse(fail(s"no read tool $name")).invoke(AgentJson.parse(args).toOption.get)

  private def answered(t: Tools, name: String, args: String = "{}") =
    Json.parse(run(t, name, args).fold(e => fail(s"$name refused: $e"), identity)).get

  test("the six reads, by the original's names, none taking a player or a currency"):
    val (_, t) = setUp()
    assertEquals(t.reads.map(_.name), Vector("get_player_state", "get_wallet_balance", "get_active_bonuses",
      "get_transaction_history", "get_session_history", "get_rg_status"))
    val params = t.reads.flatMap(tool => tool.spec.inputSchema("properties").collect { case AgentJson.Obj(fs) => fs.keySet }.getOrElse(Set.empty))
    assertEquals(params.toSet, Set("limit"))

  test("get_player_state reads players' status for the bound player and records the call"):
    val (r, t) = setUp()
    val s = answered(t, "get_player_state")
    assertEquals((s.text("status"), s.text("displayName"), s.text("currency")), (Some("ACTIVE"), Some("Ann"), Some("GBP")))
    assertEquals(r.calls.toVector, Vector("players status p-1"))
    assertEquals(r.toolCalls.map(c => (c.tool, c.outcome, c.approvalId)).toVector, Vector(("get_player_state", Outcome.Ok, None)))

  test("get_wallet_balance reads the wallet in the player's currency, in minor units; no wallet is zeros"):
    val (r, t) = setUp()
    r.ok("balance", """{"realBalance":5000,"restrictedBalance":250,"currency":"GBP"}""")
    val b = answered(t, "get_wallet_balance")
    assertEquals((b.long("real_balance"), b.long("restricted_balance"), b.text("currency")), (Some(5000L), Some(250L), Some("GBP")))
    assert(r.calls.contains("wallet balance p-1 GBP"), r.calls)
    r.scripted.update("balance", Called.Answered(404, """{"error":"wallet_not_found"}"""))
    assertEquals(answered(t, "get_wallet_balance").long("real_balance"), Some(0L))

  test("get_active_bonuses keeps the active grants, in the original's fields"):
    val (r, t) = setUp()
    r.ok("grants", """{"items":[{"rewardId":"g-1","offerName":"Welcome","wageringRequirement":3000,"wageredAmount":1000,
      |"expiresAt":"2026-12-01T00:00:00Z","status":"active"},{"rewardId":"g-2","status":"expired"}]}""".stripMargin)
    val bonuses = answered(t, "get_active_bonuses").objects("bonuses")
    assertEquals(bonuses.map(_.text("bonus_id")), Vector(Some("g-1")))
    assertEquals(bonuses.head.long("wagering_progress"), Some(1000L))
    assert(r.calls.contains("rewards grants p-1"), r.calls)

  test("get_transaction_history asks for 10 by default, at most 50, and never passes a reason"):
    val (r, t) = setUp()
    r.ok("transactions", """{"transactions":[{"transactionId":"t-1","type":"DEPOSIT","amount":2500,"currency":"GBP",
      |"status":"COMPLETED","createdAt":"2026-11-08T10:00:00Z","paymentReference":"ref-1","reason":"internal"}]}""".stripMargin)
    val txs = answered(t, "get_transaction_history").objects("transactions")
    assertEquals((txs.map(_.text("transaction_id")), txs.head.has("reason")), (Vector(Some("t-1")), false))
    assert(r.calls.contains("wallet transactions p-1 GBP 10"), r.calls)
    answered(t, "get_transaction_history", """{"limit":50}"""): Unit
    assert(r.calls.contains("wallet transactions p-1 GBP 50"), r.calls)
    val before = r.calls.size
    assert(run(t, "get_transaction_history", """{"limit":51}""").isLeft)
    assert(run(t, "get_transaction_history", """{"limit":0}""").isLeft)
    assertEquals(r.calls.size, before)

  test("get_session_history reads players' game sessions"):
    val (r, t) = setUp()
    r.ok("sessions", """{"sessions":[{"sessionId":"gs-1","gameId":"starburst","status":"ACTIVE","launchedAt":"2026-11-09T09:00:00Z"}]}""")
    assertEquals(answered(t, "get_session_history").objects("sessions").map(_.text("game_id")), Vector(Some("starburst")))
    assert(r.calls.contains("players sessions p-1"), r.calls)

  test("get_rg_status reads responsible gambling's limits in the player's currency"):
    val (r, t) = setUp()
    r.ok("limits", """{"limits":[{"limitType":"DEPOSIT_DAILY","value":5000}],"pendingChanges":[],
      |"coolingOffUntil":"2026-12-01T00:00:00Z","activeRestrictions":["COOLING_OFF"]}""".stripMargin)
    val s = answered(t, "get_rg_status")
    assertEquals((s.long("deposit_limit_amount"), s.text("deposit_limit_period"), s.text("cool_off_until"), s.bool("self_excluded")),
      (Some(5000L), Some("daily"), Some("2026-12-01T00:00:00Z"), Some(false)))
    assert(r.calls.contains("rg limits p-1 GBP"), r.calls)

  test("a read that times out is told to the model as cannot be read right now, and the turn goes on"):
    val (r, t) = setUp()
    r.scripted.update("balance", Called.TimedOut)
    assertEquals(run(t, "get_wallet_balance"), Right(Context.Unreadable))
    assertEquals(r.toolCalls.last.outcome, Outcome.TimedOut)
    r.scripted.update("grants", Called.Unavailable)
    assertEquals(run(t, "get_active_bonuses"), Right(Context.Unreadable))
    assertEquals(r.toolCalls.last.outcome, Outcome.Unavailable)
