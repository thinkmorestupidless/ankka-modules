package io.eitheror.casino.contract.steps

import com.thinkmorestupidless.ankka.testkit.GherkinSuite
import io.eitheror.casino.contract.money.*

/**
 * features/wallet/money.feature against the contract's money types, composed into the smallest
 * ledger that uses them: a balance, `MinorUnits` at the boundary and `KeyedOutcomes` for repeats.
 * The wallet (spec 002) binds the same file against itself; this suite holds the rules the wallet
 * is built from.
 */
class MoneyFeatures extends GherkinSuite("features/wallet/money.feature"):

  private final case class Ledger(balance: Long, outcomes: KeyedOutcomes[String], amounts: Vector[MinorUnits])

  private var ledger   = Ledger(0, KeyedOutcomes(), Vector.empty)
  private var answers  = Vector.empty[String]
  private var refusal  = Option.empty[String]
  private var lastSeen = 0L

  /** A money command at the boundary: key first, then a whole amount, then the movement once per key. */
  private def command(key: Option[String], amount: String): Either[String, String] =
    for
      k <- Key.from(key).left.map(_.message)
      a <- MinorUnits.parse(amount).left.map(_.message)
    yield
      val (outcomes, answer, first) = ledger.outcomes.answer(k)(s"debited ${a.value}")
      ledger = ledger.copy(outcomes = outcomes)
      if first then ledger = ledger.copy(balance = ledger.balance - a.value, amounts = ledger.amounts :+ a)
      answer

  Given("a player with a wallet in {string}") { (currency: String) =>
    assert(Currency.from(currency).isRight); ledger = Ledger(0, KeyedOutcomes(), Vector.empty); answers = Vector.empty; refusal = None
  }

  Given("the wallet has a real balance of {string}") { (balance: String) =>
    ledger = ledger.copy(balance = MinorUnits.parse(balance).toOption.get.value)
  }

  When("the player places a bet of {string} in round {string}") { (amount: String, round: String) =>
    answers :+= command(Some(s"bet-$round"), amount).fold(e => fail(e), identity)
  }

  Then("the real balance is {string}") { (balance: String) => assertEquals(ledger.balance, balance.toLong) }

  Then("the bet's amount is recorded as the whole number {string}") { (amount: String) =>
    assertEquals(ledger.amounts.last.value, amount.toLong)
  }

  Given("a money command was sent under key {string}") { (key: String) =>
    ledger = ledger.copy(balance = 10000)
    answers :+= command(Some(key), "1050").fold(e => fail(e), identity)
    lastSeen = ledger.balance
  }

  When("the same money command is sent again under key {string}") { (key: String) =>
    answers :+= command(Some(key), "1050").fold(e => fail(e), identity)
  }

  Then("the first answer is returned") { () => assertEquals(answers.distinct.size, 1, answers.toString) }

  Then("the real balance is unchanged") { () => assertEquals(ledger.balance, lastSeen) }

  When("a money command carries the amount {string}") { (amount: String) =>
    refusal = command(Some("tx-2"), amount).left.toOption
  }

  Then("it is refused, naming the amount") { () =>
    assert(refusal.exists(_.contains("'10.50'")), refusal.toString)
  }

  When("a money command is sent with no key") { () =>
    refusal = command(None, "1050").left.toOption
  }

  Then("it is refused") { () => assert(refusal.isDefined) }
