package io.eitheror.casino.gameprovider.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.catalogue.{Game, GameFields, UpsertGame}

/** The money path end to end, on the whole service: the plumbing every other suite stands on. */
class SmokeSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()

  test("an operator configures ST8; a debit and a credit move money and publish round_settled") {
    assertEquals(kit.configureSt8().status, 204)
    kit.wallet.set("p-1", "EUR", 10000)
    val token = kit.sessionTicket("p-1")
    val debit = kit.callback("debit", kit.money("token" -> token, "transaction_id" -> "t-1", "round" -> "r-1", "amount" -> "1.00", "currency" -> "EUR"))
    assertEquals((debit.status, debit.word), (200, "ok"), debit.text)
    assertEquals(debit.json.text("balance"), Some("99.00"))
    val credit = kit.callback("credit", kit.money("token" -> token, "transaction_id" -> "c-1", "round" -> "r-1", "amount" -> "5.00"))
    assertEquals(credit.word, "ok", credit.text)
    assertEquals(credit.json.text("balance"), Some("104.00"))
    assertEquals(kit.wallet.openBets("p-1", "EUR"), Vector.empty)
    val fact = kit.eventually("round_settled for r-1")(kit.roundsPublished.find(_.providerRoundId == "r-1"))
    assertEquals((fact.outcome, fact.stakeCents.value, fact.winCents.value), ("win", 100L, 500L))
    assertEquals(kit.roundsMessages.head.message.key, Some("p-1/EUR"))
  }

  test("round_settled names the game from the catalogue and carries the ticket's game session; a game not catalogued has none (spec 015)") {
    assertEquals(kit.configureSt8().status, 204)
    kit.testKit.componentClient.forKeyValueEntity(EntityId(Game.entityId("st8", "starburst"))).call(Game.upsert)
      .invoke(UpsertGame("st8", "starburst", GameFields("Starburst", "netent", "NetEnt", "slots"), kit.clock)): Unit
    kit.wallet.set("p-3", "EUR", 10000)
    def play(game: String, round: String) =
      val token = kit.sessionTicket("p-3", game = game)
      assertEquals(kit.callback("debit", kit.money("token" -> token, "transaction_id" -> s"$round-d", "round" -> round, "amount" -> "1.00",
        "currency" -> "EUR")).word, "ok")
      assertEquals(kit.callback("credit", kit.money("token" -> token, "transaction_id" -> s"$round-c", "round" -> round, "amount" -> "0.00")).word, "ok")
      (token, kit.eventually(s"round_settled for $round")(kit.roundsPublished.find(_.providerRoundId == round)))
    val (token, named) = play("starburst", "r-named")
    assertEquals((named.gameId, named.gameName, named.gameType), ("starburst", Some("Starburst"), Some("slots")))
    assertEquals(named.sessionId, kit.ticket(token).sessionId, "the game session the launch attached to the ticket")
    val (_, unnamed) = play("not-catalogued", "r-unnamed")
    assertEquals((unnamed.gameId, unnamed.gameName, unnamed.gameType), ("not-catalogued", None, None))
  }

  test("the callback is served at /st8 and at the root, and refused for another brand") {
    val token = kit.sessionTicket("p-2")
    kit.wallet.set("p-2", "EUR", 500)
    val body = kit.money("token" -> token, "player" -> "p-2", "currency" -> "EUR")
    assertEquals(kit.callback("balance", body, path = Some("/st8/balance")).word, "ok")
    assertEquals(kit.callback("balance", body, path = Some("/balance")).json.text("balance"), Some("5.00"))
    assertEquals(kit.callback("balance", body, path = Some("/v1/casino/brands/spinvibe/st8/balance")).status, 404)
  }

  test("a signature under another key is auth_failed") {
    val r = kit.callback("balance", kit.money("player" -> "p-1"), key = io.eitheror.casino.gameprovider.TestKeys.otherPrivate)
    assertEquals((r.status, r.word), (200, "auth_failed"))
  }
