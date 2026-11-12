package io.eitheror.casino.publicapi.records

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.publicapi.it.FactSet.*

import java.time.Instant

/** Each fact family of contracts/facts.md's *Read* table changes its record as data-model.md says; a repeat changes nothing. */
class IntakeSuite extends munit.FunSuite:
  private val t  = Instant.parse("2026-11-01T10:00:00Z")
  private val pl = "11111111-1111-1111-1111-111111111111"

  test("a player: registration, email, profile, statuses and KYC, by newValue") {
    val folded = Vector[f.CasinoPlayersFact](
      registered(pl, t),
      f.PlayerEmailChanged(pl, p("new@example.com"), t.plusSeconds(1)),
      f.PlayerProfileUpdated(pl, Vector("city"), Some(p("Bia")), None, None, None, None, Some(p("Rio")), None, Some("BR"), t.plusSeconds(2),
        Some("es"), None),
      f.PlayerSuspended(pl, "active", "suspended", "op", None, None, t.plusSeconds(3)),
      f.PlayerKycRejected(pl, "verified", "unverified", "op", None, None, t.plusSeconds(4))
    ).foldLeft(PlayerState())(PlayerFold(_, _))
    assertEquals((folded.status, folded.kycStatus, folded.language, folded.registeredAt), ("suspended", "unverified", Some("es"), Some(t)))
    assertEquals((Records.reveal(folded.email), Records.reveal(folded.firstName), Records.reveal(folded.city)),
      (Some("new@example.com"), Some("Bia"), Some("Rio")))
    assertEquals((folded.lastName, folded.stateProv), (None, None), "the profile fact replaces the profile")
  }

  test("a fact applied twice changes the record once") {
    val kit  = KeyValueEntityTestKit.of(PlayerRecord, pl)
    val fact = PlayerFact("id-1", registered(pl, t))
    assertEquals(kit.call(PlayerRecord.record)(fact).replyValue, true)
    assertEquals(kit.call(PlayerRecord.record)(fact).replyValue, false)
  }

  test("a round: opened, then settled; cancelled has no settledAt; the start falls back to openedAt, then the settlement") {
    val k = "st8/r-1"
    val open = RoundFold(RoundState(), opened(pl, k, "s-1", t))
    assertEquals((open.status, open.startedAt, open.id), ("open", Some(t), Records.roundId(k)))
    val done = RoundFold(open, settled(pl, k, "s-1", t.plusSeconds(5), 100, 250))
    assertEquals((done.status, done.settledAt, done.startedAt, done.stakeCents), ("settled", Some(t.plusSeconds(5)), Some(t), 100L))
    val cancelled = RoundFold(done, settled(pl, k, "s-1", t.plusSeconds(9), 100, 0, "cancelled"))
    assertEquals((cancelled.status, cancelled.settledAt), ("cancelled", None))
    val late = RoundFold(RoundState(), settled(pl, k, "", t.plusSeconds(5), 1, 0).copy(openedAt = Some(t.plusSeconds(1))))
    assertEquals((late.startedAt, late.sessionId), (Some(t.plusSeconds(1)), None))
    assertEquals(RoundFold(RoundState(), settled(pl, k, "", t.plusSeconds(5), 1, 0)).startedAt, Some(t.plusSeconds(5)))
  }

  test("a bet's roundId is its round's id; its later facts find it by the wallet round key") {
    val change = MoneyFold(bet(pl, "b-1", "w-1", "st8/r-1", "s-1", 100, t))
    assertEquals(change.bet.flatMap(_.roundId), Some(Records.roundId("st8/r-1")))
    assertEquals(change.roundKey, Some(MoneyFold.roundKey(pl, "w-1")))
    assertEquals(MoneyFold(win(pl, "x", "w-1", "st8/r-1", "s-1", 5, t)).betsBecome, Some("settled"))
    assertEquals(MoneyFold(rolledBack(pl, "x", "w-1", "st8/r-1", 5, t)).betsBecome, Some("voided"))
    assertEquals(MoneyFold(loss(pl, "x", "w-1", "st8/r-1", t)).betsBecome, Some("loss"))
  }

  test("each money family's type, wallet type and status; secondary rows' ids; excluded families make no row") {
    def one(m: f.MoneyTransactionsMessage) = MoneyFold(m).rows.map(r => (r.typeWord, r.walletType, r.status))
    assertEquals(one(deposit(pl, "d", 1, t)), Vector(("deposit", Some("MAIN"), "completed")))
    assertEquals(one(loss(pl, "l", "w", "r", t)), Vector(("loss", Some("MAIN"), "settled")))
    val forfeit = f.RestrictedFundsForfeited(pl, eur, "tx", "aw", m(5), "cancel", m(0), s(0), m(0), t)
    assertEquals(MoneyFold(forfeit).rows.map(r => (r.id, r.typeWord, r.status)), Vector((Records.uuid3("tx/restricted_funds_forfeited/aw"), "bonus_cancel", "completed")))
    val h = MoneyFold(hold(pl, "h", "po", 100, t, voided = 7)).rows
    assertEquals(h.map(r => (r.typeWord, r.amountCents, r.status)), Vector(("withdrawal", 100L, "pending"), ("bonus_cap_void", 7L, "completed")))
    assertEquals(MoneyFold(f.RestrictedFundsClosed(pl, eur, "tx", "aw", "done", s(0), m(0), t)).rows, Vector.empty)
  }

  test("withdrawal_completed completes its WITHDRAWAL row, before or after the hold") {
    val kit = KeyValueEntityTestKit.of(WithdrawalsByRequest, "po")
    assertEquals(kit.call(WithdrawalsByRequest.complete)(Complete("po")).replyValue.completed, true)
    assertEquals(kit.call(WithdrawalsByRequest.add)(AddHold("h")).replyValue, PayoutState(Vector("h"), completed = true))
  }

  test("a bonus award: granted ACTIVE, then the later of the reward's and the wallet's status") {
    val granted0 = BonusFold(BonusAwardState(), granted(pl, "bn", 1000, t, Some(t.plusSeconds(60))))
    assertEquals((granted0.status, granted0.valueCents, granted0.expiresAt), (Some("ACTIVE"), Some(1000L), Some(t.plusSeconds(60))))
    val kit = KeyValueEntityTestKit.of(BonusAwardRecord, "bn")
    kit.call(BonusAwardRecord.reward)(BonusFact("1", granted(pl, "bn", 1000, t))): Unit
    kit.call(BonusAwardRecord.money)(BonusMoney("2", pl, Some(30000), Some(500), Some("CLOSED"), t.plusSeconds(5))): Unit
    val s1 = kit.call(BonusAwardRecord.get).replyValue
    assertEquals((s1.status, s1.wageringRequirementCents, s1.wageredCents), (Some("CLOSED"), Some(30000L), Some(500L)))
  }
