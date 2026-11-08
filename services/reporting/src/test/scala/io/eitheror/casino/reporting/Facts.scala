package io.eitheror.casino.reporting

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant

/** The facts the unit suites hand the intake, one builder per family, player `p-1` in EUR unless named. */
object Facts:
  val eur: Currency = Currency.unsafe("EUR")
  val at: Instant   = Instant.parse("2026-11-08T10:00:00Z")

  def m(v: Long): MinorUnits        = MinorUnits.unsafe(v)
  def s(v: Long): SignedMinorUnits  = SignedMinorUnits(v)
  def part(v: Long): Option[MinorUnits] = Some(m(v))

  def record(award: String, status: String = "ACTIVE", balance: Long = 500, progress: Long = 0, requirement: Long = 5000) =
    RecordProgress(award, status, m(balance), m(progress), m(requirement))

  // ── money.transactions ──
  def deposit(tx: String, amount: Long, at: Instant = at, player: String = "p-1", c: Currency = eur) =
    DepositConfirmed(player, c, tx, m(amount), s"pay-$tx", 1, s(amount), m(0), at, Some(s(amount)), Some(s(0)), part(amount), part(0))
  def bet(tx: String, round: String, amount: Long, depositPart: Long, at: Instant = at, records: Option[Vector[RecordProgress]] = None, player: String = "p-1") =
    BetPlaced(player, eur, tx, round, "g-1", m(amount), "real", s(1000), m(0), at, Some(s(900)), Some(s(100)), part(depositPart),
      part(amount - depositPart), records)
  def win(tx: String, round: String, amount: Long, depositPart: Long, at: Instant = at, player: String = "p-1") =
    WinCredited(player, eur, tx, round, "g-1", m(amount), "real", s(1500), m(0), at, None, None, part(depositPart), part(amount - depositPart))
  def rollback(tx: String, round: String, amount: Long, depositPart: Long, at: Instant = at) =
    BetRolledBack("p-1", eur, tx, round, m(amount), s(1000), m(0), at, None, None, part(depositPart), part(amount - depositPart))
  def loss(tx: String, round: String, at: Instant = at) = RoundSettledAsLoss("p-1", eur, tx, round, s(1000), m(0), at)
  def awarded(tx: String, award: String, amount: Long, at: Instant = at) =
    RestrictedFundsAwarded("p-1", eur, tx, award, m(amount), m(amount * 10), s(1000), m(amount), at,
      records = Some(Vector(record(award, balance = amount, requirement = amount * 10))))
  def converted(tx: String, award: String, amount: Long, at: Instant = at) =
    RestrictedFundsConverted("p-1", eur, tx, award, m(amount), s(1000 + amount), m(0), at,
      records = Some(Vector(record(award, "CONVERTED_PENDING_CAP", balance = 0))))
  def forfeited(tx: String, award: String, amount: Long, at: Instant = at) =
    RestrictedFundsForfeited("p-1", eur, tx, award, m(amount), "expired", m(0), s(1000), m(0), at,
      records = Some(Vector(record(award, "FORFEITED", balance = 0))))
  def closed(tx: String, award: String, at: Instant = at) =
    RestrictedFundsClosed("p-1", eur, tx, award, "cap_expired", s(1000), m(0), at, records = Some(Vector(record(award, "CLOSED", balance = 0))))
  def adjusted(tx: String, amount: Long, at: Instant = at) =
    AdminAdjusted("p-1", eur, tx, s(amount), "real", "goodwill", "staff-1", s(1000 + amount), m(0), at)
  def held(tx: String, request: String, amount: Long, at: Instant = at) =
    HoldPlaced("p-1", eur, tx, request, m(amount), m(0), s(1000 - amount), m(0), at)
  def released(tx: String, request: String, amount: Long, at: Instant = at) =
    HoldReleased("p-1", eur, tx, request, m(amount), s(1000), m(0), at)
  def reversed(tx: String, request: String, amount: Long, at: Instant = at) =
    WalletWithdrawalReversed("p-1", eur, tx, request, m(amount), s(1000), m(0), at)
  def credited(tx: String, ref: String, amount: Long, at: Instant = at) =
    ProviderCredited("p-1", eur, tx, ref, m(amount), "real", None, s(1000 + amount), m(0), at)
  def reserved(tx: String, order: String, amount: Long, at: Instant = at) =
    ReservationPlaced("p-1", eur, tx, order, m(amount), s(1000), m(0), m(amount), at)
  def unreserved(tx: String, order: String, amount: Long, at: Instant = at) =
    ReservationReleased("p-1", eur, tx, order, m(amount), s(1000), m(0), m(0), at)
  def settledDebit(tx: String, trade: String, amount: Long, at: Instant = at) =
    SettlementDebit("p-1", eur, tx, trade, "o-1", m(amount), m(0), "p-2", s(1000 - amount), m(0), m(0), at)
  def settledCredit(tx: String, trade: String, amount: Long, at: Instant = at) =
    SettlementCredit("p-1", eur, tx, trade, m(amount), "p-2", s(1000 + amount), m(0), m(0), at)
  def paid(tx: String, position: String, amount: Long, at: Instant = at) =
    ResolutionPayment("p-1", eur, tx, "mk-1", position, m(amount), s(1000 + amount), m(0), m(0), at)
  def accountDebit(tx: String, kind: String, amount: Long, at: Instant = at) =
    AccountDebit("p-1", eur, tx, kind, "brand", "fee", "trade-1", m(amount), s(1000 - amount), m(0), m(0), at)
  def accountCredit(tx: String, kind: String, amount: Long, at: Instant = at) =
    AccountCredit("p-1", eur, tx, kind, "brand", "merge", "ref-1", m(amount), s(1000 + amount), m(0), m(0), at)
