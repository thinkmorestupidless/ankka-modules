package io.eitheror.casino.wallet.entity

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.wallet.entity.WalletEvent.CommandRefused

import java.time.Instant
import scala.compiletime.constValueTuple
import scala.deriving.Mirror

/**
 * SC-006, for every money command type: repeated under its key it answers the first outcome and
 * adds no event; the same key carrying a changed command is `KEY_REUSED`, naming the first, with
 * exactly one `CommandRefused` and nothing else. Both for an accepted first outcome and, where the
 * command can be refused, a refused one.
 *
 * The cases are keyed by the command's class name and checked against `MoneyCommand`'s subtypes,
 * so a command added later without a case here fails this suite.
 */
class EveryCommandIdempotentSuite extends munit.FunSuite with LogCapturing:

  private val t0 = Instant.parse("2026-10-09T10:00:00Z")
  private type Kit = EventSourcedTestKit[WalletEntity, WalletState, WalletEvent]

  private def funded(id: String): Kit =
    val kit = EventSourcedTestKit.of(WalletEntity, id)
    kit.call(WalletEntity.deposit)(Deposit("seed-dep", 10000, "psp", "t1", "psp", "card", TurnoverTerms(0, "gross_wagered"), t0))
    kit

  private def withAward(id: String): Kit =
    val kit = funded(id)
    kit.call(WalletEntity.award)(Award("seed-grant", "bonus-1", 2000, 1, 4000, 0, None, Rules(), None, t0))
    kit

  private def withBet(id: String): Kit =
    val kit = funded(id)
    kit.call(WalletEntity.placeBet)(PlaceBet("seed-bet", "r1", "slot", "s1", "prov", "slots", 1000, false, BetSwitches(), t0))
    kit

  private def withHold(id: String, confirmed: Boolean = false): Kit =
    val kit = funded(id)
    kit.call(WalletEntity.hold)(Hold("w-1", 1000, playerConfirmed = true, t0))
    if confirmed then kit.call(WalletEntity.confirm)(Confirm("seed-confirm", "w-1", t0)): Unit
    kit

  /** Spec 022: 3,000 of the 10,000 reserved under order o1. */
  private def withReservation(id: String): Kit =
    val kit = funded(id)
    kit.call(WalletEntity.reserve)(Reserve("o1", 3000, t0))
    kit

  /** A case: the wallet to start from, the command, a changed command under the same key, and how to send one. */
  private final case class Case(name: String, start: String => Kit, first: MoneyCommand, changed: MoneyCommand, send: (Kit, MoneyCommand) => Outcome,
      sendChanged: Option[(Kit, MoneyCommand) => Outcome] = None)

  private def sender[C <: MoneyCommand](handle: com.thinkmorestupidless.ankka.sdk.CommandHandle[WalletEntity, C, Outcome]): (Kit, MoneyCommand) => Outcome =
    (kit, c) => kit.call(handle)(c.asInstanceOf[C]).replyValue

  private val accepted: Vector[Case] = Vector(
    Case("OpenMigrated", id => EventSourcedTestKit.of(WalletEntity, id), OpenMigrated("m-1", 500, 0, Vector.empty, 0, 0, 0, t0),
      OpenMigrated("m-1", 600, 0, Vector.empty, 0, 0, 0, t0), sender(WalletEntity.openMigrated)),
    Case("Deposit", funded, Deposit("d-1", 100, "psp", "t1", "psp", "card", TurnoverTerms.Default, t0),
      Deposit("d-1", 200, "psp", "t1", "psp", "card", TurnoverTerms.Default, t0), sender(WalletEntity.deposit)),
    Case("PlaceBet", funded, PlaceBet("b-1", "r9", "slot", "s1", "prov", "slots", 500, false, BetSwitches(), t0),
      PlaceBet("b-1", "r9", "slot", "s1", "prov", "slots", 700, false, BetSwitches(), t0), sender(WalletEntity.placeBet)),
    Case("CreditWin", withBet, CreditWin("w-9", "r1", 2500, t0), CreditWin("w-9", "r1", 2600, t0), sender(WalletEntity.creditWin)),
    Case("RollbackBet", withBet, RollbackBet("rb-1", "r1", None, t0), RollbackBet("rb-1", "r2", None, t0), sender(WalletEntity.rollbackBet)),
    Case("SettleLoss", withBet, SettleLoss("l-1", "r1", t0), SettleLoss("l-1", "r2", t0), sender(WalletEntity.settleLoss)),
    Case("ProviderCredit", funded, ProviderCredit("pc-1", 300, "fs-1", "free spins", "st8", t0),
      ProviderCredit("pc-1", 400, "fs-1", "free spins", "st8", t0), sender(WalletEntity.providerCredit)),
    Case("Award", funded, Award("g-1", "bonus", 1000, 1, 2000, 0, None, Rules(), None, t0),
      Award("g-1", "bonus", 1500, 1, 2000, 0, None, Rules(), None, t0), sender(WalletEntity.award)),
    Case("ForfeitAward", withAward, ForfeitAward("f-1", None, t0), ForfeitAward("f-1", Some("seed-grant"), t0), sender(WalletEntity.forfeit)),
    Case("CancelAward", withAward, CancelAward("c-1", None, t0), CancelAward("c-1", Some("seed-grant"), t0), sender(WalletEntity.cancel)),
    // A close carries only its key: the changed command under it is another command type (FR-002: "in type").
    Case("ClosePendingCap", withAward, ClosePendingCap("cp-1", t0), ForfeitAward("cp-1", None, t0), sender(WalletEntity.closePendingCap),
      Some(sender(WalletEntity.forfeit))),
    Case("SwitchPrimary", withAward, SwitchPrimary("s-1", "seed-grant", t0), SwitchPrimary("s-1", "other-grant", t0), sender(WalletEntity.switchPrimary)),
    Case("Hold", funded, Hold("h-1", 1000, true, t0), Hold("h-1", 2000, true, t0), sender(WalletEntity.hold)),
    Case("Release", id => withHold(id), Release("rl-1", "w-1", "cancelled", t0), Release("rl-1", "w-1", "failed", t0), sender(WalletEntity.release)),
    Case("Confirm", id => withHold(id), Confirm("cf-1", "w-1", t0), Confirm("cf-1", "w-2", t0), sender(WalletEntity.confirm)),
    Case("Reverse", id => withHold(id, confirmed = true), Reverse("rv-1", "w-1", t0), Reverse("rv-1", "w-2", t0), sender(WalletEntity.reverse)),
    Case("Adjust", funded, Adjust("a-1", "real", 100, "reason", "goodwill", "op", t0), Adjust("a-1", "real", 200, "reason", "goodwill", "op", t0),
      sender(WalletEntity.adjust)),
    // spec 022
    Case("Reserve", funded, Reserve("o-9", 1000, t0), Reserve("o-9", 1200, t0), sender(WalletEntity.reserve)),
    Case("ReleaseReservation", withReservation, ReleaseReservation("o1/1", "o1", 500, t0), ReleaseReservation("o1/1", "o1", 600, t0),
      sender(WalletEntity.releaseReservation)),
    Case("SettleDebit", withReservation, SettleDebit("t-1", "o1", 1000, 0, "bob", t0), SettleDebit("t-1", "o1", 1100, 0, "bob", t0),
      sender(WalletEntity.settleDebit)),
    Case("SettleCredit", funded, SettleCredit("t-1", 1000, "ada", t0), SettleCredit("t-1", 1100, "ada", t0), sender(WalletEntity.settleCredit)),
    Case("DebitToAccount", funded, DebitToAccount("sp-1", "pool", "m1", Purpose.Split, "sp-1", 500, t0),
      DebitToAccount("sp-1", "pool", "m1", Purpose.Split, "sp-1", 600, t0), sender(WalletEntity.debitToAccount)),
    Case("CreditFromAccount", funded, CreditFromAccount("mg-1", "pool", "m1", Purpose.Merge, "mg-1", 500, t0),
      CreditFromAccount("mg-1", "pool", "m1", Purpose.Merge, "mg-1", 600, t0), sender(WalletEntity.creditFromAccount)),
    Case("ChargeReservationToAccount", withReservation,
      ChargeReservationToAccount("t-1/ada", "o1", "pool", "m1", Purpose.Split, "t-1/ada", 1000, 0, t0),
      ChargeReservationToAccount("t-1/ada", "o1", "pool", "m1", Purpose.Split, "t-1/ada", 1000, 100, t0),
      sender(WalletEntity.chargeReservationToAccount))
  )

  test("every money command type has a case") {
    val m      = summon[Mirror.SumOf[MoneyCommand]]
    val all    = constValueTuple[m.MirroredElemLabels].toList.map(_.toString).toSet
    val tested = accepted.map(_.name).toSet
    assertEquals(all -- tested, Set.empty[String], "money commands with no idempotency case")
  }

  for c <- accepted do
    test(s"${c.name}: a repeat answers the first outcome and persists nothing; a changed command under its key is KEY_REUSED") {
      val kit    = c.start(s"idem-${c.name}/EUR")
      val first  = c.send(kit, c.first)
      val before = kit.allEvents.size
      assertEquals(c.send(kit, c.first), first)
      assertEquals(kit.allEvents.size, before, "a repeat persisted an event")
      val state = kit.currentState
      val reused = c.sendChanged.getOrElse(c.send)(kit, c.changed)
      assertEquals(reused.code, Some("KEY_REUSED"))
      assert(reused.message.exists(_.startsWith(s"Key ${c.first.key} was used by a ")), reused.message.toString)
      val added = kit.allEvents.drop(before)
      assertEquals(added.size, 1)
      assert(added.head.isInstanceOf[CommandRefused], added.head.toString)
      assertEquals((kit.currentState.realBalance, kit.currentState.restrictedBalance), (state.realBalance, state.restrictedBalance))
      assertEquals(kit.currentState.seen.outcomeOf(io.eitheror.casino.contract.money.Key.unsafe(c.first.key)), Some(first))
    }

  test("a refused command is refused the same way when repeated, and persists nothing the second time") {
    val kit     = funded("idem-refused/EUR")
    val bet     = PlaceBet("big", "r1", "slot", "s1", "prov", "slots", 999999, false, BetSwitches(), t0)
    val refused = kit.call(WalletEntity.placeBet)(bet).replyValue
    assertEquals(refused.code, Some("INSUFFICIENT_FUNDS"))
    val before = kit.allEvents.size
    assertEquals(kit.call(WalletEntity.placeBet)(bet).replyValue, refused)
    assertEquals(kit.allEvents.size, before)
  }

  test("spec 022: a refused reservation, release, settle-debit, move and charge are refused the same way when repeated, persisting nothing") {
    val kit = withReservation("idem-refused-022/EUR")
    val refusedOnce: Vector[(String, () => Outcome)] = Vector(
      "INSUFFICIENT_FUNDS"          -> (() => kit.call(WalletEntity.reserve)(Reserve("o-big", 8000, t0)).replyValue),
      "RESERVATION_NOT_FOUND"       -> (() => kit.call(WalletEntity.releaseReservation)(ReleaseReservation("o9/1", "o9", 100, t0)).replyValue),
      "RELEASE_EXCEEDS_RESERVATION" -> (() => kit.call(WalletEntity.releaseReservation)(ReleaseReservation("o1/9", "o1", 3001, t0)).replyValue),
      "RESERVATION_SHORT"           -> (() => kit.call(WalletEntity.settleDebit)(SettleDebit("t-9", "o1", 2900, 200, "bob", t0)).replyValue),
      "INSUFFICIENT_FUNDS"          -> (() => kit.call(WalletEntity.debitToAccount)(DebitToAccount("sp-9", "pool", "m1", Purpose.Split, "sp-9", 7001, t0)).replyValue),
      "RESERVATION_NOT_FOUND"       -> (() => kit.call(WalletEntity.chargeReservationToAccount)(
        ChargeReservationToAccount("t-8/ada", "o9", "pool", "m1", Purpose.Split, "t-8/ada", 100, 0, t0)).replyValue),
      "RESERVATION_SHORT"           -> (() => kit.call(WalletEntity.chargeReservationToAccount)(
        ChargeReservationToAccount("t-9/ada", "o1", "pool", "m1", Purpose.Split, "t-9/ada", 2900, 200, t0)).replyValue)
    )
    for (code, send) <- refusedOnce do
      val first = send()
      assertEquals(first.code, Some(code), first.toString)
      val before = kit.allEvents.size
      assertEquals(send(), first)
      assertEquals(kit.allEvents.size, before, s"$code: a repeat persisted an event")
    assertEquals((kit.currentState.realBalance, kit.currentState.reserved), (10000L, 3000L))
  }

  test("a key reused twice records each reuse under its own synthetic key and leaves the first outcome") {
    val kit = funded("idem-twice/EUR")
    val first = kit.call(WalletEntity.placeBet)(PlaceBet("k", "r1", "g", "s", "p", "c", 100, false, BetSwitches(), t0)).replyValue
    kit.call(WalletEntity.placeBet)(PlaceBet("k", "r1", "g", "s", "p", "c", 200, false, BetSwitches(), t0))
    kit.call(WalletEntity.placeBet)(PlaceBet("k", "r1", "g", "s", "p", "c", 300, false, BetSwitches(), t0))
    assertEquals(kit.currentState.seen.outcomeOf(io.eitheror.casino.contract.money.Key.unsafe("k")), Some(first))
    assertEquals(kit.allEvents.collect { case e: CommandRefused => e.code }, Vector("KEY_REUSED", "KEY_REUSED"))
  }
