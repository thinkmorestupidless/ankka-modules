package io.eitheror.casino.exchange.features

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import com.thinkmorestupidless.predictions.exchange.{OrderId, OrderState, OutcomeId, OwnerId}
import io.eitheror.casino.exchange.it.{Kit, Reply, SharedKit}

import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

/**
 * A feature file of features/predictions bound against the whole service (constitution VII): a
 * player's calls through the player's routes, a book opened as `markets` opens it, the gates and the
 * wallet scripted at their route contracts. A market's name is suffixed per scenario, since the
 * database outlives scenarios; the fakes are cleared before each.
 */
abstract class ExchangeFeatures(file: String) extends GherkinSuite(s"../../features/predictions/$file") with LogCapturing:
  override val munitTimeout: scala.concurrent.duration.Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit

  protected var run         = ""
  protected var last: Reply = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()
    run = UUID.randomUUID().toString.take(8)
    last = null

  protected def id(market: String): String = s"$market-$run"

  protected def ok(r: Reply): Reply =
    assert(r.status == 200, s"${r.status}: ${r.text}")
    r

  Given("a brand running the module {string}") { (module: String) => assertEquals(module, "predictions") }

  Given("an open market {string} with the outcomes {string} and {string} in {string}") { (m: String, a: String, b: String, c: String) =>
    ok(kit.open(id(m), Vector(a, b), c)): Unit
  }

  Given("a player {string} with an active account and a wallet in {string} with a real balance of {string}") {
    (p: String, _: String, balance: String) => kit.wallet.real.put(p, balance.toLong): Unit
  }

  Given("the play gate allows {string}") { (p: String) => kit.gates.kyc.remove(p): Unit }
  Given("the bet gate allows {string}") { (p: String) => kit.gates.rg.remove(p): Unit }

  Given("the play gate and the bet gate allow {string}") { (p: String) => kit.gates.kyc.remove(p); kit.gates.rg.remove(p): Unit }

  Given("the play gate and the bet gate allow {string} and {string}") { (a: String, b: String) =>
    Seq(a, b).foreach { p => kit.gates.kyc.remove(p); kit.gates.rg.remove(p) }
  }

  protected def place(p: String, side: String, o: String, n: String, outcome: String, m: String, price: String): Reply =
    made()
    last = kit.place(p, id(m), o, side, outcome, n.toLong, price.toLong)
    last

  When("{string} places a buy order {string} for {string} shares of {string} in {string} at price {string}") {
    (p: String, o: String, n: String, outcome: String, m: String, price: String) => place(p, "buy", o, n, outcome, m, price): Unit
  }

  When("{string} places a sell order {string} for {string} shares of {string} in {string} at price {string}") {
    (p: String, o: String, n: String, outcome: String, m: String, price: String) => place(p, "sell", o, n, outcome, m, price): Unit
  }

  Then("the order {string} rests in the book of {string} in {string}") { (o: String, outcome: String, m: String) =>
    ok(last): Unit
    val book = kit.book(id(m))
    assertEquals(book.orders.get(OrderId(o)).map(_.state), Some(OrderState.Resting))
    val resting = book.books.get(OutcomeId(outcome)).toVector.flatMap(b => b.buys ++ b.sells).map(_.order)
    assert(resting.contains(OrderId(o)), resting.toString)
  }

  Then("nothing rests in the book of {string} in {string}") { (outcome: String, m: String) =>
    val b = kit.book(id(m)).books.get(OutcomeId(outcome))
    assert(b.forall(x => x.buys.isEmpty && x.sells.isEmpty), b.toString)
  }

  Then("the reserved amount of {string} is {string}") { (p: String, n: String) =>
    kit.eventually(s"$p's reservations at $n")(Option.when(kit.wallet.reserved(p) == n.toLong)(()))
  }

  /** Holdings a scenario's Givens asked for, made together before the next step that acts (`made`). */
  private var wanted = Vector.empty[(String, Long, String, String)]

  override def afterEach(context: AfterEach): Unit =
    wanted = Vector.empty
    super.afterEach(context)

  /**
   * `p` holds `n` shares of `outcome`: asked for here, made by `made`. Two holdings of complementary
   * outcomes and the same quantity are one split trade between their players; any other is bought
   * against a counterpart's complementary buy. Each is settled before the scenario goes on.
   */
  protected def holds(p: String, n: Long, outcome: String, m: String): Unit = wanted = wanted :+ ((p, n, outcome, m))

  protected def made(): Unit =
    @scala.annotation.tailrec def pair(left: Vector[(String, Long, String, String)]): Unit = left match
      case (p, n, o, m) +: rest =>
        val other = kit.book(id(m)).outcomes.map(_.value).find(_ != o).get
        val partner = rest.find(w => w._1 != p && w._2 == n && w._3 == other && w._4 == m)
        val counterpart = partner.fold { kit.wallet.real.put(s"partner-$run", 100000L): Unit; s"partner-$run" }(_._1)
        ok(kit.place(p, id(m), s"hold-$p-$run", "buy", o, n, 60)): Unit
        ok(kit.place(counterpart, id(m), s"hold-$counterpart-$run-$o", "buy", other, n, 40)): Unit
        kit.eventually(s"$p holds $n of $o") {
          Option.when(kit.book(id(m)).position(OwnerId(p), OutcomeId(o)).quantity == n && kit.wallet.reserved(p) == 0 &&
            kit.wallet.reserved(counterpart) == 0)(())
        }
        pair(partner.fold(rest)(x => rest.filterNot(_ == x)))
      case _ => ()
    val w = wanted
    wanted = Vector.empty
    pair(w)

  Given("{string} holds {string} shares of {string} in {string}") { (p: String, n: String, outcome: String, m: String) =>
    holds(p, n.toLong, outcome, m)
  }

  Given("the brand's taker fee rate is {string} basis points") { (_: String) =>
    fail("the taker fee waits on ankka-predictions spec 007")
  }

