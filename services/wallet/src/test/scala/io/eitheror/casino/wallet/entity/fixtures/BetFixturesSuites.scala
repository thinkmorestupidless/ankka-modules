package io.eitheror.casino.wallet.entity.fixtures

import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.entity.fixtures.FixtureRunner.*

/**
 * `place-bet.test.tl`: the bet path (FR-005 to FR-010, FR-014). The brand's switches arrive as the
 * fixture's parameters, as the bet endpoint passes them from `BrandConfigEntity`. A refusal's code
 * is asserted; the unmigrated guard's messages in these fixtures are an older TL text than the Go
 * guest's, which the wallet answers with.
 */
class PlaceBetFixturesSuite extends FixtureSuite("place-bet"):

  protected def check(c: Case): Unit =
    given Case = c
    val kit    = FixtureRunner.kit(c, opening(c, c.recordTwins.map(record)))
    val before = kit.currentState
    val p      = c.params
    val command = PlaceBet(
      key = "fixture-bet", roundId = text(p, "round_id").get, gameId = text(p, "game_id").getOrElse(""),
      sessionId = text(p, "session_id").getOrElse(""), provider = text(p, "provider").getOrElse(""),
      category = text(p, "game_category").getOrElse(""), amount = long(p, "amount").get,
      allowNegative = p.path("allow_negative").asBoolean(false),
      switches = BetSwitches(long(p, "game_contribution_rate").getOrElse(100L), p.path("bonus_wagering_counts").asBoolean(true),
        p.path("partial_splits_enabled").asBoolean(true)),
      at = c.at
    )
    val result = kit.call(WalletEntity.placeBet)(command)
    val o      = result.replyValue
    c.error match
      case Some(error) => assertRefused(error, before, kit, result.events, o)
      case None =>
        accepted(o)
        val placed = result.events.collectFirst { case e: WalletEvent.BetPlaced => e }.getOrElse(fail("no bet placed"))
        assertWalletAndRecords(kit.currentState)
        assertResultBalances(o)
        c.transactions("bet").foreach { t =>
          sameText("bet wallet_type", text(t, "wallet_type"), o.walletType.getOrElse(""))
          same("bet balance_before", long(t, "balance_before"), o.balanceBefore.get)
          same("bet balance_after", long(t, "balance_after"), o.balanceAfter.get)
          same("bet funded_real_amount", long(t, "funded_real_amount"), o.realLeg.get)
          same("bet funded_restricted_amount", long(t, "funded_restricted_amount"), total(o.recordLegs))
          sameText("bet funded_breakdown", text(t, "funded_breakdown"), breakdown(o.recordLegs))
          sameText("bet contribution_breakdown", text(t, "contribution_breakdown"), breakdown(placed.bet.contributions))
          same("bet wagering_contribution", long(t, "wagering_contribution"), total(placed.bet.contributions))
        }
        val conversions = c.transactions("bonus_conversion")
        if conversions.nonEmpty then
          assertEquals(total(placed.conversions), conversions.flatMap(long(_, "amount")).sum, s"${c.name}: converted amount")
        c.result.foreach { r =>
          sameText("result wallet_type", text(r, "wallet_type"), o.walletType.getOrElse(""))
          sameText("result funded_breakdown", text(r, "funded_breakdown"), breakdown(o.recordLegs))
          sameSet("result converting_records", text(r, "converting_records"), o.conversions)
          sameText("result bonus_converted", text(r, "bonus_converted"), o.conversions.nonEmpty.toString)
        }
        assert(kit.currentState.openBets.exists(_.roundKey == command.roundId), s"${c.name}: the round is not open")

/**
 * `credit-win.test.tl` (FR-011). The bet a fixture settles is the open bet its parameters describe;
 * the rebuild finds it by round. The win transaction's balances are real's for a real or split bet,
 * the first credited record's for a restricted one, as the original wrote them.
 */
