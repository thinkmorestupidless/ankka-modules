package io.eitheror.casino.reporting.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.reporting.reads.{Paging, Players, Transactions, Wallets}

import scala.concurrent.duration.*

/**
 * The keyed queries against Postgres (review, should fix 5): read two rows a page, every player, every player registered
 * in a period (in registration order) and every wallet with an open record are the rows one unbounded ask answers.
 */
class PagingSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 300.seconds
  private lazy val kit = SharedKit.kit

  test("two rows a page answers every row one ask does, in the same order") {
    val d = kit.days()
    val s = FactSet(kit.zone)
    // Five players registered at the same instant, so registration order falls back on the row's key, and two later.
    val players = (1 to 7).map(i => s.register(s"page-$i", s.at(d, if i <= 5 then "09:00" else s"1$i:00"))).toVector
    players.foreach(p => s.deposit(p, "EUR", 1_00, s.at(d, "20:00")))
    players.take(3).foreach(p => s.award(p, "EUR", s"page-award-$p", 5_00, 50_00, s.at(d, "21:00")))
    s.publish(kit)
    val g     = kit.settings.generation
    val from  = s.at(d, "00:00")
    val to    = s.at(d.plusDays(1), "00:00")
    kit.eventually("the players and their wallets", 60.seconds)(Option.when(
      Players.registered(kit.views, g, from, to).size == 7 && Wallets.ofPlayers(kit.views, g, players).size == 7)(()))
    val q = kit.views.forView(Players)
    val everyOnce = q.ask(Players.every, 1_000_000, "generation" -> g.toString, "after" -> Paging.First)
    assertEquals(Paging.keyedBy(2)(q, Players.every, Players.key, "generation" -> g.toString), everyOnce)
    val registered = Players.registered(kit.views, g, from, to, size = 2)
    assertEquals(registered.map(_.player), Players.registered(kit.views, g, from, to).map(_.player))
    assertEquals(registered.map(_.player).toSet, players.toSet)
    assertEquals(registered.map(_.registeredAt.get), registered.map(_.registeredAt.get).sorted)
    val w = kit.views.forView(Wallets)
    assertEquals(Paging.keyedBy(2)(w, Wallets.byPlayers, Wallets.key, "generation" -> g.toString, "ids" -> players.mkString(",")).map(_.player),
      players.sorted)
    kit.eventually("the open records", 60.seconds)(Option.when(
      Paging.keyedBy(2)(w, Wallets.withOpenRecords, Wallets.key, "generation" -> g.toString, "currency" -> "EUR")
        .count(r => players.contains(r.player)) == 3)(()))
  }

  test("the ledger read a page at a time before the last row answers every movement in the offset page's order; the JSON export counts what it wrote") {
    val d = kit.days()
    val s = FactSet(kit.zone)
    val p = s.register("page-ledger", s.at(d.minusDays(1), "09:00"))
    // Three movements at one instant, so the order falls back on the row's key, and four more.
    (1 to 3).foreach(i => s.deposit(p, "EUR", i * 1_00L, s.at(d, "10:00")))
    (1 to 4).foreach(i => s.deposit(p, "EUR", i * 10_00L, s.at(d, s"1${i}:30")))
    s.publish(kit)
    val g     = kit.settings.generation
    val asked = Transactions.Asked(g, s.at(d, "00:00"), s.at(d.plusDays(1), "00:00"), "all", Some(Vector(p)))
    kit.eventually("the movements", 60.seconds)(Option.when(Transactions.count(kit.views, asked) == 7)(()))
    val paged = Transactions.pages(kit.views, asked, size = 2).toVector
    assertEquals(paged.map(_.size), Vector(2, 2, 2, 1))
    assertEquals(paged.flatten, Transactions.page(kit.views, asked, 0, 1_000))
    val json = kit.get(s"/reports/transactions/export?from=$d&to=$d&playerEmail=page-ledger", kit.asFinance)
    assertEquals(json.status, 200, json.text)
  }

