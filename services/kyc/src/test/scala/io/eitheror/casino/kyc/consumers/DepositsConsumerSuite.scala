package io.eitheror.casino.kyc.consumers

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.{BetPlaced, DepositConfirmed}
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.kyc.policy.Profiles
import io.eitheror.casino.kyc.record.{FlagReason, KycEvent, KycRecord}
import io.eitheror.casino.kyc.record.KycEvent.*

import java.time.Instant

/** R6 and FR-038: a confirmed deposit counted once per ordinal and currency; the large-deposit flag; other facts ignored. */
class DepositsConsumerSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-10-30T12:00:00Z")

  private def deposit(amount: Long, ordinal: Int, currency: String = "EUR", player: String = "p-1") =
    DepositConfirmed(player, Currency.unsafe(currency), s"t-$ordinal", MinorUnits.unsafe(amount), s"ref-$ordinal", ordinal,
      SignedMinorUnits(amount), MinorUnits.unsafe(0), at)

  private val mga = Profiles.Mga // EUR, a deposit threshold of 200000

  test("the command carries the player, the currency, the amount in minor units, the ordinal and the policy"):
    val c = DepositsConsumer.command(deposit(5000, 3), mga)
    assertEquals((c.playerId, c.currency, c.amount, c.ordinal, c.policy, c.at), ("p-1", "EUR", 5000L, 3, mga, at))

  test("a deposit for a player with no record starts one; a repeat of its ordinal changes nothing"):
    val kit   = EventSourcedTestKit.of(KycRecord, "p-new")
    val first = kit.call(KycRecord.countDeposit)(DepositsConsumer.command(deposit(5000, 1, player = "p-new"), mga))
    assertEquals(first.events.map(_.getClass.getSimpleName), Vector("DepositCounted"))
    assertEquals((kit.currentState.playerId, kit.currentState.lifetime(Some("EUR"))), ("p-new", 5000L))
    assertEquals(kit.call(KycRecord.countDeposit)(DepositsConsumer.command(deposit(5000, 1, player = "p-new"), mga)).events, Vector.empty[KycEvent])
    assertEquals(kit.currentState.lifetime(Some("EUR")), 5000L)

  test("each currency keeps its own total and ordinals"):
    val kit = EventSourcedTestKit.of(KycRecord, "p-1")
    kit.call(KycRecord.countDeposit)(DepositsConsumer.command(deposit(5000, 1), mga)): Unit
    kit.call(KycRecord.countDeposit)(DepositsConsumer.command(deposit(7000, 1, "GBP"), mga)): Unit
    assertEquals((kit.currentState.lifetime(Some("EUR")), kit.currentState.lifetime(Some("GBP"))), (5000L, 7000L))

  test("a deposit alone over the threshold in the KYC currency flags a player not verified, once; in another currency it does not"):
    val kit = EventSourcedTestKit.of(KycRecord, "p-1")
    val big = kit.call(KycRecord.countDeposit)(DepositsConsumer.command(deposit(250000, 1), mga))
    assert(big.events.exists { case Flagged(_, FlagReason.LargeDeposit, _) => true; case _ => false }, big.events.toString)
    val again = kit.call(KycRecord.countDeposit)(DepositsConsumer.command(deposit(250000, 2), mga))
    assertEquals(again.events.map(_.getClass.getSimpleName), Vector("DepositCounted"))
    val gbp = EventSourcedTestKit.of(KycRecord, "p-2")
    assertEquals(gbp.call(KycRecord.countDeposit)(DepositsConsumer.command(deposit(250000, 1, "GBP", "p-2"), mga)).events.size, 1)

  test("another family of money.transactions, and a fact that cannot be read, are ignored and call nothing"):
    val kit = ConsumerTestKit.of(DepositsConsumer)
    val bet = BetPlaced("p-1", Currency.unsafe("EUR"), "t-9", "round-1", "game-1", MinorUnits.unsafe(100), "real",
      SignedMinorUnits(0), MinorUnits.unsafe(0), at)
    assertEquals(kit.onMessage(Some(bet)).effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
