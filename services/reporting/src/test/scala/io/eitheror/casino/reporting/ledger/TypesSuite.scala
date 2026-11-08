package io.eitheror.casino.reporting.ledger

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant

class TypesSuite extends munit.FunSuite:
  private val eur = Currency.unsafe("EUR")
  private val at  = Instant.parse("2026-11-02T10:00:00Z")
  private val z   = MinorUnits.unsafe(0)
  private val sz  = SignedMinorUnits(0)

  test("each family shows the original wallet's type name") {
    val cases: Seq[(MoneyTransactionsFact, String)] = Seq(
      DepositConfirmed("p", eur, "t", z, "pay", 1, sz, z, at)                    -> "deposit",
      BetPlaced("p", eur, "t", "r", "g", z, "real", sz, z, at)                    -> "bet",
      WinCredited("p", eur, "t", "r", "g", z, "real", sz, z, at)                  -> "win",
      BetRolledBack("p", eur, "t", "r", z, sz, z, at)                             -> "rollback",
      RoundSettledAsLoss("p", eur, "t", "r", sz, z, at)                           -> "loss",
      RestrictedFundsAwarded("p", eur, "t", "a", z, z, sz, z, at)                 -> "bonus_credit",
      RestrictedFundsConverted("p", eur, "t", "a", z, sz, z, at)                  -> "bonus_conversion",
      RestrictedFundsForfeited("p", eur, "t", "a", z, "expired", z, sz, z, at)    -> "bonus_cancel",
      RestrictedFundsClosed("p", eur, "t", "a", "cap_expired", sz, z, at)         -> "bonus_cap_void",
      AdminAdjusted("p", eur, "t", sz, "real", "fix", "op", sz, z, at)            -> "admin_adjustment",
      HoldPlaced("p", eur, "t", "w", z, z, sz, z, at)                             -> "withdrawal",
      HoldReleased("p", eur, "t", "w", z, sz, z, at)                              -> "withdrawal_return",
      WalletWithdrawalReversed("p", eur, "t", "w", z, sz, z, at)                  -> "withdrawal_reversal",
      ProviderCredited("p", eur, "t", "ref", z, "real", None, sz, z, at)          -> "game_provider_credit"
    )
    cases.foreach((fact, tpe) => assertEquals(Types.of(fact), Some(tpe), fact.getClass.getSimpleName))
  }

  test("the filter: five values exact, bonus every bonus type, all or none everything") {
    assert(Types.matches(Some("bet"), "bet"))
    assert(!Types.matches(Some("bet"), "bonus_credit"))
    assert(Types.matches(Some("bonus"), "bonus_credit"))
    assert(Types.matches(Some("bonus"), "bonus_cap_void"))
    assert(!Types.matches(Some("bonus"), "game_provider_credit"))
    assert(Types.matches(Some("all"), "loss"))
    assert(Types.matches(None, "loss"))
    assertEquals(Types.filters, Set("deposit", "withdrawal", "bet", "win", "rollback", "bonus"))
  }