class CreditWinFixturesSuite extends FixtureSuite("credit-win"):

  protected def check(c: Case): Unit =
    given Case = c
    val records = c.recordTwins.map(record)
    val kit     = FixtureRunner.kit(c, opening(c, records, Vector(openBet(c, records))))
    val before  = kit.currentState
    val result  = kit.call(WalletEntity.creditWin)(CreditWin("fixture-win", text(c.params, "round_id").get, long(c.params, "amount").get, c.at))
    val o       = result.replyValue
    c.error match
      case Some(error) => assertRefused(error, before, kit, result.events, o)
      case None =>
        accepted(o)
        assertWalletAndRecords(kit.currentState)
        assertResultBalances(o)
        c.transactions("win").foreach { t =>
          sameText("win wallet_type", text(t, "wallet_type"), o.walletType.getOrElse(""))
          same("win balance_before", long(t, "balance_before"), o.balanceBefore.get)
          same("win balance_after", long(t, "balance_after"), o.balanceAfter.get)
          same("win won_real_amount", long(t, "won_real_amount"), o.realLeg.get)
          same("win won_restricted_amount", long(t, "won_restricted_amount"), total(o.recordLegs))
          sameText("win won_breakdown", text(t, "won_breakdown"), breakdown(o.recordLegs))
        }
        c.result.foreach { r =>
          sameText("result wallet_type", text(r, "wallet_type"), o.walletType.getOrElse(""))
          same("result won_restricted_amount", long(r, "won_restricted_amount"), total(o.recordLegs))
          sameText("result won_breakdown", text(r, "won_breakdown"), breakdown(o.recordLegs))
        }
        val bet = text(c.params, "bet_tx_id").get
        assert(!kit.currentState.openBets.exists(_.key == bet), s"${c.name}: the bet is still open")
        assert(kit.currentState.settledBets.exists(b => b.key == bet && b.status == BetStatus.Settled), s"${c.name}: the bet is not settled")

/** `rollback-bet.test.tl` (FR-012): each leg to its source, a converted record's to real; contributions reversed. */
class RollbackBetFixturesSuite extends FixtureSuite("rollback-bet"):

  protected def check(c: Case): Unit =
    given Case = c
    val records = c.recordTwins.map(record)
    val kit     = FixtureRunner.kit(c, opening(c, records, Vector(openBet(c, records))))
    val before  = kit.currentState
    val bet     = text(c.params, "bet_tx_id").get
    val result  = kit.call(WalletEntity.rollbackBet)(RollbackBet("fixture-rollback", text(c.params, "round_id").get, Some(bet), c.at))
    val o       = result.replyValue
    c.error match
      case Some(error) => assertRefused(error, before, kit, result.events, o)
      case None =>
        accepted(o)
        assertWalletAndRecords(kit.currentState)
        assertResultBalances(o)
        c.transactions("rollback").foreach { t =>
          sameText("rollback wallet_type", text(t, "wallet_type"), o.walletType.getOrElse(""))
          same("rollback balance_before", long(t, "balance_before"), o.balanceBefore.get)
          same("rollback balance_after", long(t, "balance_after"), o.balanceAfter.get)
          same("rollback funded_real_amount", long(t, "funded_real_amount"), o.realLeg.get)
          same("rollback funded_restricted_amount", long(t, "funded_restricted_amount"), total(o.recordLegs))
          sameText("rollback funded_breakdown", text(t, "funded_breakdown"), breakdown(o.recordLegs))
        }
        c.result.foreach(r => sameText("result wallet_type", text(r, "wallet_type"), o.walletType.getOrElse("")))
        assert(kit.currentState.settledBets.exists(b => b.key == bet && b.status == BetStatus.Voided), s"${c.name}: the bet is not voided")
        assertEquals(kit.currentState.depositTurnoverPending, before.depositTurnoverPending, s"${c.name}: turnover restored")

/** `settle-round-as-loss.test.tl` (FR-013): no money moves; round counts fall; a spent record with no round left forfeits. */
class SettleRoundAsLossFixturesSuite extends FixtureSuite("settle-round-as-loss"):

  protected def check(c: Case): Unit =
    given Case = c
    val records = c.recordTwins.map(record)
    val kit     = FixtureRunner.kit(c, opening(c, records, Vector(openBet(c, records))))
    val before  = kit.currentState
    val bet     = text(c.params, "bet_tx_id").get
    val result  = kit.call(WalletEntity.settleLoss)(SettleLoss("fixture-loss", text(c.params, "round_id").get, c.at))
    val o       = result.replyValue
    c.error match
      case Some(error) => assertRefused(error, before, kit, result.events, o)
      case None =>
        accepted(o)
        assertWalletAndRecords(kit.currentState)
        assertResultBalances(o)
        assertEquals(kit.currentState.realBalance, before.realBalance, s"${c.name}: a loss moved real balance")
        val lost = kit.currentState.settledBets.find(_.key == bet).getOrElse(fail(s"${c.name}: the bet is not settled"))
        assertEquals(lost.status, BetStatus.Loss)
        c.transactions("loss").foreach { t =>
          sameText("loss wallet_type", text(t, "wallet_type"), o.walletType.getOrElse(""))
          same("loss funded_real_amount", long(t, "funded_real_amount"), lost.realLeg)
          same("loss funded_restricted_amount", long(t, "funded_restricted_amount"), total(lost.recordLegs))
          sameText("loss funded_breakdown", text(t, "funded_breakdown"), breakdown(lost.recordLegs))
        }
        val forfeited = c.mutations.filter(m => m.op == "set_properties" && text(m.properties, "status").contains("FORFEITED")).map(_.target)
        assertEquals(o.forfeited.toSet, forfeited.toSet, s"${c.name}: forfeited records")
