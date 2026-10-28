package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import io.eitheror.casino.attribution.{Binding, MoneyEvent}
import io.eitheror.casino.attribution.carriers.*
import io.eitheror.casino.attribution.money.{DayTally, OpenDays, Tally}
import io.eitheror.casino.attribution.money.RealMoney.Leg
import io.eitheror.casino.attribution.record.{AttributionRecord, Record}
import io.eitheror.casino.attribution.views.Records

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import scala.concurrent.duration.*

/**
 * The four views (T022): each query after the events that fill it. Scala views run only against a real
 * database, so this starts the entities, the workflow and the views on ankka's integration kit.
 */
class ViewsSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private val t0     = Instant.parse("2026-10-25T10:00:00Z")
  private val broker = InMemoryBroker()
  private var kit: AnkkaTestKit = scala.compiletime.uninitialized
  private def run = UUID.randomUUID().toString.take(8)
  private def views = kit.service.viewClient

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(AttributionRecord.descriptor, Records.descriptor, DayTally.descriptor, OpenDays.descriptor, NetworkFeed.descriptor,
        FeedRows.descriptor, WebhookSubscription.descriptor, Delivery.descriptor, Deliveries.descriptor),
      Seq(ProjectionRuntime.withBroker(broker, broker))
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def binding(network: String) = Binding(network, "aff-1", "lnk-1", None, Vector.empty, Some("c-1"), "pseud", t0, Binding.Tag)

  test("Records: a bound player's row, by player and by network; the counts per network") {
    val net = s"net-$run"
    val p1  = s"p-$run-1"
    val p2  = s"p-$run-2"
    val records = kit.componentClient
    records.forEventSourcedEntity(EntityId(p1)).call(AttributionRecord.registered).invoke(Record.Register(t0, None, Some(binding(net)), None)): Unit
    records.forEventSourcedEntity(EntityId(p1)).call(AttributionRecord.money)
      .invoke(MoneyEvent(MoneyEvent.Deposit, "EUR", 5000, "pay-1", t0.plusSeconds(5), "d-1")): Unit
    records.forEventSourcedEntity(EntityId(p2)).call(AttributionRecord.registered).invoke(Record.Register(t0, None, None, None)): Unit

    val rows = views.forView(Records)
    val row = kit.eventually("the bound row with its first deposit")(
      rows.ask(Records.byPlayer, "player" -> p1).headOption.filter(_.firstDepositAt.isDefined))
    assertEquals((row.network, row.bound, row.firstDepositAt), (Some(net), true, Some(t0.plusSeconds(5))))
    kit.eventually("the organic row")(rows.get(p2))
    assertEquals(rows.ask(Records.boundTo, "network" -> net).map(_.playerId), Vector(p1))
    assertEquals(Records.countsByNetwork(rows, Seq(net, "nobody")), Map(net -> 1L, "nobody" -> 0L))
  }

  test("OpenDays: an open day is before a later day; a closed day leaves openBefore and is the player's closed day") {
    val player = s"p-$run"
    val open   = DayTally.id(player, "EUR", "2026-10-24")
    val shut   = DayTally.id(player, "EUR", "2026-10-23")
    def leg(day: String, id: String) = Tally.LegCmd(player, "EUR", day, Leg.Stake, "r-1", id, 1000, t0)
    kit.componentClient.forEventSourcedEntity(EntityId(open)).call(DayTally.leg).invoke(leg("2026-10-24", "s-1")): Unit
    kit.componentClient.forEventSourcedEntity(EntityId(shut)).call(DayTally.leg).invoke(leg("2026-10-23", "s-2")): Unit
    kit.componentClient.forEventSourcedEntity(EntityId(shut)).call(DayTally.close).invoke(t0): Unit

    val rows = views.forView(OpenDays)
    def openFor(day: String) = rows.ask(OpenDays.openBefore, "day" -> day).filter(_.player == player)
    kit.eventually("the closed day")(rows.ask(OpenDays.closedFor, "player" -> player).headOption)
    kit.eventually("the open day")(openFor("2026-10-25").headOption)
    assertEquals(openFor("2026-10-25").map(_.day), Vector("2026-10-24"))
    assertEquals(openFor("2026-10-24"), Vector.empty)
    val closed = rows.ask(OpenDays.closedFor, "player" -> player)
    assertEquals(closed.map(d => (d.day, d.stake, d.win)), Vector(("2026-10-23", 1000L, 0L)))
  }

  test("FeedRows: a page is the network's facts after the cursor, in order, at most the limit") {
    val net = s"net-$run"
    val feed = kit.componentClient.forEventSourcedEntity(EntityId(net))
    (1 to 25).foreach(i => feed.call(NetworkFeed.append).invoke(FeedFact(s"click/$run-$i", "click", s"""{"n":$i}""")): Unit)
    val rows = views.forView(FeedRows)
    kit.eventually("all 25")(rows.get(FeedRows.key(net, 25)))
    val page = rows.ask(FeedRows.page, 10, "network" -> net, "after" -> "5")
    assertEquals(page.map(_.seq), (6L to 15L).toVector)
    assertEquals(page.head.fact, """{"n":6}""")
    assertEquals(rows.ask(FeedRows.page, 1000, "network" -> net, "after" -> "0").size, 25)
    assertEquals(rows.ask(FeedRows.page, "network" -> s"other-$run", "after" -> "0"), Vector.empty)
  }

  test("Deliveries: a delivered fact is listed delivered, and not undelivered") {
    val net = s"net-$run"
    val sub = UUID.randomUUID().toString
    Delivery.clock.set(Clock.fixed(t0, ZoneOffset.UTC))
    Delivery.http.set((_, _, _) => Right(200))
    kit.componentClient.forKeyValueEntity(EntityId(sub)).call(WebhookSubscription.subscribe)
      .invoke(CreateSubscription(sub, net, "https://n.example/hook", "staff-1", t0)): Unit
    kit.secrets.put(WebhookSubscription.secretName(sub), "s3cret")
    val fact = FeedFact(s"click/$run", "click", "{}")
    kit.componentClient.forWorkflow(Delivery.id(sub, fact.factId)).call(Delivery.start).invoke(DeliveryStart(sub, net, fact)): Unit
    val rows = views.forView(Deliveries)
    val row = kit.eventually("delivered")(rows.get(s"$sub/${fact.factId}").filter(_.status == DeliveryRules.Delivered))
    assertEquals((row.network, row.factId, row.attempts, row.lastStatus), (net, fact.factId, 1, Some(200)))
    assertEquals(rows.ask(Deliveries.undelivered, "network" -> net), Vector.empty)
  }
