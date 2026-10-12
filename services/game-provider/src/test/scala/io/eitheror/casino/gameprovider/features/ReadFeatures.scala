package io.eitheror.casino.gameprovider.features

import io.eitheror.casino.contract.facts.PlayerRegistered
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.players.PlayerStatus

/** features/game-provider/reads.feature (US6, FR-016 to FR-018). */
class ReadFeatures extends ProviderFeatures("reads.feature"):

  sessionTicketSteps()

  Given("a player with a session ticket for a wallet in {string} and a real balance of {string}") { (cur: String, amount: String) =>
    currency = cur
    token = kit.sessionTicket(player, currency = cur)
    kit.wallet.set(player, cur, minor(amount))
  }

  When("the game provider asks the balance with the session ticket") { () =>
    kit.wallet.set(player, currency, 9000, 525)
    last = kit.callback("balance", json("token" -> token, "player" -> player, "currency" -> currency))
  }

  Then("it is answered {string} with real plus restricted balance to two decimals and the currency") { (word: String) =>
    answeredWithBalance(word, "95.25")
    assertEquals(last.json.text("currency"), Some(currency))
  }

  Given("a player who has launched a game before") { () =>
    kit.sessionTicket(player): Unit
    kit.wallet.set(player, currency, 4200)
  }

  When("the game provider asks the balance with the player id and no session ticket") { () =>
    last = kit.callback("balance", json("player" -> player, "currency" -> currency))
  }

  Then("the player is resolved by id") { () => answered("ok") }

  Then("the balance is answered") { () => answeredWithBalance("ok", render(balance)) }

  Given("a registered player who has never launched a game") { () =>
    kit.publishPlayers(PlayerRegistered(player, kit.clock, None, None, None, Some("mt"), Some("EUR"), None, kit.clock))
    kit.eventually("the registration in the view")(kit.testKit.service.viewClient.forView(PlayerStatus).get(player))
    kit.neighbours.profiles.update(player, io.eitheror.casino.gameprovider.it.FakeNeighbours.Profile("EUR", "mt", "1991-02-03", "2026-03-04T15:16:17Z", Some(15000L)))
  }

  When("the game provider asks their player profile") { () => last = kit.callback("player_profile", json("player" -> player)) }

  Then("it is answered {string} with their id, the brand's jurisdiction, their default currency, their registration country and date, their date of birth, and a bet limits band from their daily deposit limit") {
    (word: String) =>
      answered(word)
      val a = last.json
      assertEquals(a.text("id"), Some(player))
      assertEquals(a.text("jurisdiction"), Some("MT"), "the brand's, from its configuration, not the original's constant CW")
      assertEquals(a.text("default_currency"), Some("EUR"))
      assertEquals(a.text("reg_country"), Some("MT"))
      assertEquals(a.text("reg_date"), Some("2026-03-04"))
      assertEquals(a.text("birth_date"), Some("1991-02-03"))
      assertEquals(a.text("bet_limits"), Some("medium"))
  }

  Given("an unknown player id") { () => kit.neighbours.unknown += player }

  When("the game provider asks the player profile for it") { () => last = kit.callback("player_profile", json("player" -> player)) }

  Then("it is answered {string}") { (word: String) => answered(word) }

  When("the game provider debits a buyin of {string} under transaction id {string}") { (amount: String, tx: String) =>
    last = kit.callback("buyin", json("player" -> player, "token" -> token, "transaction_id" -> id(tx), "amount" -> amount, "currency" -> currency))
  }

  Then("a bet keyed {string} is placed") { (tx: String) =>
    assertEquals(kit.wallet.openBets(player, currency).map(_.key), Vector(id(tx)))
  }

  When("the game provider credits {string} outside any round under transaction id {string}") { (amount: String, tx: String) =>
    last = kit.callback("payout", json("player" -> player, "token" -> token, "transaction_id" -> id(tx), "amount" -> amount, "currency" -> currency))
  }

  Then("a provider credit keyed {string} is made") { (tx: String) =>
    assertEquals(kit.wallet.calls("provider-credits").flatMap(_.json).flatMap(_.text("key")), Vector(id(tx)))
  }
