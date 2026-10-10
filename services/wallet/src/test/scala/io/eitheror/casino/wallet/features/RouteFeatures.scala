package io.eitheror.casino.wallet.features

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.http.Caller
import com.thinkmorestupidless.ankka.testkit.GherkinSuite
import io.eitheror.casino.wallet.entity.WalletEntity
import io.eitheror.casino.wallet.it.{Harness, TestTokens}

import scala.jdk.CollectionConverters.*

/** Features whose scenarios are about the routes, the views or the topics: the whole service, over HTTP. */
abstract class RouteFeatures(file: String) extends GherkinSuite(s"../../features/wallet/$file") with Harness:

  protected val json                 = ObjectMapper()
  protected var player               = ""
  protected var currency             = "EUR"
  protected var keys                 = 0
  protected var last: (Int, String)  = (0, "")
  protected var first: (Int, String) = (0, "")
  protected var lastSeen             = 0L

  protected def nextKey(prefix: String): String = { keys += 1; s"$prefix-$keys" }

  protected def path: String = wallet(player, currency)

  protected def node(body: String): JsonNode = json.readTree(body)

  protected def deposit(amount: Long): (Int, String) =
    send("POST", s"$path/deposits",
      Some(s"""{"paymentId":"${nextKey("pay")}","amount":$amount,"terminal":"t1","psp":"dreamzpay","scheme":"card","paymentProvider":"dreamzpay"}"""),
      as = Caller.Service("payments", "merchant"))

  protected def bet(amount: String, round: String, key: Option[String]): (Int, String) =
    val keyed = key.fold("")(k => s""""key":"$k",""")
    send("POST", s"$path/bets", Some(s"""{$keyed"roundId":"$round","gameId":"slot-a","sessionId":"s1","amount":$amount}"""))

  protected def realBalance: Long = field(send("GET", s"$path/balance")._2, "realBalance").getOrElse(fail("no balance")).toLong

  protected def walletState = testKit.componentClient.forEventSourcedEntity(WalletEntity.id(player, currency)).call(WalletEntity.state).invoke()

  Given("a player with a wallet in {string}") { (c: String) =>
    currency = c
    player = s"p${scenarioId.hashCode.abs}"
    keys = 0
  }

/** features/wallet/money.feature: money at the boundary and under its key (User Story 1; spec 001 FR-026, FR-027). */
class MoneyFeatures extends RouteFeatures("money.feature"):

  Given("the wallet has a real balance of {string}") { (r: String) => assertEquals(deposit(r.toLong)._1, 200) }

  When("the player places a bet of {string} in round {string}") { (a: String, round: String) =>
    last = bet(a, round, Some(nextKey("tx")))
  }

  Then("the real balance is {string}") { (r: String) => assertEquals(realBalance, r.toLong) }

  Then("the bet's amount is recorded as the whole number {string}") { (a: String) =>
    assertEquals(walletState.openBets.map(_.amount), Vector(a.toLong))
    // And on the topic, as an integer, never a decimal.
    eventually(facts("money.transactions", s"$player/$currency"))(_.exists(f => f.contains("\"bet_placed\"") && f.contains(s""""amount_cents":$a""")))
  }

  Given("a money command was sent under key {string}") { (k: String) =>
    deposit(10000L)
    first = bet("1050", "r1", Some(k))
    assertEquals(first._1, 200, first._2)
    lastSeen = realBalance
  }

  When("the same money command is sent again under key {string}") { (k: String) => last = bet("1050", "r1", Some(k)) }

  Then("the first answer is returned") { () => assertEquals(last, first) }

  Then("the real balance is unchanged") { () => assertEquals(realBalance, lastSeen) }

  When("a money command carries the amount {string}") { (a: String) => last = bet(a, "r1", Some(nextKey("tx"))) }

  Then("it is refused, naming the amount") { () =>
    assertEquals((last._1, field(last._2, "error")), (400, Some("INVALID_REQUEST")), last._2)
    assert(last._2.contains("'10.50'"), last._2)
  }

  When("a money command is sent with no key") { () => last = bet("1050", "r1", None) }

  Then("it is refused") { () =>
    assertEquals(last._1, 400, last._2)
    assertEquals(field(last._2, "error"), Some("MISSING_KEY"))
  }

  Given("a bet of {string} was placed under key {string}") { (a: String, k: String) =>
    deposit(10000L)
    first = bet(a, "r1", Some(k))
    assertEquals(first._1, 200, first._2)
    lastSeen = realBalance
  }

  When("a bet of {string} is sent under key {string}") { (a: String, k: String) => last = bet(a, "r2", Some(k)) }

  Then("it is refused as {string}, naming {string} and the first bet of {string}") { (phrase: String, k: String, a: String) =>
    assertEquals(phrase, "key reused")
    assertEquals((last._1, field(last._2, "error")), (409, Some("KEY_REUSED")), last._2)
    val message = field(last._2, "message").getOrElse("")
    assert(message.contains(k) && message.contains(s"of $a"), message)
  }

  Then("no transaction is written") { () =>
    assertEquals(realBalance, lastSeen)
    assertEquals(walletState.openBets.size, 1)
  }

