package io.eitheror.casino.players.facts

import com.thinkmorestupidless.ankka.sdk.DeferredCall
import com.thinkmorestupidless.ankka.sdk.TimerScheduler
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing, TestTransport}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits}
import io.eitheror.casino.players.{PlayersRuntime, Settings}
import io.eitheror.casino.players.inbox.{MessageAnswer, MessageAt, MessageEntity, MessageState}
import io.eitheror.casino.players.player.*
import io.eitheror.casino.players.sessions.SessionLimits

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/** What the service does with other services' facts (T018). */
class ConsumersSuite extends munit.FunSuite with LogCapturing:

  private val at  = Instant.parse("2026-10-25T10:00:00Z")
  private val eur = Currency.unsafe("EUR")

  private final class Timers extends TimerScheduler:
    val single = mutable.ListBuffer.empty[(String, FiniteDuration)]
    def createSingleTimer(name: String, delay: FiniteDuration, call: DeferredCall): Unit = single += name -> delay
    def createRecurringTimer(name: String, delay: FiniteDuration, period: FiniteDuration, call: DeferredCall): Unit = ()
    def delete(name: String): Unit                                                                                 = ()
    def exists(name: String): Boolean                                                                              = single.exists(_._1 == name)

  test("SESSION_MINUTES sets the limit and reschedules each open game session by name; a session over it ends at once") {
    val timers = Timers()
    PlayersRuntime.wire(Settings(brand = "goldvibe"), () => at)
    PlayersRuntime.timers = () => timers
    val asked     = mutable.ListBuffer.empty[SetSessionLimit]
    val transport = TestTransport().stub(PlayerEntity.setSessionLimit) { (c: SetSessionLimit) =>
      asked += c
      Remainders(Map("gs-1" -> 20, "gs-2" -> -5), limited = true)
    }
    val kit = ConsumerTestKit.of(RgLimitsConsumer, transport.client)
    kit.onMessage(Some(RgLimitApplied("p-1", "SESSION_MINUTES", None, Some(60), "player", at)): Option[CasinoPlayersFact]): Unit
    assertEquals(asked.toVector, Vector(SetSessionLimit(Some(60), at)))
    assertEquals(timers.single.toMap, Map(SessionLimits.name("gs-1") -> FiniteDuration(20, "minutes"), SessionLimits.name("gs-2") -> FiniteDuration(0, "minutes")))
  }

  test("any other rg_limit_applied kind, and a fact the codec cannot read, do nothing") {
    val kit = ConsumerTestKit.of(RgLimitsConsumer)
    assertEquals(kit.onMessage(Some(RgLimitApplied("p-1", "DEPOSIT_DAILY", Some("day"), Some(100), "player", at)): Option[CasinoPlayersFact]).messages,
      Vector.empty)
    assertEquals(kit.onMessage(Option.empty[CasinoPlayersFact]).messages, Vector.empty)
  }

  test("player_kyc_verified locks the date of birth; player_kyc_rejected withdraws it") {
    val calls     = mutable.ListBuffer.empty[String]
    val transport = TestTransport()
      .stub(PlayerEntity.kycVerified)((_: At) => { calls += "verified"; Done.changed })
      .stub(PlayerEntity.kycWithdrawn)((_: At) => { calls += "withdrawn"; Done.changed })
    val kit = ConsumerTestKit.of(KycFactsConsumer, transport.client)
    kit.onMessage(Some(PlayerKycVerified("p-1", "pending", "verified", "operator", None, None, at)): Option[CasinoPlayersFact]): Unit
    kit.onMessage(Some(PlayerKycRejected("p-1", "verified", "rejected", "operator", None, None, at)): Option[CasinoPlayersFact]): Unit
    kit.onMessage(Some(PlayerKycPending("p-1", "", "pending", at)): Option[CasinoPlayersFact]): Unit
    assertEquals(calls.toVector, Vector("verified", "withdrawn"))
  }

  test("a requested withdrawal opens; completed, cancelled or failed settles; approved does nothing") {
    val calls     = mutable.ListBuffer.empty[(String, String)]
    val transport = TestTransport()
      .stub(PlayerEntity.withdrawalOpened)((w: Withdrawal) => { calls += "opened" -> w.withdrawalId; Done.changed })
      .stub(PlayerEntity.withdrawalSettled)((w: Withdrawal) => { calls += "settled" -> w.withdrawalId; Done.changed })
    val kit = ConsumerTestKit.of(WithdrawalsConsumer, transport.client)
    val m   = MinorUnits.unsafe(100)
    Seq[CasinoWithdrawalsFact](WithdrawalRequested("p-1", "w-1", m, eur, "t", at), WithdrawalApproved("p-1", "w-1", m, eur, "op", at),
      WithdrawalCompleted("p-1", "w-1", m, eur, at), WithdrawalCancelled("p-1", "w-2", m, eur, "player", at), WithdrawalFailed("p-1", "w-3", m, eur, "x", at))
      .foreach(f => kit.onMessage(Some(f)): Unit)
    assertEquals(calls.toVector, Vector("opened" -> "w-1", "settled" -> "w-1", "settled" -> "w-2", "settled" -> "w-3"))
  }

  test("push_delivered marks the message delivered; bonus_code_entered clears the pending code") {
    val delivered = mutable.ListBuffer.empty[MessageAt]
    val push      = TestTransport().stub(MessageEntity.delivered)((m: MessageAt) => { delivered += m; MessageAnswer(true, MessageState()) })
    ConsumerTestKit.of(PushDeliveredConsumer, push.client).onMessage(Some(PushDelivered("p-1", "m-1", 1, at)): Option[CasinoPushFact]): Unit
    ConsumerTestKit.of(PushDeliveredConsumer, push.client).onMessage(Some(PushDelivered("p-1", "m-2", 0, at)): Option[CasinoPushFact]): Unit
    assertEquals(delivered.toVector, Vector(MessageAt(at)))
    val cleared = mutable.ListBuffer.empty[At]
    val rewards = TestTransport().stub(PlayerEntity.clearPendingBonusCode)((a: At) => { cleared += a; Done.changed })
    ConsumerTestKit.of(RewardsConsumer, rewards.client).onMessage(Some(BonusCodeEntered("p-1", "WELCOME", at)): Option[CasinoRewardsFact]): Unit
    assertEquals(cleared.toVector, Vector(At(at)))
  }
