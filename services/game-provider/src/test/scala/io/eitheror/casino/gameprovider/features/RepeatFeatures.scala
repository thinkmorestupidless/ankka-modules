package io.eitheror.casino.gameprovider.features

import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.it.Reply
import io.eitheror.casino.gameprovider.rounds.Cleared

/** features/game-provider/repeats.feature (US4, FR-011, FR-020). */
class RepeatFeatures extends ProviderFeatures("repeats.feature"):

  sessionTicketSteps()

  private var first: Reply = null
  private var moneyCalls   = 0

  Given("a debit {string} answered {string} with balance {string}") { (tx: String, word: String, amount: String) =>
    kit.wallet.set(player, currency, minor(amount) + 500)
    first = debit(tx, "5.00", "r1")
    assertEquals((first.word, first.json.text("balance")), (word, Some(amount)))
    moneyCalls = kit.wallet.moneyCalls.size
  }

  private var mayBetAsked = 0

  Given("a debit {string} answered {string}") { (tx: String, word: String) =>
    kit.wallet.set(player, currency, 10_000)
    if word == "player_locked" then kit.neighbours.betRefused(player) = "cooling_off"
    first = debit(tx, "5.00", "r4")
    assertEquals(first.word, word)
  }

  When("{string} arrives again") { (tx: String) =>
    moneyCalls = kit.wallet.moneyCalls.size
    mayBetAsked = kit.neighbours.mayBetAsked.size
    tx match
      case t if t.startsWith("t1") => debit(tx, "5.00", "r1")
      case t if t.startsWith("t4") => debit(tx, "5.00", "r4")
      case t if t.startsWith("c")  => cancel(tx, "t9")
      case _                       => credit(tx, "12.00", Some("r1"))
  }

  Then("it is answered {string} with balance {string}") { (word: String, amount: String) => answeredWithBalance(word, amount) }

  Then("the wallet is not called") { () => assertEquals(kit.wallet.moneyCalls.size, moneyCalls) }

  Then("it is answered {string}") { (word: String) => answered(word) }

  Then("responsible gambling is not asked") { () => assertEquals(kit.neighbours.mayBetAsked.size, mayBetAsked) }

  Given("a credit {string} answered {string}") { (tx: String, word: String) =>
    assertEquals(debit(s"$tx-stake", "1.00", "r1").word, "ok")
    first = credit(tx, "12.00", Some("r1"))
    assertEquals(first.word, word)
  }

  When("{string} arrives again with a different amount") { (tx: String) =>
    moneyCalls = kit.wallet.moneyCalls.size
    credit(tx, "999.00", Some("r1"))
  }

  Then("the first outcome is answered") { () =>
    assertEquals(last.text, first.text)
    assertEquals(kit.wallet.moneyCalls.size, moneyCalls, "a repeat never reaches the wallet")
  }

  Then("the difference is recorded as a conflicting repeat") { () =>
    val conflicts = kit.round(id("r1")).conflictingRepeats
    assertEquals(conflicts.map(_.transactionId), Vector(id("t2")))
    assert(conflicts.head.difference.contains("amount 1200 then 99900"), conflicts.head.difference)
  }

  Given("a cancel {string} answered {string}") { (c: String, word: String) =>
    assertEquals(debit("t9", "2.00", "r1").word, "ok")
    first = cancel(c, "t9")
    assertEquals(first.word, word)
  }

  Then("it is answered {string} with the same balance") { (word: String) =>
    answered(word)
    assertEquals(last.json.text("balance"), first.json.text("balance"))
    assertEquals(kit.wallet.moneyCalls.size, moneyCalls)
  }

  Given("a debit {string} that failed to reach the wallet and was answered {string}") { (tx: String, word: String) =>
    kit.wallet.down = true
    assertEquals(debit(tx, "5.00", "r3").word, word)
    kit.wallet.down = false
    assert(kit.round(id("r3")).openUnacknowledged(id(tx)).isDefined, "recorded as unacknowledged")
  }

  When("the game provider retries {string}") { (tx: String) => debit(tx, "5.00", "r3") }

  Then("the retry is served afresh") { () =>
    answered("ok")
    assertEquals(kit.wallet.openBets(player, currency).map(_.key), Vector(id("t3")))
  }

  Then("on success the unacknowledged call is cleared") { () =>
    val entry = kit.round(id("r3")).unacknowledged.find(_.transactionId == id("t3")).get
    assertEquals(entry.cleared.map(_.how), Some(Cleared.ProviderRetry))
  }