class OrdersFeatures extends ExchangeFeatures("orders.feature"):

  override protected def ranOutside: Map[String, String] = Map(
    "with a taker fee a buy order reserves its cost and the most its fee can be" ->
      "the taker fee is ankka-predictions spec 007 (v0.2.0); this module builds on v0.1.0 with the rate 0 (research R8)"
  )

  Then("the account of {string}, the play gate and the bet gate were asked, in that order") { (p: String) =>
    assertEquals(kit.gates.asked(p), Vector("account", "play gate", "bet gate"))
  }

  Then("a reservation of {string} is placed for order {string} on the wallet of {string}") { (n: String, o: String, p: String) =>
    // The wallet knows an order under its market (research R4); a scenario's orders are in one market.
    val held = kit.wallet.reservations.asScala.collect { case (k, v) if k.endsWith(s"/$o") => v }.toVector
    assertEquals(held, Vector(p -> n.toLong))
  }

  Given("the play gate refuses {string}") { (p: String) => kit.gates.kyc.put(p, "pending"): Unit }

  Then("it is refused naming the play gate") { () =>
    assertEquals(last.status, 403, last.text)
    assert(last.str("error").startsWith("play gate:"), last.text)
  }

  Given("the bet gate refuses {string} as {string}") { (p: String, reason: String) => kit.gates.rg.put(p, reason.replace(' ', '_')): Unit }

  Then("it is refused naming the bet gate and the reason {string}") { (reason: String) =>
    assertEquals(last.status, 403, last.text)
    assertEquals(last.str("error"), s"bet gate: ${reason.replace(' ', '_')}")
  }

  Then("it is refused as {string}") { (reason: String) =>
    assertEquals(last.status, 409, last.text)
    assertEquals(last.str("error"), reason)
  }

  Then("it is refused naming the position") { () =>
    assertEquals(last.status, 400, last.text)
    assertEquals(last.str("error"), "quantity held")
  }
