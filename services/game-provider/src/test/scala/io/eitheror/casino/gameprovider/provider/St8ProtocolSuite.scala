package io.eitheror.casino.gameprovider.provider

import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.provider.st8.*

/** contracts/callbacks.md's protocol rules, row by row. */
class St8ProtocolSuite extends munit.FunSuite:

  private def body(c: Callback, json: String) = CallbackBody(c, Json.parseObject(json).get)

  test("the eight callbacks by their path names") {
    assertEquals(Callback.values.map(_.wire).toSet, Set("check", "balance", "player_profile", "debit", "credit", "cancel", "buyin", "payout"))
    assertEquals(Callback.from("player_profile"), Some(Callback.PlayerProfile))
    assertEquals(Callback.from("launch"), None)
  }

  test("an unreadable body is session_expired, and player_not_found for the profile (FR-004)") {
    assertEquals(St8Protocol.malformedWord(Callback.Debit), "session_expired")
    assertEquals(St8Protocol.malformedWord(Callback.Check), "session_expired")
    assertEquals(St8Protocol.malformedWord(Callback.PlayerProfile), "player_not_found")
    assertEquals(Json.parseObject("[1,2]"), None, "an array is not an object")
    assertEquals(Json.parseObject("not json"), None)
  }

  test("a field the protocol names as a string is read as a number too") {
    val b = body(Callback.Debit, """{"transaction_id": 12345, "round": 99, "token": "tok-1234567"}""")
    assertEquals(b.transactionId, Some("12345"))
    assertEquals(b.round, Some("99"))
  }

  test("round_id is read when round is absent; an empty field is absent") {
    assertEquals(body(Callback.Credit, """{"round_id": "r-1"}""").round, Some("r-1"))
    assertEquals(body(Callback.Credit, """{"round": "", "round_id": "r-2"}""").round, Some("r-2"))
    assertEquals(body(Callback.Credit, """{"token": ""}""").token, None)
  }

  test("fields the protocol does not name are ignored") {
    val b = body(Callback.Debit, """{"transaction_id": "t", "new_field": {"x": 1}, "bonus": null}""")
    assertEquals(b.transactionId, Some("t"))
  }

  test("provider_kind defaults to the callback's own kind") {
    assertEquals(body(Callback.Debit, "{}").kind, Kind.Debit)
    assertEquals(body(Callback.Credit, "{}").kind, Kind.Credit)
    assertEquals(body(Callback.Buyin, "{}").kind, Kind.Buyin)
    assertEquals(body(Callback.Debit, """{"provider_kind":"free_debit"}""").kind, Kind.FreeDebit)
  }

  test("a money answer is status, balance and currency; a bare refusal is its word alone") {
    assertEquals(Json.render(St8Protocol.money("ok", Some("95.00"), Some("EUR"))), """{"status":"ok","balance":"95.00","currency":"EUR"}""")
    assertEquals(Json.render(St8Protocol.status("session_expired")), """{"status":"session_expired"}""")
    assertEquals(Json.render(St8Protocol.money("error", None, None)), """{"status":"error"}""")
  }

  test("the status words are the contract's nine") {
    assertEquals(Status.all.size, 9)
    assert(!Status.all.contains("provider_unavailable"), "the simulator's word, never answered here")
  }

  test("only debit, credit, cancel, buyin and payout move money") {
    assertEquals(Callback.values.filter(Callback.movesMoney).map(_.wire).toSet, Set("debit", "credit", "cancel", "buyin", "payout"))
  }
