package io.eitheror.casino.attribution.facts

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}
import io.eitheror.casino.attribution.{Binding, MoneyEvent, Recorded, Wiring}
import io.eitheror.casino.attribution.clicks.{ClickFacts, ClickRecord, ClickState}
import io.eitheror.casino.attribution.facts.Facts.ClosedDay
import io.eitheror.casino.attribution.money.Tally
import io.eitheror.casino.attribution.money.RealMoney.Leg
import io.eitheror.casino.attribution.networks.{NetworkCodecs, NetworkState}
import io.eitheror.casino.attribution.record.{Record, RecordCodecs}
import io.eitheror.casino.contract.facts.*

import java.time.Instant

/** The three fact publishers (T024): what each event becomes on `affiliates.attribution`, keyed and identified. */
class PublishersSuite extends munit.FunSuite with LogCapturing:
  private val t0 = Instant.parse("2026-10-25T10:00:00Z")
  private def binding(touch: String = Binding.Tag, at: Instant = t0) =
    Binding("programme", "aff-1", "lnk-1", None, Vector.empty, Some("c-1"), "pseud", at, touch)
  private val click = ClickRecord("c-1", Some("programme"), Some("aff-1"), Some("lnk-1"), at = t0, deviceType = "mobile")

  Wiring.brand.set("goldvibe")

  /** A consumer is constructed with the kit, reading the wiring then. */
  private def recordFacts(days: Vector[ClosedDay] = Vector.empty) =
    Wiring.closedDays.set(_ => days)
    ConsumerTestKit.of(RecordFacts)

  test("ClickFacts: one click keyed by the link with ce-id click/<id>; nothing for a refused click") {
    val kit = ConsumerTestKit.of(ClickFacts)
    val r   = kit.onMessage(ClickState(Some(click)))
    assertEquals(r.payloads.map(_.factId), Vector("click/c-1"))
    assertEquals(r.keys, Vector(Some("lnk-1")))
    assertEquals(r.messages.head.metadata.get("ce-id"), Some("click/c-1"))
    assertEquals(kit.onMessage(ClickState(Some(click.copy(refused = Some("bad_signature"))))).effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(ClickState(None)).effect, ConsumerEffect.Ignore)
  }

  test("RecordFacts: Bound is a registration keyed by the pseudonym, the same twice") {
    val kit = recordFacts()
    val ev  = Record.Bound(binding(), Some("GB"), Vector.empty, None)
    val one = kit.onMessage(ev, subject = "p-1")
    assertEquals(one.payloads.map(_.factId), Vector("registration/pseud"))
    assertEquals(one.keys, Vector(Some("pseud")))
    assertEquals(one.messages.head.metadata.get("ce-id"), Some("registration/pseud"))
    assertEquals(kit.onMessage(ev, subject = "p-1").payloads, one.payloads)
  }

  test("RecordFacts: a later code's Bound adds the days closed before it, marked; nothing while unbound") {
    val days = Vector(ClosedDay("EUR", "2026-10-23", 1000, 300, t0.minusSeconds(3600)), ClosedDay("EUR", "2026-10-25", 500, 0, t0.plusSeconds(60)))
    val kit  = recordFacts(days)
    val r    = kit.onMessage(Record.Bound(binding(Binding.LaterCode), None, Vector.empty, None), subject = "p-1")
    val daily = r.payloads.collect { case g: GgrDaily => g }
    assertEquals(daily.map(_.reportingDay), Vector("2026-10-23"))
    assertEquals(daily.head.beforeBinding, Some(true))
    val pending = MoneyEvent(MoneyEvent.Deposit, "EUR", 5000, "pay-1", t0, "d-1")
    assertEquals(kit.onMessage(Record.PendingRecorded(pending, first = true)).effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(Record.Registered(t0, None)).effect, ConsumerEffect.Ignore)
  }

  private def tallyKit(bound: Boolean, stream: Boolean = false) =
    val record = Record.State(binding = Option.when(bound)(binding()))
    val r = Recorded(c => c.component match
      case "attribution-record" => RecordCodecs.given_Serializer_State.toBytes(record)
      case _                    => NetworkCodecs.given_Serializer_NetworkState.toBytes(NetworkState(id = "programme", roundStream = stream)))
    (r, ConsumerTestKit.of(TallyFacts, r.client))

  private val closed = Tally.Closed("p-1", "EUR", "2026-10-25", t0.plusSeconds(86400), 1000, 300)

  test("TallyFacts: a closed day reads the record and publishes ggr-daily; nothing for an unbound player") {
    val (calls, kit) = tallyKit(bound = true)
    val r = kit.onMessage(closed)
    assertEquals(r.payloads.collect { case g: GgrDaily => (g.reportingDay, g.ggr.value) }, Vector(("2026-10-25", 700L)))
    assertEquals(calls.calls.map(c => (c.component, c.entity)), Vector(("attribution-record", "p-1")))
    assertEquals(tallyKit(bound = false)._2.onMessage(closed).effect, ConsumerEffect.Ignore)
  }

  test("TallyFacts: a day with no movement and a stake leg read nothing; ggr-round only when the stream is on") {
    val (calls, kit) = tallyKit(bound = true)
    assertEquals(kit.onMessage(closed.copy(stake = 0, win = 0)).effect, ConsumerEffect.Ignore)
    val stake = Tally.LegRecorded("p-1", "EUR", "2026-10-25", Leg.Stake, "r-1", "s-1", 1000, t0, None)
    assertEquals(kit.onMessage(stake).effect, ConsumerEffect.Ignore)
    assert(calls.calls.isEmpty)
    val win = Tally.LegRecorded("p-1", "EUR", "2026-10-25", Leg.Win, "r-1", "w-1", 300, t0, Some(Tally.RoundDelta(1000, 300, true)))
    assertEquals(kit.onMessage(win).effect, ConsumerEffect.Ignore)
    assertEquals(tallyKit(bound = true, stream = true)._2.onMessage(win).payloads.map(_.factId).size, 1)
  }
