package io.eitheror.casino.provider.edge

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.*

/**
 * What the operator API refuses (review): a launch in a currency no one can render, a ticket reused
 * with other terms, and a bonus id another brand holds.
 */
class LaunchSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private def kit = ProviderTestKit.kit

  private def error(a: ProviderTestKit.Answer): Option[String] = Option(a.json.get("error")).map(_.asText())

  private def launched(ticket: String, player: String, currency: String = "BRL"): ProviderTestKit.Answer =
    kit.launch(ticket, player, "test-slot", currency = currency)

  override def beforeAll(): Unit =
    kit.start()
    assertEquals(kit.game.registerGame("test-slot", "slots").status, 200)

  test("a launch in a currency contract.money does not know is refused, and no session is made") {
    val ticket = s"L-cur-${System.nanoTime()}"
    assertEquals(error(launched(ticket, "p-cur", currency = "XXQ")), Some("invalid_currency"))
    assert(scala.util.Try(kit.session(ticket)).fold(_ => true, !_.launched))
  }

  test("a repeated launch answers the same page; the ticket with another player is ticket_used") {
    val ticket = s"L-again-${System.nanoTime()}"
    val first  = launched(ticket, "p-again")
    assert(first.json.has("game_url"), first.body)
    assertEquals(launched(ticket, "p-again").json.get("game_url"), first.json.get("game_url"))
    assertEquals(error(launched(ticket, "p-other")), Some("ticket_used"))
    assertEquals(kit.session(ticket).playerId, "p-again")
  }

  test("a bonus id another brand already holds is answered the stand-in's error, not unknown_game (review nit)") {
    val id = s"B-held-${System.nanoTime()}"
    def body(site: String) =
      s"""{"bonus_id":"$id","players":["p-bonus"],"count":2,"value":"0.10","type":"free_bets","currency":"BRL","game_codes":["test-slot"],"site":"$site"}"""
    assertEquals(kit.signedPost("/api/operator/v1/bonus/create", body("goldvibe")).json.get("status").asText(), "ok")
    kit.putEntry(kit.entry("spinvibe", kit.spinvibe.address))
    try
      val other = kit.signedPost("/api/operator/v1/bonus/create", body("spinvibe"))
      assertEquals(other.json.get("status").asText(), "error", other.body)
      assertEquals(error(other), None)
    finally kit.removeEntry("spinvibe")
  }
