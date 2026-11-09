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

  override def beforeEach(context: BeforeEach): Unit = FoldProgress.forget()

  private def recorded(test: Boolean) = Recorded(c =>
    if c.method == "standing" then s"""{"registered":true,"test":$test}""".getBytes else Recorded.answer(c))
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
    val before = r.entityCalls.length
    assertEquals(kit.onMessage(Captured("f", "pay-1", "p-1", "psp-a", 1000, "EUR", at), subject = "g2/pay-1").effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(Ended("f", "pay-1", "p-1", "psp-a", "expired", at), subject = "g1/pay-1").effect, ConsumerEffect.Ignore)
    assertEquals(r.entityCalls.length, before)
  }

  test("a registration's flag is its own; the player's log is not asked") {
    val r   = recorded(test = false)
    val kit = ConsumerTestKit.of(ToDaysConsumers.Players(settings), client = r.client)
    kit.onMessage(PlayerLogEvent.Registered("f", "p-1", at, None, None, None, testAccount = true, at), subject = "g1/p-1"): Unit
    assert(!r.calls.exists(_.method == "standing"))
    assert(folds(r).head._2.delta.test)
  }

  test("the five to-days consumers are registered for the generation") {
    val ids = ReportingService.descriptors(Settings("goldvibe", generation = 4)).map(_.componentId.toString)
    assertEquals(ids.filter(_.startsWith("to-days")).toSet,
      Set("to-days-wallets-g4", "to-days-payments-g4", "to-days-withdrawals-g4", "to-days-grants-g4", "to-days-players-g4"))
  }

  private def told(r: Recorded): Vector[(String, Progressed)] =
    r.calls.filter(_.component == "fold-progress").map(c => c.entity -> c.as[Progressed](using FoldProgress.given_Serializer_Progressed))

  test("every event read is told to the consumer's progress with its fact time; another generation's with none") {
    val r     = recorded(test = false)
    val clock = MovableClock.at(at.plusSeconds(5))
    val kit   = ConsumerTestKit.of(ToDaysConsumers.Payments(settings, clock), client = r.client)
    kit.onMessage(Captured("f", "pay-1", "p-1", "psp-a", 1000, "EUR", at), subject = "g1/pay-1"): Unit
    assertEquals(told(r), Vector("g1/to-days-payments" -> Progressed("to-days-payments", Some(at), at.plusSeconds(5))))
    // inside the refresh and the same close day: not told again
    kit.onMessage(Ended("f", "pay-2", "p-1", "psp-a", "expired", at.plusSeconds(60)), subject = "g1/pay-2"): Unit
    assertEquals(told(r).size, 1)
    // past the next day's end plus the grace: told at once, with the latest fact time seen
    val next = Instant.parse("2026-11-08T22:15:00Z")
    kit.onMessage(Ended("f", "pay-3", "p-1", "psp-a", "expired", next), subject = "g1/pay-3"): Unit
    assertEquals(told(r).last._2, Progressed("to-days-payments", Some(next), at.plusSeconds(5)))
    // the refresh passed: another generation's event is told as activity, the latest kept
    clock.moveBy(scala.concurrent.duration.Duration.fromNanos(FoldProgress.Refresh.toNanos))
    kit.onMessage(Captured("f", "pay-9", "p-1", "psp-a", 1000, "EUR", at), subject = "g7/pay-9"): Unit
    assertEquals(told(r).last._2, Progressed("to-days-payments", Some(next), clock.instant()))
  }

  test("FoldProgress keeps the latest of each time it is told") {
    val k = com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit.of(FoldProgress, FoldProgress.id(1, "to-days-wallets"))
    k.call(FoldProgress.record)(Progressed("to-days-wallets", Some(at), at.plusSeconds(10))): Unit
    k.call(FoldProgress.record)(Progressed("to-days-wallets", Some(at.minusSeconds(60)), at.plusSeconds(5))): Unit
    assertEquals(k.currentState, ProgressState("to-days-wallets", Some(at), Some(at.plusSeconds(10))))
    k.call(FoldProgress.record)(Progressed("to-days-wallets", None, at.plusSeconds(20))): Unit
    assertEquals(k.currentState, ProgressState("to-days-wallets", Some(at), Some(at.plusSeconds(20))))
  }

  test("the markets' consumer is waited for only with the predictions module") {
    assertEquals(ToDaysConsumers.names(settings).size, 5)
    assertEquals(ToDaysConsumers.names(settings.copy(modules = Set("predictions"))).last, "to-days-markets")
  }

  // ── a test account's money folded before its registration (review, should fix 2) ───────────────

  test("a player PlayerLog has not registered: the fold is held in TestHolds and folded as a real player's") {
    val r = Recorded(c =>
      if c.method == "standing" then "{}".getBytes
      else if c.method == "hold" then "false".getBytes
      else Recorded.answer(c))
    val kit = ConsumerTestKit.of(ToDaysConsumers.Wallets(settings), client = r.client)
    kit.onMessage(moved("deposit_confirmed", 1000), subject = "g1/p-1/EUR", sequenceNumber = 3): Unit
    val held = r.calls.filter(_.method == "hold")
    assertEquals(held.map(_.entity), Vector("g1/p-1"))
    assertEquals(held.head.as[HoldFold](using TestHolds.given_Serializer_HoldFold),
      HoldFold("wallet-book/g1/p-1/EUR", 3, Map("2026-11-08" -> Delta("EUR", 12, Money(deposits = 1000, depositCount = 1)))))
    assert(folds(r).forall(!_._2.delta.test))
  }

  test("a test account's registration folds the folds held before it into the test sections alone") {
    val deposit = Delta("EUR", 12, Money(deposits = 1000, depositCount = 1))
    val heldOne = HeldFold("wallet-book/g1/p-1/EUR", 3, Map("2026-11-08" -> deposit))
    val r = Recorded(c =>
      if c.component == "test-holds" && c.method == "register" then
        TestHolds.given_Serializer_Vector.toBytes(Vector(heldOne))
      else Recorded.answer(c))
    val kit = ConsumerTestKit.of(ToDaysConsumers.Players(settings), client = r.client)
    kit.onMessage(PlayerLogEvent.Registered("f", "p-1", at, None, None, None, testAccount = true, at), subject = "g1/p-1"): Unit
    val register = r.calls.filter(_.method == "register")
    assertEquals(register.map(c => c.entity -> c.as[Register](using TestHolds.given_Serializer_Register)), Vector("g1/p-1" -> Register(true)))
    val again = folds(r).filter(_._2.testOnly)
    assertEquals(again.map(f => (f._1.entity, f._2.source, f._2.seq, f._2.delta)),
      Vector(("g1/2026-11-08", "test/wallet-book/g1/p-1/EUR", 3L, deposit.copy(test = true))))
  }

  test("TestHolds: holds before the registration, answers its flag after, and a held fold stays held on redelivery") {
    val k = com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit.of(TestHolds, TestHolds.id(1, "p-1"))
    val days = Map("2026-11-08" -> Delta("EUR", 12, Money(stakes = 500)))
    assertEquals(k.call(TestHolds.hold)(HoldFold("wallet-book/g1/p-1/EUR", 1, days)).replyValue, false)
    assertEquals(k.call(TestHolds.hold)(HoldFold("payment/g1/pay-1", 2, days)).replyValue, false)
    assertEquals(k.call(TestHolds.register)(Register(true)).replyValue.map(_.seq), Vector(1L, 2L))
    // After the registration: the flag, and a redelivered held fold is still held (its test-only part is folded already).
    assertEquals(k.call(TestHolds.hold)(HoldFold("wallet-book/g1/p-1/EUR", 5, days)).replyValue, true)
    assertEquals(k.call(TestHolds.hold)(HoldFold("wallet-book/g1/p-1/EUR", 1, days)).replyValue, false)
    // A registration redelivered answers the same held folds; a real player's none.
    assertEquals(k.call(TestHolds.register)(Register(true)).replyValue.size, 2)
    val real = com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit.of(TestHolds, TestHolds.id(1, "p-2"))
    real.call(TestHolds.hold)(HoldFold("wallet-book/g1/p-2/EUR", 1, days)): Unit
    assertEquals(real.call(TestHolds.register)(Register(false)).replyValue, Vector.empty)
  }
