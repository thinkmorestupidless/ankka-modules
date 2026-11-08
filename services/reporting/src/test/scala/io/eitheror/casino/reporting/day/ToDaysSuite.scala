package io.eitheror.casino.reporting.day

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, MovableClock}
import io.eitheror.casino.reporting.{Call, Recorded, ReportingService, Settings}
import io.eitheror.casino.reporting.intake.*

import java.time.{Instant, ZoneId}

/** T026: what each intake event folds into which day, the test flag read at the fold, the source and sequence. */
class ToDaysSuite extends munit.FunSuite:
  private val jhb = ZoneId.of("Africa/Johannesburg")
  private val at  = Instant.parse("2026-11-08T10:00:00Z") // 12:00 in Johannesburg
  private val settings = Settings("goldvibe", reportingZone = jhb)

  private def moved(family: String, amount: Long, depositPart: Long = 0, day: String = "2026-11-08", roundDay: Option[String] = None,
      casino: Boolean = true, at: Instant = at) =
    Moved(s"f-$family", family, "", amount, depositPart, roundDay.map(_ => "r-1"), "ref", day, roundDay, at, casino, None, "p-1", "EUR", "t1", 0, 0)

  test("a deposit, a stake, a win, a provider credit and a rollback on their own day and hour") {
    assertEquals(ToDays.wallet(moved("deposit_confirmed", 1000), jhb), Map("2026-11-08" -> Delta("EUR", 12, Money(deposits = 1000, depositCount = 1))))
    assertEquals(ToDays.wallet(moved("provider_credited", 60), jhb), Map("2026-11-08" -> Delta("EUR", 12, Money(wins = 60))))
    assertEquals(ToDays.wallet(moved("bet_placed", 200, 150, roundDay = Some("2026-11-08")), jhb),
      Map("2026-11-08" -> Delta("EUR", 12, Money(stakes = 200, poolGgr = 150))))
    assertEquals(ToDays.wallet(moved("bet_rolled_back", 200, 150, roundDay = Some("2026-11-08")), jhb),
      Map("2026-11-08" -> Delta("EUR", 12, Money(rollbacks = 200, poolGgr = -150))))
  }

  test("the deposit pool's leg counts on its round's day, the GGR leg on its own") {
    val win = moved("win_credited", 300, 200, day = "2026-11-09", roundDay = Some("2026-11-08"))
    assertEquals(ToDays.wallet(win, jhb), Map(
      "2026-11-09" -> Delta("EUR", 12, Money(wins = 300)),
      "2026-11-08" -> Delta("EUR", 12, Money(poolGgr = -200))))
  }

  test("spec 022's families, a loss, an award and the records fold nothing") {
    assertEquals(ToDays.wallet(moved("settlement_debit", 500, casino = false), jhb), Map.empty)
    assertEquals(ToDays.wallet(moved("round_settled_as_loss", 0, roundDay = Some("2026-11-08")), jhb), Map.empty)
    assertEquals(ToDays.wallet(moved("restricted_funds_awarded", 500), jhb), Map.empty)
    assertEquals(ToDays.wallet(RecordsChanged("f", Vector.empty), jhb), Map.empty)
  }

  test("payments: an attempt, a capture, a lost dispute's chargeback; a won dispute nothing") {
    assertEquals(ToDays.payment(Initiated("f", "pay-1", "p-1", "psp-a", "t", 1000, "EUR", at), jhb),
      Map("2026-11-08" -> Delta("EUR", 12, payments = Map("psp-a" -> PaymentFigures(attempts = 1)))))
    assertEquals(ToDays.payment(Captured("f", "pay-1", "p-1", "psp-a", 1000, "EUR", at), jhb),
      Map("2026-11-08" -> Delta("EUR", 12, payments = Map("psp-a" -> PaymentFigures(completed = 1, value = 1000)))))
    assertEquals(ToDays.payment(DisputeClosed("f", "pay-1", "p-1", "psp-a", "lost", Some(900), Some("EUR"), at), jhb),
      Map("2026-11-08" -> Delta("EUR", 12, Money(chargebacks = 900, chargebackCount = 1))))
    assertEquals(ToDays.payment(DisputeClosed("f", "pay-1", "p-1", "psp-a", "lost", None, Some("EUR"), at), jhb),
      Map("2026-11-08" -> Delta("EUR", 12, Money(chargebackCount = 1))))
    assertEquals(ToDays.payment(DisputeClosed("f", "pay-1", "p-1", "psp-a", "won", Some(900), Some("EUR"), at), jhb), Map.empty)
  }

  test("withdrawals: a request; a completion with its amount and its seconds since the request") {
    assertEquals(ToDays.withdrawal(WithdrawalEvent.Requested("f", "w-1", "p-1", 2500, "EUR", "unity", "t", at), jhb),
      Map("2026-11-08" -> Delta("EUR", 12, gateways = Map("unity" -> GatewayFigures(requests = 1)))))
    val done = WithdrawalEvent.Completed("f", "w-1", "p-1", 2500, "EUR", "unity", at.plusSeconds(7200), Some(at))
    assertEquals(ToDays.withdrawal(done, jhb), Map("2026-11-08" -> Delta("EUR", 14, Money(withdrawals = 2500, withdrawalCount = 1),
      gateways = Map("unity" -> GatewayFigures(completed = 1, completionSeconds = 7200, timed = 1, amount = 2500)))))
    assertEquals(ToDays.withdrawal(done.copy(requestedAt = None), jhb)("2026-11-08").gateways("unity"), GatewayFigures(completed = 1, amount = 2500))
  }

  test("grants: a funds grant's amount and a free-rounds settlement's win are bonus cost") {
    assertEquals(ToDays.grant(GrantEvent.Granted("f", "b-1", "p-1", "o-1", "Welcome", "deposit_match", 1000, Some("EUR"), at), jhb),
      Map("2026-11-08" -> Delta("EUR", 12, Money(bonusCost = 1000))))
    assertEquals(ToDays.grant(GrantEvent.Granted("f", "b-2", "p-1", "o-2", "Spins", "free_spins", 0, None, at, 10), jhb), Map.empty)
    assertEquals(ToDays.grant(GrantEvent.FreeSpinsSettled("f", "b-2", "p-1", 250, "EUR", at), jhb),
      Map("2026-11-08" -> Delta("EUR", 12, Money(bonusCost = 250))))
  }

  test("a registration counts on its day and hour, with its test flag") {
    val r = PlayerLogEvent.Registered("f", "p-1", at, None, None, None, testAccount = true, at)
    assertEquals(ToDays.player(r, jhb), Map("2026-11-08" -> Delta(hour = 12, registrations = 1, test = true)))
  }

  private def recorded(test: Boolean) = Recorded(c => if c.method == "test-account" then test.toString.getBytes else "true".getBytes)
  private def folds(r: Recorded): Vector[(Call, Fold)] =
    r.calls.filter(_.method == "fold").map(c => c -> c.as[Fold](using BrandDay.given_Serializer_Fold))

  test("the consumer folds each day's delta under the source and sequence, marked as the player's flag says") {
    val r     = recorded(test = true)
    val clock = MovableClock.at(at.plusSeconds(5))
    val kit   = ConsumerTestKit.of(ToDaysConsumers.Wallets(settings, clock), client = r.client)
    val win   = moved("win_credited", 300, 200, day = "2026-11-09", roundDay = Some("2026-11-08"))
    assertEquals(kit.onMessage(win, subject = "g1/p-1/EUR", sequenceNumber = 7).effect, ConsumerEffect.Done)
    assertEquals(r.calls.head.entity, "g1/p-1")
    val sent = folds(r)
    assertEquals(sent.map(_._1.entity), Vector("g1/2026-11-08", "g1/2026-11-09"))
    assertEquals(sent.map(_._2.source).distinct, Vector("wallet-book/g1/p-1/EUR"))
    assertEquals(sent.map(_._2.seq).distinct, Vector(7L))
    assert(sent.forall(_._2.delta.test))
    assertEquals(sent.map(_._2.arrivedAt).distinct, Vector(at.plusSeconds(5)))
  }

  test("a player who is not a test account folds unmarked; another generation's event and an empty delta fold nothing") {
    val r   = recorded(test = false)
    val kit = ConsumerTestKit.of(ToDaysConsumers.Payments(settings), client = r.client)
    kit.onMessage(Captured("f", "pay-1", "p-1", "psp-a", 1000, "EUR", at), subject = "g1/pay-1"): Unit
    assert(folds(r).forall(!_._2.delta.test))
    val before = r.calls.length
    assertEquals(kit.onMessage(Captured("f", "pay-1", "p-1", "psp-a", 1000, "EUR", at), subject = "g2/pay-1").effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(Ended("f", "pay-1", "p-1", "psp-a", "expired", at), subject = "g1/pay-1").effect, ConsumerEffect.Ignore)
    assertEquals(r.calls.length, before)
  }

  test("a registration's flag is its own; the player's log is not asked") {
    val r   = recorded(test = false)
    val kit = ConsumerTestKit.of(ToDaysConsumers.Players(settings), client = r.client)
    kit.onMessage(PlayerLogEvent.Registered("f", "p-1", at, None, None, None, testAccount = true, at), subject = "g1/p-1"): Unit
    assert(!r.calls.exists(_.method == "test-account"))
    assert(folds(r).head._2.delta.test)
  }

  test("the five to-days consumers are registered for the generation") {
    val ids = ReportingService.descriptors(Settings("goldvibe", generation = 4)).map(_.componentId.toString)
    assertEquals(ids.filter(_.startsWith("to-days")).toSet,
      Set("to-days-wallets-g4", "to-days-payments-g4", "to-days-withdrawals-g4", "to-days-grants-g4", "to-days-players-g4"))
  }
