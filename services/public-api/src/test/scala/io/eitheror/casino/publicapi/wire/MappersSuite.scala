package io.eitheror.casino.publicapi.wire

import com.thinkmorestupidless.ankka.core.{DataSubject, Personal}
import io.eitheror.casino.publicapi.records.{BetState, BonusAwardState, PlayerState, RoundState, SessionState, TransactionState}

import java.time.Instant

/** Each record as the contract's shape (contracts/fields.md): money, enums, parents' ids, optional fields omitted. */
class MappersSuite extends munit.FunSuite:
  private val t    = Instant.parse("2026-11-01T10:00:00Z")
  private val json = com.fasterxml.jackson.databind.ObjectMapper()
  private def p(v: String) = Personal(DataSubject("player/p"), v)

  private val player = PlayerState("p", Some(t), "self_excluded", "pending", Some("BR"), Some("EUR"), None, email = Some(p("a@b.c")),
    dateOfBirth = Some(p("1990-04-02T00:00:00Z")), city = Some(Personal.Erased(DataSubject("player/p"))))

  test("a player: statuses mapped, optional fields omitted, identifying fields only where allowed and present") {
    val (none, servedNone) = Records.player(player, "goldvibe", Set.empty)
    val j = json.readTree(none)
    assertEquals((j.path("status").asText(), j.path("kycStatus").asText(), j.path("brandId").asText()), ("SELF_EXCLUDED", "PENDING", "goldvibe"))
    assert(!j.has("language") && !j.has("email"))
    assertEquals(servedNone, Vector.empty)
    val (all, served) = Records.player(player, "goldvibe", Set("email", "dateOfBirth", "city"))
    val k = json.readTree(all)
    assertEquals((k.path("email").asText(), k.path("dateOfBirth").asText()), ("a@b.c", "1990-04-02"))
    assert(!k.has("city"), "an erased value is absent with every key")
    assertEquals(served, Vector("email", "dateOfBirth"))
  }

  test("a status with no mapping is null, never coerced") {
    assert(json.readTree(Records.player(player.copy(status = "blocked"), "g", Set.empty)._1).path("status").isNull)
  }

  test("money is a two-decimal major-unit string; a signed adjustment keeps its sign") {
    val tx = TransactionState("t", "p", "admin_adjustment", -1250, "EUR", Some("MAIN"), "completed", Some(t))
    val j  = json.readTree(Records.transaction(tx, "g"))
    assertEquals((j.path("amount").asText(), j.path("type").asText(), j.path("createdAt").asText()), ("-12.50", "ADMIN_ADJUSTMENT", t.toString))
    assert(!j.has("roundId") && !j.has("paymentRef"))
  }

  test("a round and a bet carry their parents' ids; a session's status is derived") {
    val r = json.readTree(Records.round(RoundState("r", "k", "p", Some("s"), "g1", "EUR", startedAt = Some(t), stakeCents = 100, status = "open"), "g"))
    assertEquals((r.path("sessionId").asText(), r.path("stakeTotal").asText(), r.path("winTotal").asText(), r.path("status").asText()),
      ("s", "1.00", "0.00", "open"))
    val b = json.readTree(Records.bet(BetState("b", "p", "k", Some("r"), Some("s"), None, 5, "EUR", "", "open", Some(t)), "g"))
    assertEquals((b.path("roundId").asText(), b.path("sessionId").asText()), ("r", "s"))
    assert(b.path("fundedFrom").isNull, "an empty funding word is null")
    assertEquals(json.readTree(Records.session(SessionState("s", "p", None, Some(t), Some(t)), "g")).path("status").asText(), "CLOSED")
  }

  test("a bonus award: value, requirement, progress and status") {
    val b = json.readTree(Records.bonusAward(BonusAwardState("b", "p", Some("o"), None, None, Some(1000), Some("EUR"), Some(30000), None,
      Some("ACTIVE"), Some(t), grantedAt = Some(t)), "g"))
    assertEquals((b.path("value").asText(), b.path("wageringRequirement").asText(), b.path("status").asText()), ("10.00", "300.00", "ACTIVE"))
    assert(!b.has("wageredAmount") && !b.has("expiresAt"))
  }
