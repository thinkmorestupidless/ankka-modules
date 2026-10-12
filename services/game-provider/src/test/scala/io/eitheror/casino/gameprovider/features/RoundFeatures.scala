package io.eitheror.casino.gameprovider.features

import io.eitheror.casino.contract.facts.PlayerSelfExcluded
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.players.PlayerStatus
import io.eitheror.casino.gameprovider.rounds.RoundState

/** features/game-provider/rounds.feature (US2, FR-012 to FR-014, FR-019). */
class RoundFeatures extends ProviderFeatures("rounds.feature"):

  sessionTicketSteps()

  private var round = ""

  private def walletRound(route: String): Vector[String] = kit.wallet.calls(route).flatMap(_.json).flatMap(_.text("roundId"))
  private def walletKeys(route: String): Vector[String]  = kit.wallet.calls(route).flatMap(_.json).flatMap(_.text("key"))
  private def roundState(r: String = round): RoundState  = kit.round(id(r))

  Given("the wallet has a real balance of {string}") { (amount: String) => kit.wallet.set(player, currency, minor(amount)) }

  When("the game provider debits {string} under transaction id {string} in round {string}") { (amount: String, tx: String, r: String) =>
    round = r; before = balance; debit(tx, amount, r)
  }

  Then("it is answered {string} with balance {string}") { (word: String, amount: String) => answeredWithBalance(word, amount) }
  Then("it is answered {string}") { (word: String) => answered(word) }

  Then("the wallet holds an open bet keyed {string}") { (tx: String) =>
    assertEquals(kit.wallet.openBets(player, currency).map(_.key), Vector(id(tx)))
  }

  Given("an open bet {string} of {string} in round {string} and a real balance of {string}") { (tx: String, amount: String, r: String, after: String) =>
    kit.wallet.set(player, currency, minor(after) + minor(amount))
    round = r
    assertEquals(debit(tx, amount, r).word, "ok")
  }

  Given("an open bet {string} of {string} in round {string}") { (tx: String, amount: String, r: String) =>
    round = r
    assertEquals(debit(tx, amount, r).word, "ok")
  }

  When("the game provider credits {string} under transaction id {string} in round {string}") { (amount: String, tx: String, r: String) =>
    round = r; before = balance; credit(tx, amount, Some(r))
  }

  // Sent the moment the debit before it was answered: the debit's bet is on the round by then (research R18).
  When("the game provider credits {string} under transaction id {string} in round {string} once it is answered") { (amount: String, tx: String, r: String) =>
    round = r; credit(tx, amount, Some(r))
  }

  When("the game provider credits {string} in round {string}") { (amount: String, r: String) =>
    round = r; before = balance; credit("credit", amount, Some(r))
  }

  Then("bet {string} is settled with the win") { (tx: String) =>
    assertEquals(walletRound("wins"), Vector(id(tx)))
    assert(!kit.wallet.openBets(player, currency).exists(_.key == id(tx)))
  }

  Then("round {string} is closed") { (r: String) => assertEquals(roundState(r).status, RoundState.Settled) }

  Then("a {string} fact is published") { (family: String) =>
    assertEquals(family, "round_settled")
    val f = settled(round)
    assertEquals(f.key, s"$player/$currency")
  }

  Then("it is answered {string} with the balance unchanged") { (word: String) => answeredWithBalance(word, render(before)) }

  Then("bet {string} is settled as a loss") { (tx: String) =>
    assert(walletRound("losses").contains(id(tx)), kit.wallet.calls("losses").toString)
    assert(!kit.wallet.openBets(player, currency).exists(_.key == id(tx)))
  }

  Then("a {string} fact with outcome {string} is published") { (family: String, outcome: String) =>
    assertEquals(family, "round_settled")
    assertEquals(settled(round).outcome, outcome)
  }

  Then("no bet is opened") { () =>
    assertEquals(kit.wallet.openBets(player, currency), Vector.empty)
    assertEquals(roundState().openBets, Vector.empty)
  }

  Given("no debit in round {string}") { (r: String) => round = r }

  Then("the amount is a provider credit on the wallet keyed {string}") { (tx: String) =>
    assertEquals(walletKeys("provider-credits"), Vector(id(tx)))
    assertEquals(walletKeys("wins"), Vector.empty)
  }

  Then("a {string} fact with no stake is published") { (family: String) =>
    assertEquals(family, "round_settled")
    val f = settled(round)
    assertEquals((f.stakeCents.value, f.outcome), (0L, "credit"))
  }

  Given("open bets {string} and {string} in round {string}") { (a: String, b: String, r: String) =>
    round = r
    assertEquals(debit(a, "1.00", r).word, "ok")
    assertEquals(debit(b, "2.00", r).word, "ok")
  }

  Then("the win settles bet {string}") { (tx: String) => assertEquals(walletRound("wins"), Vector(id(tx))) }

  Then("round {string} is closed with both bets") { (r: String) =>
    val s = roundState(r)
    assertEquals(s.status, RoundState.Settled)
    assertEquals(s.openBets, Vector.empty)
    assertEquals(kit.wallet.openBets(player, currency), Vector.empty, "no bet of the round is left open in the wallet")
  }

  Given("round {string} already settled") { (r: String) =>
    round = r
    assertEquals(debit("first", "1.00", r).word, "ok")
    assertEquals(credit("first-credit", "2.00", Some(r)).word, "ok")
  }

  When("the game provider credits {string} under a new transaction id {string} in round {string}") { (amount: String, tx: String, r: String) =>
    credit(tx, amount, Some(r))
  }

  Then("the amount is a provider credit keyed {string}") { (tx: String) => assert(walletKeys("provider-credits").contains(id(tx))) }

  When("the game provider sends a debit of {string} in round {string}") { (amount: String, r: String) =>
    round = r; before = balance; kit.wallet.clearReceived(); debit("zero", amount, r)
  }

  When("the game provider sends a debit of kind {string} in round {string}") { (kind: String, r: String) =>
    round = r; before = balance; kit.wallet.clearReceived(); debit("free", "0.20", r, kind = Some(kind))
  }

  Then("no bet is placed on the wallet") { () => assertEquals(kit.wallet.calls("bets"), Vector.empty) }

  Then("the current balance is answered") { () => answeredWithBalance("ok", render(balance)) }

  Then("round {string} is recorded with a stake of zero") { (r: String) =>
    val s = roundState(r)
    assertEquals(s.totalStake, 0L)
    assert(s.transactions.nonEmpty)
  }

  Given("the session ticket carries free rounds") { () => token = kit.sessionTicket(player, grant = Some(id("grant"))) }

  Then("round {string} is marked with the free rounds before the answer") { (r: String) =>
    answered("ok")
    assertEquals(roundState(r).freeRoundsGrant, Some(id("grant")))
  }

  Given("round {string} is marked with free rounds") { (r: String) =>
    round = r
    token = kit.sessionTicket(player, grant = Some(id("grant")))
    assertEquals(debit("free", "0", r, kind = Some("free_debit")).word, "ok")
    kit.wallet.clearReceived()
  }

  Then("the wallet is not credited") { () =>
    assertEquals(kit.wallet.calls("wins") ++ kit.wallet.calls("provider-credits"), Vector.empty)
  }

  Then("a {string} fact carrying the free rounds and the win is published, for the winnings to be settled under the reward rules") { (family: String) =>
    assertEquals(family, "round_settled")
    val f = settled(round)
    assertEquals((f.freeRoundsGrant, f.winCents.value, f.outcome), (Some(id("grant")), 300L, "win"))
    assertEquals(f.walletTransactionIds, Vector.empty)
  }

  When("the game provider debits {string} of kind {string} under transaction id {string}") { (amount: String, kind: String, tx: String) =>
    round = tx; debit(tx, amount, tx, kind = Some(kind))
  }

  Then("it is placed as a bet allowed to take the balance below zero") { () =>
    assertEquals(kit.wallet.calls("bets").last.json.flatMap(_.bool("allowNegative")), Some(true))
  }

  Given("the session ticket was issued for game {string}") { (g: String) => token = kit.sessionTicket(player, game = id(g)) }

  When("the game provider debits {string} naming game {string}") { (amount: String, g: String) =>
    round = "r-other"; before = balance; debit("t-other", amount, "r-other", game = Some(id(g)))
  }

  Then("it is served as any debit is") { () =>
    answeredWithBalance("ok", render(before - 100))
    assertEquals(kit.wallet.openBets(player, currency).map(_.key), Vector(id("t-other")))
  }

  Given("a provider call carrying fields the brand does not read") { () => () }

  When("it arrives") { () =>
    last = kit.callback("debit", json("token" -> token, "transaction_id" -> id("t-extra"), "round" -> id("r-extra"), "amount" -> "1.00",
      "currency" -> currency, "bonus" -> json("x" -> 1L), "new_field" -> "anything", "table_id" -> 77L))
  }

  Then("the fields the brand does not read are ignored") { () =>
    answered("ok")
    assertEquals(kit.wallet.openBets(player, currency).map(_.key), Vector(id("t-extra")))
  }

  // Cooling off is `responsible-gambling`'s answer to the bet workflow's `may-bet` (research R18).
  Given("the player is cooling off") { () => kit.neighbours.betRefused(player) = "cooling_off" }

  Given("responsible gambling does not answer") { () => kit.neighbours.betSilent += player }

  Given("the player is self-excluded") { () =>
    kit.publishPlayers(PlayerSelfExcluded(player, "active", "self_excluded", "player", None, None, kit.clock, None, kit.clock))
    kit.eventually("self-exclusion in the view")(kit.testKit.service.viewClient.forView(PlayerStatus).get(player).filter(_.locked))
  }

  Then("nothing is placed") { () =>
    assertEquals(kit.wallet.openBets(player, currency), Vector.empty)
    assertEquals(roundState().openBets, Vector.empty)
  }

  Given("the player's wallet refuses a bet for a responsible gambling limit") { () => kit.wallet.refuse(id("t8"), "SCOPE_BLOCKED") }

  Then("it is answered {string} with the current balance") { (word: String) => answeredWithBalance(word, render(balance)) }
