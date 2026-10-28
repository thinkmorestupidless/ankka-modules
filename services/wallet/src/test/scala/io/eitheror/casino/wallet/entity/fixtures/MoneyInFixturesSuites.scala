package io.eitheror.casino.wallet.entity.fixtures

import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.entity.fixtures.FixtureRunner.*
import io.eitheror.casino.wallet.funding.Status

/**
 * `deposit.test.tl` (FR-022, FR-004a). The fixture's turnover multiplier and definition were inputs
 * to the original's deposit; in the rebuild they are the brand's terms, which the deposit route reads
 * from `BrandConfigEntity` and passes in, so they arrive as the command's `brandTerms`.
 */
class DepositFixturesSuite extends FixtureSuite("deposit"):

  override protected def ignored: Map[String, String] = Map(
    "unstamped wallet — deposit writes no pool properties" -> NoUnstampedWallet
  )

  protected def check(c: Case): Unit =
    given Case = c
    val kit    = FixtureRunner.kit(c, opening(c, c.recordTwins.map(record)))
    val before = kit.currentState
    val p      = c.params
    val terms  = TurnoverTerms(long(p, "turnover_multiplier").getOrElse(1L), text(p, "turnover_definition").getOrElse("gross_wagered"))
    val psp    = text(p, "psp_id").getOrElse("")
    val result = kit.call(WalletEntity.deposit)(Deposit(text(p, "payment_ref").get, long(p, "amount").get, psp,
      text(p, "terminal_id").getOrElse(""), psp, text(p, "scheme").getOrElse(""), terms, c.at))
    val o = result.replyValue
    accepted(o)
    val state     = kit.currentState
    val confirmed = result.events.collectFirst { case e: WalletEvent.DepositConfirmed => e }.getOrElse(fail("no deposit confirmed"))
    assertWalletAndRecords(state)
    assertResultBalances(o)
    c.transactions("deposit").foreach { t =>
      same("deposit amount", long(t, "amount"), confirmed.amount)
      same("deposit balance_before", long(t, "balance_before"), o.balanceBefore.get)
      same("deposit balance_after", long(t, "balance_after"), o.balanceAfter.get)
    }
    c.mutations.filter(m => m.op == "set_properties" && m.target.startsWith("deposit-record.")).map(_.properties).foreach { d =>
      val kept = state.recentDeposits.find(_.paymentReference == confirmed.key).getOrElse(fail("no deposit record kept"))
      same("deposit record turnover_required_amount", long(d, "turnover_required_amount"), kept.requiredTurnover)
      sameText("deposit record turnover_status", text(d, "turnover_status"), kept.status)
      same("deposit record turnover_multiplier", long(d, "turnover_multiplier"), kept.terms.multiplier)
      sameText("deposit record turnover_definition", text(d, "turnover_definition"), kept.terms.definition)
      sameText("deposit record scheme", text(d, "scheme"), confirmed.scheme)
      sameText("deposit record psp_id", text(d, "psp_id"), confirmed.psp)
      same("deposit record amount", long(d, "amount"), kept.amount)
    }
    assertEquals(state.depositTurnoverPending, before.depositTurnoverPending + confirmed.requiredTurnover, s"${c.name}: turnover pending")

/** `admin-adjust.test.tl` (FR-026): real or the primary active record, with a reason and an actor, never a deposit. */
class AdminAdjustFixturesSuite extends FixtureSuite("admin-adjust"):

  override protected def ignored: Map[String, String] = Map(
    "two open records — the credit lands on the primary record and the cache is the primary's balance" -> Since002.TwoRecords,
    "real debit to exactly zero closes the open cap record"                                           -> Since002.CapCloseAtZero
  )

  protected def check(c: Case): Unit =
    given Case = c
    val kit    = FixtureRunner.kit(c, opening(c, c.recordTwins.map(record)))
    val before = kit.currentState
    val p      = c.params
    val target = if text(p, "wallet_type").contains("bonus") then "restricted" else "real"
    val result = kit.call(WalletEntity.adjust)(Adjust(text(p, "payment_ref").get, target, long(p, "amount").get,
      text(p, "reason").getOrElse(""), "correction", text(p, "actor").getOrElse(""), c.at))
    val o = result.replyValue
    c.error match
      case Some(error) => assertRefused(error, before, kit, result.events, o)
      case None =>
        accepted(o)
        val state = kit.currentState
        assertWalletAndRecords(state)
        result.events.collectFirst { case e: WalletEvent.AdminAdjusted => e }.foreach(a => assertParts("from", a.parts))
        assertResultBalances(o)
        c.transactions("admin_adjustment").foreach { t =>
          sameText("adjustment wallet_type", text(t, "wallet_type"), if target == "real" then "real" else "bonus")
          same("adjustment amount", long(t, "amount"), long(p, "amount").get)
          same("adjustment balance_before", long(t, "balance_before"), o.balanceBefore.get)
          same("adjustment balance_after", long(t, "balance_after"), o.balanceAfter.get)
        }
        c.result.foreach { r =>
          sameSet("result closed_records", text(r, "closed_records"), o.forfeited)
          text(r, "bonus_status").foreach(s => assertEquals(o.forfeited.flatMap(state.record).map(_.status), Vector(s)))
        }
        assertEquals((state.totalDeposits, state.depositOrdinal), (before.totalDeposits, before.depositOrdinal), s"${c.name}: counted as a deposit")

/** `game-provider-credit.test.tl` (FR-027): onto the primary active record, else real. */
class GameProviderCreditFixturesSuite extends FixtureSuite("game-provider-credit"):

  override protected def ignored: Map[String, String] = Map(
    "two open records — the credit lands on the primary record and the cache is the primary's balance" -> Since002.TwoRecords
  )

  protected def check(c: Case): Unit =
    given Case = c
    val kit = FixtureRunner.kit(c, opening(c, c.recordTwins.map(record)))
    val p   = c.params
    val ref = text(p, "payment_ref").get
    val o = kit.call(WalletEntity.providerCredit)(ProviderCredit(ref, long(p, "amount").get, ref, text(p, "reason").getOrElse(""),
      text(p, "actor").getOrElse(""), c.at)).replyValue
    accepted(o)
    assertWalletAndRecords(kit.currentState)
    assertResultBalances(o)
    c.transactions("game_provider_credit").foreach { t =>
      sameText("credit wallet_type", text(t, "wallet_type"), if o.landedOn.contains("real") then "real" else "bonus")
      same("credit amount", long(t, "amount"), long(p, "amount").get)
      same("credit balance_before", long(t, "balance_before"), o.balanceBefore.get)
      same("credit balance_after", long(t, "balance_after"), o.balanceAfter.get)
    }
    // A credit to a record leaves its release progress where it was.
    c.recordTwins.map(record).filter(_.status == Status.Active).foreach { a =>
      assertEquals(kit.currentState.record(a.id).get.record.releasedProgress, a.record.releasedProgress)
    }