/** features/wallet/reads.feature: what the player, the operator and the brand's other parts read (User Story 6). */
class ReadsFeatures extends RouteFeatures("reads.feature"):

  private var read: JsonNode = null

  private def award(grant: String, amount: Long, priority: Long, policy: String): Unit =
    val (status, body) = send("POST", s"$path/awards", Some(
      s"""{"key":"$grant","bonusRef":"bonus-$grant","amount":$amount,"wageringMultiplier":10,"maxWin":5000,"priority":$priority,"forfeitOnWithdrawal":"$policy"}"""),
      as = Caller.Service("local", "rewards"))
    assertEquals(status, 200, body)

  Given("an active restricted funds record") { () => award("g1", 2000L, 0L, "funds_and_winnings") }

  When("the balance is read") { () =>
    val (status, body) = sendWithToken("GET", s"/v1/casino/players/$brand/wallets/$currency/balance", TestTokens.player(player))
    assertEquals(status, 200, body)
    read = node(body)
  }

  Then("real balance, restricted balance and currency are returned as major-unit strings") { () =>
    assertEquals((read.get("realBalance").asText, read.get("bonusBalance").asText, read.get("currency").asText), ("0.00", "20.00", currency))
    assert(read.get("realBalance").isTextual && read.get("bonusBalance").isTextual, read.toString)
  }

  Then("the primary record's status, wagering requirement, wagered amount and release progress are returned") { () =>
    val p = read.get("primary")
    assertEquals((p.get("status").asText, p.get("wageringRequirement").asText, p.get("wagered").asText, p.get("progressPercent").asLong),
      ("ACTIVE", "200.00", "0.00", 0L))
  }

  Given("open restricted funds records") { () =>
    award("g-second", 1000L, 1L, "funds")
    award("g-first", 2000L, 0L, "none")
  }

  When("the restricted funds status is read") { () =>
    val (status, body) = send("GET", s"$path/restricted-funds", as = Caller.Service("local", "rewards"))
    assertEquals(status, 200, body)
    read = node(body)
  }

  Then("every open record is listed in order with its balance, wagering requirement, release progress, cap fields, priority and withdrawal policy") { () =>
    val records = read.get("records").elements.asScala.toVector
    assertEquals(records.map(_.get("grantRef").asText), Vector("g-first", "g-second"))
    val fields = Vector("balance", "wageringRequirement", "releaseProgress", "maxConvertible", "remainingAllowance",
      "depositsSinceConversion", "priority", "forfeitOnWithdrawal")
    records.foreach(r => fields.foreach(f => assert(r.has(f), s"$f missing from $r")))
    assertEquals(records.map(r => (r.get("balance").asLong, r.get("wageringRequirement").asLong, r.get("forfeitOnWithdrawal").asText)),
      Vector((2000L, 20000L, "none"), (1000L, 10000L, "funds")))
  }

  Then("the primary record's view is the headline") { () =>
    assertEquals(read.get("headline"), read.get("records").get(0))
  }

  Given("transactions") { () =>
    assertEquals(deposit(5000L)._1, 200)
    assertEquals(bet("1000", "r1", Some("tx-1"))._1, 200)
    assertEquals(send("POST", s"$path/wins", Some("""{"key":"tx-2","roundId":"r1","amount":2500}"""))._1, 200)
    award("g1", 2000L, 0L, "funds_and_winnings")
    val (s, b) = send("POST", s"$path/adjustments",
      Some("""{"key":"adj-1","walletType":"real","amount":500,"reason":"goodwill after an outage","category":"goodwill","actor":"op-7"}"""),
      as = Caller.Service("local", "support-agent"))
    assertEquals(s, 200, b)
  }

  When("they are listed by type, wallet type, date range and page") { () =>
    val q = s"type=bet&walletType=real&from=0&to=${Long.MaxValue / 2}&page=0&pageSize=10"
    val listed = eventually(send("GET", s"$path/transactions?$q", as = Caller.Service("local", "reporting")))(r =>
      r._1 == 200 && Option(node(r._2).get("transactions")).exists(_.size >= 1))
    read = node(listed._2)
  }

  Then("each row carries type, wallet type, amount, balance before and after, currency, status, payment reference, round, award reference, reason, terminal, payment provider and created time") { () =>
    val rows = read.get("transactions").elements.asScala.toVector
    assertEquals(rows.map(r => (r.get("type").asText, r.get("walletType").asText, r.get("amount").asLong)), Vector(("bet", "real", 1000L)))
    val fields = Vector("type", "walletType", "amount", "balanceBefore", "balanceAfter", "currency", "status", "paymentReference",
      "roundKey", "awardRef", "reason", "terminal", "paymentProvider", "createdAt")
    rows.foreach(r => fields.foreach(f => assert(r.has(f), s"$f missing from $r")))
    assertEquals((read.get("page").asInt, read.get("pageSize").asInt, read.get("total").asLong), (0, 10, 1L))
  }

  When("a sum by type over a date range is asked") { () =>
    read = eventually(node(send("GET", s"$path/sums?type=deposit&from=2000-01-01&to=2100-01-01", as = Caller.Service("local", "reporting"))._2))(
      _.get("count").asLong >= 1)
  }

  Then("the total and the count are returned") { () =>
    assertEquals((read.get("total").asLong, read.get("count").asLong), (5000L, 1L))
  }

  Then("lifetime totals report deposited, wagered and won") { () =>
    val totals = eventually(node(send("GET", s"$path/lifetime-totals", as = Caller.Service("local", "reporting"))._2))(_.get("won").asLong == 2500L)
    assertEquals((totals.get("deposited").asLong, totals.get("wagered").asLong, totals.get("won").asLong), (5000L, 1000L, 2500L))
  }
