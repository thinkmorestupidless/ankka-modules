package io.eitheror.casino.gameprovider.features

import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.rounds.RoundState

/** features/game-provider/cancels.feature (US3, FR-015). */
class CancelFeatures extends ProviderFeatures("cancels.feature"):

  sessionTicketSteps()

  private var round = ""

  private def keys(route: String): Vector[String]   = kit.wallet.calls(route).flatMap(_.json).flatMap(_.text("key"))
  private def rounds(route: String): Vector[String] = kit.wallet.calls(route).flatMap(_.json).flatMap(_.text("roundId"))

  Given("an open bet {string} of {string} in round {string}") { (tx: String, amount: String, r: String) =>
    round = r
    before = balance
    assertEquals(debit(tx, amount, r).word, "ok")
  }

  When("the game provider cancels {string} under cancel id {string}") { (tx: String, c: String) => cancel(c, tx) }

  Then("the bet is rolled back") { () =>
    assertEquals(kit.wallet.calls("rollbacks").size, 1)
    assertEquals(kit.wallet.openBets(player, currency), Vector.empty)
  }

  Then("it is answered {string} with the balance restored") { (word: String) =>
    answeredWithBalance(word, render(before))
    assertEquals(balance, before)
  }

  Then("round {string} is closed as cancelled, with a {string} fact of outcome {string}") { (r: String, family: String, outcome: String) =>
    assertEquals(kit.round(id(r)).status, RoundState.Cancelled)
    assertEquals(family, "round_settled")
    assertEquals(settled(r).outcome, outcome)
  }

  Given("a credit {string} of {string} in round {string}") { (tx: String, amount: String, r: String) =>
    round = r
    assertEquals(debit(s"$tx-stake", "1.00", r).word, "ok")
    assertEquals(credit(tx, amount, Some(r)).word, "ok")
    kit.wallet.set(player, currency, 500) // less than the credit, so its cancel takes the balance below zero
    kit.wallet.clearReceived()
  }

  Then("{string} is debited as a bet keyed {string} allowed to take the balance below zero") { (amount: String, c: String) =>
    val bet = kit.wallet.calls("bets").flatMap(_.json).find(_.text("key").contains(id(c))).getOrElse(fail("no bet keyed by the cancel id"))
    assertEquals(bet.long("amount"), Some(minor(amount)))
    assertEquals(bet.bool("allowNegative"), Some(true))
    assertEquals(balance, 500 - minor(amount))
  }

  Then("it is answered {string}") { (word: String) => answered(word) }

  Given("round {string} whose debit {string} was already rolled back and whose debit {string} is open") { (r: String, first: String, second: String) =>
    round = r
    assertEquals(debit(first, "1.00", r).word, "ok")
    assertEquals(debit(second, "2.00", r).word, "ok")
    assertEquals(cancel(s"$first-cancel", first).word, "ok")
    kit.wallet.clearReceived()
  }

  Then("{string} is rolled back on its own key") { (tx: String) =>
    assertEquals(rounds("rollbacks"), Vector(id(tx)))
    assertEquals(kit.wallet.openBets(player, currency), Vector.empty)
    assertEquals(kit.round(id(round)).status, RoundState.Cancelled)
  }

  Given("round {string} whose bet the wallet no longer holds open") { (r: String) =>
    round = r
    assertEquals(debit("held", "3.00", r).word, "ok")
    // settled elsewhere: the wallet closed the bet without this service, so a rollback finds no round
    kit.wallet.closeBet(player, currency, id("held"))
    before = balance
    kit.wallet.clearReceived()
  }

  When("the game provider cancels its debit under cancel id {string}") { (c: String) => cancel(c, "held") }

  Then("the debit's amount is refunded as a provider credit keyed {string}") { (c: String) =>
    val credit = kit.wallet.calls("provider-credits").flatMap(_.json).find(_.text("key").contains(id(c))).getOrElse(fail("no provider credit"))
    assertEquals(credit.long("amount"), Some(300L))
    assertEquals(balance, before + 300)
  }

  Given("no transaction {string} recorded for the player") { (tx: String) => () }

  Then("it is answered {string} with the current balance") { (word: String) => answeredWithBalance(word, render(balance)) }

  When("the game provider cancels {string} naming only the player and the transaction, with no session ticket") { (tx: String) =>
    cancel("by-id", tx, withTicket = false)
  }

  Then("the player is resolved by id") { () => assertEquals(last.json.text("currency"), Some(currency)) }

  Then("the cancel is served") { () =>
    answered("ok")
    assertEquals(kit.wallet.calls("rollbacks").size, 1)
    assertEquals(kit.wallet.openBets(player, currency), Vector.empty)
  }

  Given("round {string} with a debit {string} and a credit {string}") { (r: String, d: String, c: String) =>
    round = r
    before = balance
    assertEquals(debit(d, "5.00", r).word, "ok")
    assertEquals(credit(c, "12.00", Some(r)).word, "ok")
  }

  When("the game provider cancels {string} and then {string}") { (first: String, second: String) =>
    assertEquals(cancel(s"$first-x", first).word, "ok")
    assertEquals(cancel(s"$second-x", second).word, "ok")
  }

  Then("the balance is what it was before round {string}") { (r: String) => assertEquals(balance, before) }
