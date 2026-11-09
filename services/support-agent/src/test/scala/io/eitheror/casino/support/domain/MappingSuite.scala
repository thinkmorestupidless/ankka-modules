package io.eitheror.casino.support.domain

import io.eitheror.casino.support.Json

/** contracts/neighbours.md *Reads*, with the original's cases (`test_read_tools.py`). */
class MappingSuite extends munit.FunSuite:
  private def j(text: String) = Json.parse(text).get

  test("the player's state: status, verification, display name, currency, and only the named fields"):
    val o = Mapping.playerState(j("""{"status":"active","displayName":"Ada","verification":"verified","currency":"GBP"}"""),
      Some(j("""{"firstName":"Ada","email":"player@example.com"}""")))
    assertEquals(o.get("status").asText, "active")
    assertEquals(o.get("currency").asText, "GBP")
    assertEquals(o.get("email").asText, "player@example.com")
    assertEquals(Mapping.playerState(j("""{"status":"active"}"""), None).has("email"), false)

  test("the balance in minor units, and a wallet that is not there yet is zeros"):
    val o = Mapping.walletBalance(Some(j("""{"realBalance":10000,"restrictedBalance":5000,"currency":"GBP","primary":null}""")), "GBP")
    assertEquals((o.get("real_balance").asLong, o.get("restricted_balance").asLong, o.get("currency").asText), (10000L, 5000L, "GBP"))
    val none = Mapping.walletBalance(None, "EUR")
    assertEquals((none.get("real_balance").asLong, none.get("restricted_balance").asLong, none.get("currency").asText), (0L, 0L, "EUR"))

  test("only active grants, in the original's names; none is an empty list"):
    val grants = j("""{"items":[
      {"rewardId":"bonus:123","offerName":"Welcome Bonus","status":"ACTIVE","wageringRequirement":50000,"wageredAmount":10000,"expiresAt":"2026-12-31T00:00:00Z"},
      {"rewardId":"bonus:9","offerName":"Old","status":"expired"}]}""")
    val active = Mapping.activeBonuses(grants)
    assertEquals(active.map(_.get("bonus_id").asText), Vector("bonus:123"))
    assertEquals((active.head.get("name").asText, active.head.get("wagering_progress").asLong), ("Welcome Bonus", 10000L))
    assertEquals(Mapping.activeBonuses(j("""{"items":[]}""")), Vector.empty)

  test("transactions carry the payment reference as reference, and never the reason"):
    val rows = Mapping.transactions(j("""{"transactions":[{"transactionId":"t-1","type":"deposit","amount":2500,"currency":"GBP",
      "status":"completed","createdAt":"2026-11-01T10:00:00Z","paymentReference":"PSP-7","reason":"a note about the player"}]}"""))
    assertEquals(rows.head.get("reference").asText, "PSP-7")
    assertEquals(rows.head.get("amount").asLong, 2500L)
    assert(!rows.head.has("reason") && !Json.render(rows.head).contains("a note"))

  test("game sessions name the game by id; none is an empty list"):
    val s = Mapping.sessions(j("""{"sessions":[{"sessionId":"gs-1","gameId":"starburst","status":"active","launchedAt":"2026-11-09T09:00:00Z","endedAt":null}]}"""))
    assertEquals((s.head.get("session_id").asText, s.head.get("game_id").asText, s.head.get("closed_at").isNull), ("gs-1", "starburst", true))
    assertEquals(Mapping.sessions(j("""{"sessions":[]}""")), Vector.empty)

  test("RG's status: no limit and not excluded"):
    val o = Mapping.rgStatus(j("""{"limits":[],"pendingChanges":[],"activeRestrictions":[],"coolingOffUntil":null}"""))
    assertEquals(o.get("self_excluded").asBoolean, false)
    assert(o.get("deposit_limit_amount").isNull)
    assertEquals(o.get("deposit_limit_pending").asBoolean, false)

  test("RG's status: a deposit limit, its pending raise, a break and the exclusion"):
    val o = Mapping.rgStatus(j("""{"limits":[{"limitType":"LOSS_DAILY","value":100},{"limitType":"DEPOSIT_WEEKLY","value":5000,"currency":"GBP"}],
      "pendingChanges":[{"limitType":"DEPOSIT_WEEKLY","appliesAt":"2026-11-10T10:00:00Z","pendingValue":"9000"}],
      "activeRestrictions":["COOLING_OFF","SELF_EXCLUDED"],"coolingOffUntil":"2026-11-20T00:00:00Z"}"""))
    assertEquals((o.get("deposit_limit_amount").asLong, o.get("deposit_limit_period").asText), (5000L, "weekly"))
    assertEquals((o.get("deposit_limit_pending").asBoolean, o.get("deposit_limit_pending_amount").asLong), (true, 9000L))
    assertEquals(o.get("deposit_limit_pending_effective").asText, "2026-11-10T10:00:00Z")
    assertEquals((o.get("cool_off_until").asText, o.get("self_excluded").asBoolean), ("2026-11-20T00:00:00Z", true))
