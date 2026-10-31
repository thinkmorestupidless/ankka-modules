package io.eitheror.casino.wallet.features

import com.thinkmorestupidless.ankka.http.Caller
import com.thinkmorestupidless.ankka.testkit.GherkinSuite
import io.eitheror.casino.wallet.entity.{OpenMigrated, WalletEntity}
import io.eitheror.casino.wallet.it.{Harness, TestTokens}

import java.time.Instant

/**
 * Spec 022's features about two wallets and non-player accounts, bound to the whole service over
 * HTTP against Postgres (constitution VII): the exchange's calls as `Caller.Service(project,
 * "exchange")`, the clearing's as `"clearing"`, the operator's with a staff token.
 *
 * The kit is shared by a suite's scenarios, so each scenario's players, markets and pools carry
 * the scenario's tag and never meet another's.
 */
abstract class LedgerFeatures(file: String) extends GherkinSuite(s"../../features/wallet/$file") with Harness:

  protected var tag                  = ""
  protected var currency             = "EUR"
  protected var keys                 = 0
  protected var last: (Int, String)  = (0, "")
  protected var first: (Int, String) = (0, "")
  /** Whether a When has run: a step written as both a Given and a Then seeds before it and checks after. */
  protected var acted                = false

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    tag = s"s${context.test.name.hashCode.abs}"
    currency = "EUR"; keys = 0; last = (0, ""); first = (0, ""); acted = false

  protected def nextKey(prefix: String): String = { keys += 1; s"$prefix-$tag-$keys" }
  protected def p(name: String): String         = s"$name-$tag"

  protected def exchange: Caller = Caller.Service(Harness.Project, "exchange")
  protected def service(name: String): Caller = Caller.Service(Harness.Project, name)

  /** A wallet as the migration opens one: any real balance, zero included, with no deposit. */
  protected def open(player: String, real: Long): Unit =
    val o = testKit.componentClient.forEventSourcedEntity(WalletEntity.id(player, currency)).call(WalletEntity.openMigrated)
      .invoke(OpenMigrated("open", real, 0, Vector.empty, 0, 0, 0, Instant.now()))
    assert(o.accepted, o.toString)

  protected def balanceOf(player: String): String = send("GET", s"${wallet(player, currency)}/balance")._2
  protected def amountIn(body: String, name: String): Long = field(body, name).getOrElse("0").toLong

  protected def reserve(player: String, order: String, amount: Long): (Int, String) =
    send("POST", s"${wallet(player, currency)}/reservations", Some(s"""{"key":"$order","amount":$amount}"""), as = exchange)

  protected def refusedAs(phrase: String): Unit =
    val code = Map("insufficient funds" -> "INSUFFICIENT_FUNDS", "credit line reached" -> "CREDIT_LINE_REACHED",
      "not owner" -> "NOT_OWNER", "reservation short" -> "RESERVATION_SHORT", "reservation not found" -> "RESERVATION_NOT_FOUND",
      "wallet not found" -> "WALLET_NOT_FOUND")
      .getOrElse(phrase, fail(s"no refusal is named '$phrase'"))
    assertEquals(field(last._2, "error"), Some(code), last.toString)

  // ── the shared vocabulary ─────────────────────────────────────────────────

  Given("a player {string} with a wallet in {string} and a real balance of {string}") { (name: String, c: String, r: String) =>
    currency = c
    open(p(name), r.toLong)
  }

  Given("a reservation of {string} is placed for order {string} on the wallet of {string}") { (a: String, o: String, name: String) =>
    val (status, body) = reserve(p(name), p(o), a.toLong)
    assertEquals(status, 200, body)
  }

  Then("the real balance of {string} is {string}") { (name: String, r: String) =>
    assertEquals(amountIn(balanceOf(p(name)), "realBalance"), r.toLong)
  }

  Then("the reserved amount of {string} is {string}") { (name: String, r: String) =>
    assertEquals(amountIn(balanceOf(p(name)), "reserved"), r.toLong)
  }

  Then("the available amount of {string} is {string}") { (name: String, r: String) =>
    assertEquals(amountIn(balanceOf(p(name)), "available"), r.toLong)
  }

  Then("it is refused as {string}") { (phrase: String) => refusedAs(phrase) }

  Then("the outcome is the first outcome") { () => assertEquals(last, first) }

  /** The facts on `money.transactions` about a wallet or an account, once the publisher has caught up. */
  protected def moneyFacts(key: String, family: String): Seq[String] =
    eventually(facts("money.transactions", key))(_.exists(_.contains(s""""type":"$family""""))).filter(_.contains(s""""type":"$family""""))

  protected def operatorToken: String = TestTokens.operator("op-7")
