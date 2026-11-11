package io.eitheror.casino.reporting.predictions

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits}
import io.eitheror.casino.reporting.day.{Close, CloseInputs, Day, DayEndInputs, DayEvent, DayState, Delta, MarketDay, ToDays}

import java.time.{Instant, ZoneId}

/** T066–T067: a market's book from its trades and payments, its day, and the fee account's credits held to the fees. */
class MarketBookSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-11-08T10:00:00Z")
  private val eur = Currency.unsafe("EUR")
  private def m(v: Long) = MinorUnits.unsafe(v)

  private val split = f.TradeMade("mk", "t1", 1, "split", 10, Vector(f.OutcomePrice("yes", 60), f.OutcomePrice("no", 40)), Vector(
    f.TradeLeg("p1", "o1", "yes", "buy", m(600), m(0), m(0), 0), f.TradeLeg("p2", "o2", "no", "buy", m(400), m(0), m(0), 0)), eur, at)
  private val direct = f.TradeMade("mk", "t2", 2, "direct", 4, Vector(f.OutcomePrice("yes", 70)), Vector(
    f.TradeLeg("p3", "o3", "yes", "buy", m(280), m(0), m(20), 1, Some(m(1))), f.TradeLeg("p1", "o4", "yes", "sell", m(0), m(280), m(0), 0)),
    eur, at.plusSeconds(60))
  private val paid = f.PositionPaid("mk", "pos-1", "p1", "yes", 6, m(600), eur, at.plusSeconds(120))

  private def book(facts: f.ExchangeTradesFact*): (MarketBookState, Vector[MarketBookEvent]) =
    facts.zipWithIndex.foldLeft((MarketBookState(), Vector.empty[MarketBookEvent])) { case ((s, es), (fact, i)) =>
      MarketBooks.event(s, s"f$i", fact).fold((s, es))(e => (MarketBooks.apply(s, e), es :+ e))
    }

  test("a book counts contracts, notional at each traded outcome's price, trades, traders, fees, payments and open interest") {
    val (s, _) = book(split, direct, paid, split)
    assertEquals((s.contracts, s.notional, s.trades, s.traders, s.fees, s.payments), (14L, 1_000L + 280L, 2L, Set("p1", "p2", "p3"), 1L, 600L))
    // p1 bought 10 YES, sold 4, was paid on 6: 0; p2 holds 10 NO; p3 holds 4 YES.
    assertEquals(s.openInterest, 14L)
  }

  test("a leg's net is what moved to and from the player's real balance: a release moves none") {
    val (_, es) = book(direct)
    val legs = es.collect { case t: MarketBookEvent.Traded => t.legs }.flatten
    assertEquals(legs.map(l => l.player -> l.net), Vector("p3" -> -281L, "p1" -> 280L))
  }

  test("a market's day holds its trades and payments in the predictions section alone (FR-032)") {
    val (_, es) = book(split, paid)
    val ds = es.flatMap(e => ToDays.market(e, ZoneId.of("UTC")).values)
    assert(ds.forall(_.money == io.eitheror.casino.reporting.day.Money()), ds.toString)
    assertEquals(ds.flatMap(_.predictions.values).foldLeft(MarketDay())(_ + _), MarketDay(10, 1_000, 1, 0, 600))
  }

  test("the close lists every trade whose fee and credits differ or that one side lacks, and a trade with neither agrees") {
    val rows = Vector(
      TradeFeeState(1, "t1", "mk", "EUR", "2026-11-08", 0, traded = true),
      TradeFeeState(1, "t2", "mk", "EUR", "2026-11-08", 3, traded = true, credits = 3, credited = true),
      TradeFeeState(1, "t3", "mk", "EUR", "2026-11-08", 3, traded = true, credits = 2, credited = true),
      TradeFeeState(1, "t4", "mk", "EUR", "2026-11-08", 2, traded = true),
      TradeFeeState(1, "t5", "", "EUR", "2026-11-08", 0, credits = 7, credited = true))
    val (credits, differ) = DayEndInputs.fees(rows)
    assertEquals(credits, Map("EUR" -> Map("mk" -> 5L, "" -> 7L)))
    assertEquals(differ.map(d => d.trade -> d.status), Vector("t5" -> "credit_only", "t3" -> "differs", "t4" -> "fee_only"))
    val folded = Day.apply(DayState(), DayEvent.Folded("s", 1, Delta("EUR", 10, predictions = Map("mk" -> MarketDay(fees = 8)))))
    val closed = Day.close(folded, Close(at, "UTC", CloseInputs(feeCredits = credits, feeDifferences = differ))).map(Day.apply(folded, _)).get
    assertEquals(closed.currencies("EUR").predictions, Map("mk" -> MarketDay(fees = 8, feeCredits = 5), "" -> MarketDay(feeCredits = 7)))
    assertEquals(closed.feeDifferences, differ)
  }
