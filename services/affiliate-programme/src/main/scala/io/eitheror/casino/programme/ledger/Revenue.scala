package io.eitheror.casino.programme.ledger

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.commission.FactType.*
import io.eitheror.casino.programme.periods.Periods
import io.eitheror.casino.programme.players.{PlayerFact, PlayerFactRow}

import java.time.ZoneId

/**
 * One brand's figures in one currency, the original's revenue fields in minor units (contracts/routes.md):
 * the ledger's facts by type, and the counts of the player facts the brand sent. `currency` is absent for a
 * brand with registrations and no money yet.
 */
final case class Figure(
    brandId: String,
    currency: Option[String],
    ggr: Long = 0,
    excludedGgr: Long = 0,
    bonusCosts: Long = 0,
    chargebacks: Long = 0,
    adminFees: Long = 0,
    paymentFees: Long = 0,
    providerFees: Long = 0,
    ngr: Long = 0,
    cpaCommission: Long = 0,
    revShareCommission: Long = 0,
    overrideCommission: Long = 0,
    adjustments: Long = 0,
    totalCommission: Long = 0,
    registrationCount: Long = 0,
    ftdCount: Long = 0,
    depositCount: Long = 0,
    deposit2Count: Long = 0,
    deposit3Count: Long = 0,
    totalDepositAmount: Long = 0,
    fraudFlagCount: Long = 0
)

/** A revenue read's answer: the period asked for, and a figure per brand and currency. */
final case class RevenueAnswer(affiliateId: Option[String], period: String, figures: Vector[Figure])

object Revenue:
  /** Every figure is written, zeros too: a reader compares field by field with the original. */
  given JsonValueCodec[RevenueAnswer] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false).withTransientEmpty(false).withTransientNone(true))

  /** The figures of these ledger rows and player facts, per brand and currency, never summed across currencies. */
  def figures(ledger: Iterable[LedgerRow], players: Iterable[PlayerFactRow]): Vector[Figure] =
    val money = LedgerFold.totals(ledger).map(t => (t.brand, Option(t.currency)) -> t).toMap
    val counted = players.groupBy(p => (p.brand, p.currency))
    // Registrations carry no currency: they count on the brand's figures, or on one without a currency.
    val registrations = players.filter(_.outcome == "bound").groupMapReduce(_.brand)(_ => 1L)(_ + _)
    val keys = (money.keySet ++ counted.keySet.filter(_._2.isDefined) ++
      registrations.keySet.filterNot(b => money.keySet.exists(_._1 == b) || counted.keySet.exists(k => k._1 == b && k._2.isDefined))
        .map(b => (b, Option.empty[String]))).toVector.sortBy((b, c) => (b, c.getOrElse("")))
    keys.map { case key @ (brand, currency) =>
      val t  = money.get(key)
      val ps = counted.getOrElse(key, Nil)
      def amount(f: FactType) = t.fold(0L)(_.amount(f))
      def count(kind: String) = ps.count(_.kind == kind).toLong
      val excluded = ps.filter(p => PlayerFact.ggrKinds(p.kind) && (p.outcome == "before-binding" || p.outcome.startsWith("ignored:")))
      Figure(
        brandId = brand,
        currency = currency,
        ggr = amount(Ggr),
        excludedGgr = excluded.map(_.amount).sum,
        bonusCosts = amount(BonusCost),
        chargebacks = amount(Chargeback),
        adminFees = amount(AdminFee),
        paymentFees = amount(PaymentFee),
        providerFees = amount(ProviderFee),
        ngr = t.fold(0L)(_.ngr),
        cpaCommission = amount(Cpa),
        revShareCommission = amount(RevShare),
        overrideCommission = amount(Override),
        adjustments = amount(Adjustment),
        totalCommission = t.fold(0L)(_.payable),
        registrationCount = registrations.getOrElse(brand, 0L),
        ftdCount = count(PlayerFact.FirstDeposit),
        depositCount = count(PlayerFact.Deposit),
        deposit2Count = ps.count(p => p.kind == PlayerFact.Deposit && p.ordinal == 2).toLong,
        deposit3Count = ps.count(p => p.kind == PlayerFact.Deposit && p.ordinal == 3).toLong,
        totalDepositAmount = ps.filter(_.kind == PlayerFact.Deposit).map(_.amount).sum
      )
    }

  /** The `yyyy-MM` bounds a range is queried by; a day range is then held to its days. */
  def bounds(range: Periods.Range): (String, String) = range match
    case Periods.Range.Months(from, to) => (from.toString, to.toString)
    case Periods.Range.Days(from, to)   => (from.toString.take(7), to.toString.take(7))
    case Periods.Range.AllTime          => ("0000-00", "9999-99")

  /**
   * One affiliate's figures at each brand named, for a range: the ledger and the player facts read and
   * held to the range in each brand's reporting zone.
   */
  def read(reads: Reads, affiliate: Option[String], brands: Iterable[(String, ZoneId)], range: Periods.Range): Vector[Figure] =
    val (from, to) = bounds(range)
    val ledger = brands.toVector.flatMap { (b, zone) =>
      reads.ledger(affiliate, b, from, to).filter(r => range.holds(r.period, r.occurredAt, zone))
    }
    val players = brands.toVector.flatMap { (b, zone) =>
      reads.players(affiliate, b, from, to).filter(r => range.holds(r.period, r.occurredAt, zone))
    }
    figures(ledger, players)

  /** The two views a read folds, one affiliate's rows at a brand (or the brand's, with no affiliate) for a period range. */
  trait Reads:
    def ledger(affiliate: Option[String], brand: String, from: String, to: String): Vector[LedgerRow]
    def players(affiliate: Option[String], brand: String, from: String, to: String): Vector[PlayerFactRow]
