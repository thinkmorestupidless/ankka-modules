package io.eitheror.casino.pspgateway

import com.thinkmorestupidless.ankka.core.Serializer
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.providers.PayoutAnswer
import io.eitheror.casino.pspgateway.sessions.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** Data-model.md: every stored type round-trips, minor units as JSON numbers and instants as ISO-8601. */
class GatewayCodecsSuite extends munit.FunSuite with LogCapturing:
  import DepositCodecs.given
  import PayoutCodecs.given

  private val at = Instant.parse("2026-10-18T10:00:00Z")
  private val eur = Currency.unsafe("EUR")

  private def roundTrip[A](value: A)(using s: Serializer[A]): Unit =
    val bytes = s.toBytes(value)
    assertEquals(s.fromBytes(bytes), value, String(bytes, UTF_8))

  private val variance = Variance(Variance.Underpayment, Some("0.0012"), Some("0.0010"), Some("80.00"), Some("EUR"))
  private val outcomes = Vector(Outcome.Captured(), Outcome.Captured(Some(variance)), Outcome.Failed(Some("03"), "Less crypto arrived",
    Some(variance)), Outcome.Completed, Outcome.PayoutFailed(None, "Payout cancelled"), Outcome.Reversed)
  private val deliveries = Vector(Delivery.NotYet, Delivery.Delivered(at, 200), Delivery.Refused(at, 403, "invalid_token"),
    Delivery.Undelivered(at, 4, "503 OUTCOME_PENDING"), Delivery.Undelivered(at, 4, "no answer"))
  private val claim = DepositClaim(Provider.Morphosis, "USDT", "sb-1", None, "acme:p1", 1500, eur, "r", "w", Map("token" -> "t"))

  test("every deposit event round-trips") {
    val events: Vector[DepositEvent] =
      Vector(DepositEvent.Claimed(claim, at),
        DepositEvent.Initiated(Initiation(Some("x"), Destination.Crypto("addr", "0.001", "BTC", Some("2026-10-18T11:00:00Z")), Map("wpbId" -> "9"), at)),
        DepositEvent.Initiated(Initiation(None, Destination.Redirect("https://p"), Map.empty, at)),
        DepositEvent.InitiationFailed(Refusal(400, "player_error", Some("minimum deposit amount is 15.00")), at),
        DepositEvent.Redriven("timer", at)) ++
        outcomes.map(DepositEvent.Settled(_, "s", at)) ++ deliveries.map(DepositEvent.DeliveryRecorded(_, 1, at))
    events.foreach(roundTrip(_))
  }

  test("every payout event round-trips") {
    val answers = Vector(PayoutAnswer.Initiated(Some("r")), PayoutAnswer.Initiated(None), PayoutAnswer.Completed(Some("r")),
      PayoutAnswer.AlreadyInitiated, PayoutAnswer.Rejected("unauthorised"), PayoutAnswer.InDoubt("timed out"))
    val c = PayoutClaim("goldvibe", "sb-1", Provider.Cozeni, "t", "USDT", 5000, eur, "w", Map("salt" -> "s"))
    (Vector[PayoutEvent](PayoutEvent.Claimed(c, at), PayoutEvent.Redriven("op", at)) ++ answers.map(PayoutEvent.Answered(_, at)) ++
      outcomes.map(PayoutEvent.Settled(_, Some("r"), "s", at))).foreach(roundTrip(_))
  }

  test("a full state round-trips, the amount a JSON number and the instant ISO-8601") {
    val state = DepositState("pay-1", Some(claim), outcome = Some(Outcome.Captured(Some(variance))), delivery = Delivery.Delivered(at, 200),
      attempts = 2, updatedAt = Some(at))
    roundTrip(state)
    val json = String(summon[Serializer[DepositState]].toBytes(state), UTF_8)
    assert(json.contains("\"amount\":1500"), json)
    assert(json.contains("2026-10-18T10:00:00Z"), json)
  }

  test("the commands and replies round-trip") {
    roundTrip(Settle(Outcome.Failed(None, "declined"), "N", at))
    roundTrip(RecordDelivery(Delivery.Undelivered(at, 4, "503"), 4, at))
    roundTrip(Redriving(true, Delivery.NotYet))
    roundTrip(SessionStatus("resolved", Some("captured"), true, false, false, Some(at)))
    roundTrip(Receiver.Payouts("goldvibe", "wd-1"))(using GatewayCodecs.serializer[Receiver]("receiver"))
  }
