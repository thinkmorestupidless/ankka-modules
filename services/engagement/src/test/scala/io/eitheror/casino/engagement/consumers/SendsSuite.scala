package io.eitheror.casino.engagement.consumers

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.engagement.profile.{Applied, RecentPayment}
import io.eitheror.casino.engagement.send.Kind

import java.time.Instant

/** research R4's keys and contracts/facts.md's families, as each consumer claims them. */
class SendsSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-11-03T10:00:00Z")
  private val eur = Currency.unsafe("EUR")
  private val id  = "fact-1"

  private def deposit = f.DepositConfirmed("p-1", eur, "tx-1", MinorUnits.unsafe(2500L), "ref-1", 1, SignedMinorUnits(2500L),
    MinorUnits.unsafe(0L), at)

  private def keys(fact: f.Fact, applied: Applied = Applied(changed = true)) = Sends.of("p-1", id, fact, applied).map(_.key)

  test("a deposit is deposit_confirmed/<fact id>, with first_deposit_confirmed when the lifetime count becomes one") {
    assertEquals(keys(deposit, Applied(true, None, 3, 4)), Vector("deposit_confirmed/fact-1"))
    assertEquals(keys(deposit, Applied(true, None, 0, 1)), Vector("deposit_confirmed/fact-1", "first_deposit_confirmed/fact-1"))
    val r = Sends.of("p-1", id, deposit, Applied(true, None, 0, 1)).head
    assertEquals((r.trigger, r.sendClass, r.transactional, r.calls), ("deposit_confirmed", "marketing", Some("DepositConfirmed"),
      Vector(Kind.Identify, Kind.Track, Kind.Transactional)))
    assertEquals(r.occurredAt, at)
  }

  test("a movement with no event is one identify keyed identify/<fact id>, and none when the profile did not change") {
    val bet = f.BetPlaced("p-1", eur, "tx-2", "round-1", "game-1", MinorUnits.unsafe(100L), "real", SignedMinorUnits(2400L),
      MinorUnits.unsafe(0L), at)
    val s = Sends.of("p-1", id, bet, Applied(changed = true))
    assertEquals(s.map(r => (r.key, r.trigger, r.calls)), Vector(("identify/fact-1", "identify", Vector(Kind.Identify))))
    assertEquals(keys(bet, Applied(changed = false)), Vector.empty)
  }

  test("a payment failure or expiry is payment_failed/<payment id>, carrying the initiated payment's data") {
    val initiated = RecentPayment("pay-1", 5000L, "EUR", "psp", "CARD_VISA")
    val failed    = f.PaymentFailed("pay-1", "p-1", "psp", "05", "declined", at)
    val s = Sends.of("p-1", id, failed, Applied(changed = false, payment = Some(initiated)))
    assertEquals(s.map(_.key), Vector("payment_failed/pay-1"))
    assertEquals(s.head.data.map(_.name).take(3), Vector("amount", "currency", "transaction_id"))
    assertEquals(keys(f.PaymentExpired("pay-1", "p-1", "psp", at), Applied(false)), Vector("payment_failed/pay-1"))
    assertEquals(keys(f.PaymentInitiated("pay-1", "p-1", "psp", "CARD_VISA", MinorUnits.unsafe(5000L), eur, at), Applied(false)), Vector.empty)
  }

  test("a session, a withdrawal and a reward fact are their events, keyed by the fact") {
    assertEquals(keys(f.GameSessionStarted("p-1", "s-1", "g-1", "ps-1", at), Applied(false)), Vector("game_session_started/fact-1"))
    assertEquals(keys(f.WithdrawalFailed("p-1", "po-1", MinorUnits.unsafe(100L), eur, "insufficient", at), Applied(false)),
      Vector("withdrawal_failed/fact-1"))
    assertEquals(keys(f.BonusGranted("p-1", "b-1", "Welcome", "offer-1", "deposit_match", MinorUnits.unsafe(100L), eur, None, at),
      Applied(false)), Vector("bonus_granted/fact-1"))
  }

  test("a vip tier change is an identify only; a registration with a tag is two events") {
    assertEquals(keys(f.VipTierChanged("p-1", Some("bronze"), "gold", "op", at)), Vector("identify/fact-1"))
    val reg = f.PlayerRegistered("p-1", at, Some("aff-7"), None, None, Some("ZA"), Some("EUR"), None, at)
    assertEquals(keys(reg), Vector("player_registered/fact-1", "affiliate_attributed/fact-1"))
  }

  test("a fact id is the same for the same fact and differs for another") {
    val a = Sends.factId(f.MoneyTransactions, deposit)
    assertEquals(Sends.factId(f.MoneyTransactions, deposit), a)
    assertNotEquals(Sends.factId(f.MoneyTransactions, deposit.copy(occurredAt = at.plusSeconds(1))), a)
    assertEquals(a.length, 32)
  }
