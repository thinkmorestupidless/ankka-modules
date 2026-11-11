package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, KeyValueEntityTestKit}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{Recorded, Settings}
import io.eitheror.casino.reporting.Facts.*

import java.time.{Instant, LocalDate, ZoneId}

/** T028: `Coverage` keeps a topic's earliest fact in the generation; the intake tells it only an earlier one. */
class CoverageSuite extends munit.FunSuite:
  private val jhb = ZoneId.of("Africa/Johannesburg")

  override def beforeEach(context: BeforeEach): Unit = Coverage.forget()

  test("keeps the earliest time it is told") {
    val k = KeyValueEntityTestKit.of(Coverage, Coverage.id(1, "money.transactions"))
    k.call(Coverage.observe)(Observed("money.transactions", at)): Unit
    k.call(Coverage.observe)(Observed("money.transactions", at.plusSeconds(60))): Unit
    assertEquals(k.currentState.earliest, Some(at))
    assert(!k.call(Coverage.observe)(Observed("money.transactions", at.plusSeconds(1))).changed)
    k.call(Coverage.observe)(Observed("money.transactions", at.minusSeconds(60))): Unit
    assertEquals(k.currentState, CoverageState("money.transactions", Some(at.minusSeconds(60))))
  }

  test("complete from the first whole reporting day: its own day only from its midnight") {
    assertEquals(Coverage.completeFrom(Instant.parse("2026-11-07T22:00:00Z"), jhb), LocalDate.parse("2026-11-08"))
    assertEquals(Coverage.completeFrom(Instant.parse("2026-11-07T22:00:01Z"), jhb), LocalDate.parse("2026-11-09"))
    assertEquals(Coverage.completeFrom(Instant.parse("2026-11-08T10:00:00Z"), jhb), LocalDate.parse("2026-11-09"))
  }

  test("an intake consumer tells its topic's coverage in the generation, then only an earlier fact") {
    val r   = Recorded()
    val kit = ConsumerTestKit.of(MoneyFacts(Settings("goldvibe", generation = 5)), client = r.client)
    kit.onMessage(Some(deposit("t1", 100, at))): Unit
    kit.onMessage(Some(deposit("t2", 100, at.plusSeconds(60)))): Unit
    kit.onMessage(Some(deposit("t3", 100, at.minusSeconds(60)))): Unit
    val told = r.calls.filter(_.component == "coverage")
    assertEquals(told.map(_.entity).distinct, Vector("g5/money.transactions"))
    assertEquals(told.map(_.as[Observed](using Coverage.given_Serializer_Observed).at), Vector(at, at.minusSeconds(60)))
  }

  test("each intake topic has its own coverage") {
    val r = Recorded()
    ConsumerTestKit.of(PaymentsFacts(Settings("goldvibe")), client = r.client)
      .onMessage(Some(f.PaymentExpired("pay-1", "p-1", "psp", at))): Unit
    ConsumerTestKit.of(SessionsFacts(Settings("goldvibe")), client = r.client)
      .onMessage(Some(f.PlayerSessionEnded("p-1", "s-1", "logout", at))): Unit
    assertEquals(r.calls.filter(_.component == "coverage").map(_.entity), Vector("g1/payments.payments", "g1/casino.sessions"))
  }
