package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.engagement.consumers.Sends
import io.eitheror.casino.engagement.profile.{PlayersFact, Profile}
import io.eitheror.casino.engagement.send.{Outcome, Send, SendState}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** Each consumer of data-model.md's table, through the kit's broker: the fact read, the send claimed under its key. */
class ConsumersSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit
  private val eur      = Currency.unsafe("EUR")

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("DepositConfirmed" -> "7"))

  private def player(): String =
    val id = s"c-${UUID.randomUUID().toString.take(8)}"
    val at = Instant.now().minusSeconds(3600)
    kit.components.forKeyValueEntity(EntityId(id)).call(Profile.players).invoke(PlayersFact(f.PlayerRegistered(id, at, None, None, None,
      Some("ZA"), Some("EUR"), None, at, email = Some(Personal(s"$id@example.com"))))): Unit
    kit.components.forKeyValueEntity(EntityId(id)).call(Profile.players)
      .invoke(PlayersFact(f.PlayerConsentChanged(id, "email", true, "registration", at))): Unit
    id

  private def settled(key: String): SendState = kit.eventually(s"send $key") {
    Option(kit.components.forWorkflow(EntityId(key)).call(Send.outcome).invoke()).filter(s => s.request.isDefined && s.ended)
  }

  test("money.transactions: a deposit's send, and a bet's identify; the profile's balance moved") {
    val p   = player()
    val now = Instant.now()
    val d   = f.DepositConfirmed(p, eur, "tx-1", MinorUnits.unsafe(2500L), "ref-1", 1, SignedMinorUnits(2500L), MinorUnits.unsafe(0L), now)
    kit.publish(f.MoneyTransactions, d.key, d)
    assertEquals(settled(s"deposit_confirmed/${Sends.factId(f.MoneyTransactions, d)}").outcome, Outcome.Accepted)
    assertEquals(settled(s"first_deposit_confirmed/${Sends.factId(f.MoneyTransactions, d)}").outcome, Outcome.Accepted)
    val bet = f.BetPlaced(p, eur, "tx-2", "r-1", "g-1", MinorUnits.unsafe(100L), "real", SignedMinorUnits(2400L), MinorUnits.unsafe(0L),
      now.plusSeconds(1))
    kit.publish(f.MoneyTransactions, bet.key, bet)
    assertEquals(settled(s"identify/${Sends.factId(f.MoneyTransactions, bet)}").calls.map(_.kind), Vector("identify"))
    assertEquals(kit.components.forKeyValueEntity(EntityId(p)).call(Profile.get).invoke().realCents, Some(2400L))
  }

  test("casino.players: a tier change is an identify only") {
    val p = player()
    val t = f.VipTierChanged(p, None, "gold", "op", Instant.now())
    kit.publish(f.CasinoPlayers, t.key, t)
    assertEquals(settled(s"identify/${Sends.factId(f.CasinoPlayers, t)}").outcome, Outcome.Accepted)
  }

  test("payments.payments: a failure after an initiation is payment_failed/<payment id>") {
    val p   = player()
    val pay = s"pay-${UUID.randomUUID().toString.take(8)}"
    val i   = f.PaymentInitiated(pay, p, "psp", "CARD_VISA", MinorUnits.unsafe(5000L), eur, Instant.now())
    kit.publish(f.PaymentsPayments, i.key, i)
    val failed = f.PaymentFailed(pay, p, "psp", "05", "declined", Instant.now().plusSeconds(1))
    kit.publish(f.PaymentsPayments, failed.key, failed)
    val s = settled(s"payment_failed/$pay")
    assertEquals(s.request.map(_.data.map(_.name).head), Some("amount"))
  }

  test("casino.sessions, casino.withdrawals and casino.rewards: their events") {
    val p = player()
    val s = f.GameSessionStarted(p, "s-1", "g-1", "ps-1", Instant.now())
    kit.publish(f.CasinoSessions, s.key, s)
    val w = f.WithdrawalFailed(p, "po-1", MinorUnits.unsafe(100L), eur, "insufficient", Instant.now())
    kit.publish(f.CasinoWithdrawals, w.key, w)
    val b = f.BonusGranted(p, "b-1", "Welcome", "offer-1", "deposit_match", MinorUnits.unsafe(100L), eur, None, Instant.now())
    kit.publish(f.CasinoRewards, b.key, b)
    assertEquals(settled(s"game_session_started/${Sends.factId(f.CasinoSessions, s)}").outcome, Outcome.Accepted)
    assertEquals(settled(s"withdrawal_failed/${Sends.factId(f.CasinoWithdrawals, w)}").outcome, Outcome.Accepted)
    assertEquals(settled(s"bonus_granted/${Sends.factId(f.CasinoRewards, b)}").outcome, Outcome.Accepted)
  }
