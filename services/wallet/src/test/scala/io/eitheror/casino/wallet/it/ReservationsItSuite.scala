package io.eitheror.casino.wallet.it

import com.thinkmorestupidless.ankka.http.Caller
import io.eitheror.casino.wallet.entity.{OpenMigrated, WalletEntity}

import java.time.Instant

/**
 * Spec 022 User Stories 1 and 4 over HTTP against Postgres: the exchange's reservations and their
 * facts, and every audience's read of the reserved and available amounts.
 */
class ReservationsItSuite extends Harness:

  private val exchange = Caller.Service(Harness.Project, "exchange")

  private def open(player: String, real: Long): Unit =
    testKit.componentClient.forEventSourcedEntity(WalletEntity.id(player, "EUR")).call(WalletEntity.openMigrated)
      .invoke(OpenMigrated("open", real, 0, Vector.empty, 0, 0, 0, Instant.now())): Unit

  private def reserve(player: String, order: String, amount: Long) =
    send("POST", s"${wallet(player)}/reservations", Some(s"""{"key":"$order","amount":$amount}"""), as = exchange)

  private def release(player: String, order: String, sequence: Int, amount: Long) =
    send("POST", s"${wallet(player)}/reservations/release", Some(s"""{"orderId":"$order","sequence":$sequence,"amount":$amount}"""), as = exchange)

  test("reserve 600 of 1,000, release 200, read 400 reserved and 600 available; a repeat answers the first; the next 600 is refused") {
    open("rv1", 1000)
    val (status, placed) = reserve("rv1", "o1", 600)
    assertEquals((status, field(placed, "reserved"), field(placed, "available"), field(placed, "realBalance")),
      (200, Some("600"), Some("400"), Some("1000")))
    assertEquals(field(release("rv1", "o1", 1, 200)._2, "reserved"), Some("400"))
    val balance = send("GET", s"${wallet("rv1")}/balance")._2
    assertEquals((field(balance, "reserved"), field(balance, "available")), (Some("400"), Some("600")))
    assertEquals(reserve("rv1", "o1", 600), (status, placed))
    val (refusedStatus, refused) = reserve("rv1", "o2", 601)
    assertEquals((refusedStatus, field(refused, "error")), (409, Some("INSUFFICIENT_FUNDS")))
    assertEquals(field(reserve("rv1", "o1", 700)._2, "error"), Some("KEY_REUSED"))

    val money = eventually(facts("money.transactions", "rv1/EUR"))(_.size >= 2)
    assert(money.exists(f => f.contains("\"reservation_placed\"") && f.contains("\"reserved_balance_after\":600")), money.mkString("\n"))
    assert(money.exists(f => f.contains("\"reservation_released\"") && f.contains("\"reserved_balance_after\":400")), money.mkString("\n"))
    assertEquals(money.count(_.contains("\"reservation_placed\"")), 1, "a repeat publishes nothing")
  }

  test("a release beyond the reservation names both amounts; of an order with none, RESERVATION_NOT_FOUND; a zero amount is INVALID_REQUEST") {
    open("rv2", 1000)
    reserve("rv2", "o1", 300)
    val (status, body) = release("rv2", "o1", 1, 301)
    assertEquals((status, field(body, "error"), field(body, "amount"), field(body, "reserved")),
      (409, Some("RELEASE_EXCEEDS_RESERVATION"), Some("301"), Some("300")))
    assertEquals(release("rv2", "o9", 1, 1)._1, 404)
    assertEquals(field(reserve("rv2", "o3", 0)._2, "error"), Some("INVALID_REQUEST"))
  }

  test("FR-008a: unused without an order, and an order on a merge or a clearing move, are INVALID_REQUEST, moving nothing") {
    open("rv-fr8a", 1000)
    reserve("rv-fr8a", "o1", 600)
    val accounts = s"/v1/casino/brands/$brand/accounts"
    def body(extra: String) = Some(s"""{"key":"k-${extra.hashCode.abs}","currency":"EUR","player":"rv-fr8a","amount":100$extra}""")
    val noOrder = send("POST", s"$accounts/pool/m-fr8a/splits", body(""","unused":50"""), as = exchange)
    assertEquals((noOrder._1, field(noOrder._2, "error")), (400, Some("INVALID_REQUEST")), noOrder._2)
    val merge = send("POST", s"$accounts/pool/m-fr8a/merges", body(""","orderId":"o1""""), as = exchange)
    assertEquals(field(merge._2, "error"), Some("INVALID_REQUEST"), merge._2)
    val moved = send("POST", s"$accounts/clearing/c-fr8a/moves", body(""","orderId":"o1","direction":"from-wallet""""), as = exchange)
    assertEquals(field(moved._2, "error"), Some("INVALID_REQUEST"), moved._2)
    val balance = send("GET", s"${wallet("rv-fr8a")}/balance")._2
    assertEquals((field(balance, "realBalance"), field(balance, "reserved")), (Some("1000"), Some("600")))
  }

  test("the player's and the operator's balance carry reserved and available in major units (FR-012)") {
    open("rv3", 100000)
    reserve("rv3", "o1", 60000)
    val (ps, player) = sendWithToken("GET", s"/v1/casino/players/$brand/wallets/EUR/balance", TestTokens.player("rv3"))
    assertEquals((ps, field(player, "reserved"), field(player, "available")), (200, Some("600.00"), Some("400.00")))
    val (os, operator) = sendWithToken("GET", s"/v1/casino/backoffice/$brand/wallets/rv3/EUR/balance", TestTokens.operator("op-1"))
    assertEquals((os, field(operator, "reserved"), field(operator, "available")), (200, Some("600.00"), Some("400.00")))
  }

  test("the transactions read lists a reservation with its reference; a hold preview answers against available; deposit sums are unchanged") {
    open("rv4", 1000)
    reserve("rv4", "o1", 600)
    val page = eventually(send("GET", s"${wallet("rv4")}/transactions?type=reservation")._2)(field(_, "total").contains("1"))
    assert(page.contains("\"reference\":\"o1\""), page)
    val preview = send("GET", s"${wallet("rv4")}/holds/preview?amount=500")._2
    assertEquals((field(preview, "refusal"), field(preview, "available")), (Some("INSUFFICIENT_FUNDS"), Some("400")))
    val sums = send("GET", s"${wallet("rv4")}/deposit-sums")._2
    assert(!sums.contains("600"), sums)
  }

  test("a player erased with a reservation standing keeps the reserved amount: the reservation names no person (spec Edge Cases, R13)") {
    open("rv5", 1000)
    reserve("rv5", "o1", 600)
    assertEquals(send("POST", s"/v1/casino/brands/$brand/erasure/players/rv5", as = Caller.Service(Harness.Project, "players"))._1, 200)
    assertEquals(field(send("GET", s"${wallet("rv5")}/balance")._2, "reserved"), Some("600"))
  }
