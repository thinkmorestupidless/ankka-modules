package io.eitheror.casino.programme.api

import io.eitheror.casino.programme.api.Answers.*
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.ledger.LedgerRow
import io.eitheror.casino.programme.players.{PlayerFact, PlayerFactRow}

/** The reads that list rather than sum (M2 to M4, O38, O39): folds of the same two views as revenue. */
object Reports:
  /** The CPA facts, one per qualifying player: its pseudonym is the original's `playerRef` (FR-038). */
  def cpa(rows: Vector[LedgerRow]): Vector[CpaRow] =
    rows.filter(_.kind == FactType.Cpa).map(r => CpaRow(r.brand, r.currency, r.factId.split('/').last, r.occurredAt, r.amount))

  /** First deposits by campaign (`default` for a link with none), per brand. */
  def byCampaign(players: Vector[PlayerFactRow]): Vector[CampaignRow] =
    players.filter(_.kind == PlayerFact.FirstDeposit).groupBy(p => (p.brand, p.campaign.getOrElse("default"))).toVector.sortBy(_._1)
      .map { case ((b, c), ps) => CampaignRow(b, c, ps.size.toLong) }

  /**
   * Registrations and first deposits by the country the registration gave, per brand; a player closed
   * since has no country and counts under `unknown`.
   */
  def geo(players: Vector[PlayerFactRow]): Vector[GeoRow] =
    val countryOf = players.filter(_.kind == PlayerFact.Registration).map(p => (p.brand, p.pseudonym) -> p.country.getOrElse("unknown")).toMap
    def country(p: PlayerFactRow) = countryOf.getOrElse((p.brand, p.pseudonym), "unknown")
    val regs = players.filter(p => p.kind == PlayerFact.Registration && p.outcome == "bound").groupMapReduce(p => (p.brand, country(p)))(_ => 1L)(_ + _)
    val ftds = players.filter(_.kind == PlayerFact.FirstDeposit).groupMapReduce(p => (p.brand, country(p)))(_ => 1L)(_ + _)
    (regs.keySet ++ ftds.keySet).toVector.sorted.map(k => GeoRow(k._1, k._2, regs.getOrElse(k, 0L), ftds.getOrElse(k, 0L)))

  /** Bonus costs and chargebacks, as the original's deductions. */
  def deductions(rows: Vector[LedgerRow]): Vector[DeductionRow] =
    rows.collect {
      case r if r.kind == FactType.BonusCost  => DeductionRow(r.brand, r.currency, "BONUS_COST", r.amount, r.factId, r.recordedAt)
      case r if r.kind == FactType.Chargeback => DeductionRow(r.brand, r.currency, "CHARGEBACK", r.amount, r.factId, r.recordedAt)
    }
