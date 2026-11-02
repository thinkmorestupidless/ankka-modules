package io.eitheror.casino.programme.ledger

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.Wiring
import io.eitheror.casino.contract.money.{Amounts, Currency}
import io.eitheror.casino.programme.accounts.{Affiliate, AffiliateState, Membership, MembershipState}
import io.eitheror.casino.programme.brands.{BrandProgramme, BrandState, Plan, PlanState}
import io.eitheror.casino.programme.commission.Window
import io.eitheror.casino.programme.activity.{FraudFlag, RaiseFlag}
import io.eitheror.casino.programme.players.{Outcome, PlayerAttribution, PlayerFact, PlayerState}
import io.eitheror.casino.programme.postbacks.{Macros, Postback, SendPostback}

import java.time.Instant

/** The calls `ToLedger` makes, so its suite can route them to entity kits. */
trait LedgerCalls:
  def brand(slug: String): BrandState
  def membership(affiliate: String, brand: String): MembershipState
  def plan(brand: String, plan: String): PlanState
  def recordNgr(account: EntityId, c: RecordNgr): AccountState
  def recordCpa(account: EntityId, c: RecordCpa): Unit
  def recordPaymentFee(account: EntityId, c: RecordPaymentFee): Unit
  def player(brand: String, pseudonym: String): PlayerState
  def raiseFlag(id: EntityId, c: RaiseFlag): Unit
  def account(affiliate: String): AffiliateState
  def postback(id: EntityId, c: SendPostback): Unit

object LedgerCalls:
  def of(client: ComponentClient): LedgerCalls = new LedgerCalls:
    def brand(slug: String): BrandState = client.forKeyValueEntity(EntityId(slug)).call(BrandProgramme.get).invoke()
    def membership(affiliate: String, brand: String): MembershipState =
      client.forEventSourcedEntity(Membership.entityId(affiliate, brand)).call(Membership.get).invoke()
    def plan(brand: String, plan: String): PlanState = client.forKeyValueEntity(Plan.entityId(brand, plan)).call(Plan.get).invoke()
    def recordNgr(account: EntityId, c: RecordNgr): AccountState =
      client.forEventSourcedEntity(account).call(CommissionAccount.recordNgr).invoke(c)
    def recordCpa(account: EntityId, c: RecordCpa): Unit =
      client.forEventSourcedEntity(account).call(CommissionAccount.recordCpa).invoke(c): Unit
    def recordPaymentFee(account: EntityId, c: RecordPaymentFee): Unit =
      client.forEventSourcedEntity(account).call(CommissionAccount.recordPaymentFee).invoke(c): Unit
    def player(brand: String, pseudonym: String): PlayerState =
      client.forEventSourcedEntity(PlayerAttribution.entityId(brand, pseudonym)).call(PlayerAttribution.get).invoke()
    def raiseFlag(id: EntityId, c: RaiseFlag): Unit = client.forKeyValueEntity(id).call(FraudFlag.raise).invoke(c): Unit
    def account(affiliate: String): AffiliateState  = client.forEventSourcedEntity(EntityId(affiliate)).call(Affiliate.get).invoke()
    def postback(id: EntityId, c: SendPostback): Unit = client.forWorkflow(id).call(Postback.start).invoke(c): Unit

/**
 * What a player's recorded fact means for the ledger (data-model.md *Consumers*), at the binding's
 * affiliate: a qualifying first deposit writes CPA in the plan's currency while the membership is active;
 * every deposit writes its payment fee; an NGR input is folded at the plan's rate (the membership's
 * RevShare override when it has one), payable only while the membership is active (FR-016). Each of a
 * registration, a first deposit, a deposit and a positive RevShare delta queues a postback to the
 * affiliate's address, when they have one (FR-036).
 */
final class LedgerWriter(calls: LedgerCalls, now: () => Instant):
  def write(e: PlayerAttribution.Applied): Unit =
    e.binding.foreach { b =>
      val f          = e.fact
      lazy val brand = calls.brand(f.brand)
      lazy val m     = calls.membership(b.affiliate, f.brand)
      lazy val plan  = m.plan.map(calls.plan(f.brand, _)).filter(_.exists)
      def account(currency: String) = CommissionAccount.entityId(b.affiliate, f.brand, currency)
      // Once per deposit: the brand's `deposit` fact, never the `first-deposit` it also emits for the first.
      def paymentFee(): Unit =
        f.currency.filter(_ => f.kind == PlayerFact.Deposit).foreach { c =>
          calls.recordPaymentFee(account(c), RecordPaymentFee(f.factId, f.amount, brand.fees.payment, f.occurredAt, brand.reportingZone, now()))
        }
      lazy val clickId = if f.kind == PlayerFact.Registration then f.clickId else calls.player(f.brand, f.pseudonym).clickId
      def postback(event: String, payout: Option[String], status: String): Unit =
        calls.account(b.affiliate).postbackUrl.filter(_.nonEmpty).foreach { address =>
          calls.postback(Postback.id(b.affiliate, f.brand, event, f.factId),
            SendPostback(b.affiliate, f.brand, event, Macros.render(address, event, clickId, payout, Some(status))))
        }
      e.outcome match
        case Outcome.Bound => postback(LedgerWriter.Registration, None, "ok")
        case Outcome.FirstDeposit(qualifies) =>
          val cpa = plan.filter(p => qualifies && m.active && p.paysCpa)
          cpa.foreach { p =>
            calls.recordCpa(account(p.cpaCurrency),
              RecordCpa(s"cpa/${f.brand}/${f.pseudonym}", p.cpaAmount, f.occurredAt, brand.reportingZone, now()))
          }
          paymentFee()
          // As the original: the CPA paid, or 0 on a plan that pays RevShare; nothing on a CPA plan that did not pay.
          cpa.map(p => major(p.cpaAmount, p.cpaCurrency)).orElse(plan.filter(_.paysRevShare).map(_ => "0"))
            .foreach(payout => postback(LedgerWriter.Ftd, Some(payout), "approved"))
        case Outcome.Deposit =>
          paymentFee()
          postback(LedgerWriter.Deposit, Some("0"), "ok")
        case Outcome.Ngr(kind, amount) =>
          f.currency.foreach { c =>
            val bp   = plan.filter(_.paysRevShare).fold(0)(p => m.revShareOverride.getOrElse(p.revShareBp))
            val rate = Rate(bp, plan.fold(Window.Period)(_.window), payable = m.active)
            val fees = FeeRates(brand.fees.admin, brand.fees.payment, brand.fees.provider)
            val after = calls.recordNgr(account(c), RecordNgr(f.factId, kind, amount, f.occurredAt, rate, fees, brand.reportingZone, now()))
            after.unsettled.find(_.id == s"revshare/${f.factId}").filter(_.amount > 0)
              .foreach(d => postback(LedgerWriter.RevShare, Some(major(d.amount, c)), "approved"))
          }
          flag(e, b.affiliate, kind, brand)
        case _ => ()
    }

  private def major(minor: Long, currency: String): String =
    Currency.from(currency).fold(_ => minor.toString, Amounts.render(minor, _))

  /**
   * FR-032: once the player's bonus costs or chargebacks reach the brand's threshold, a flag for that
   * player, brand and reason. The player's counters are read after the fact, and only rise, so a
   * redelivered event raises the same flag, which is raised once.
   */
  private def flag(e: PlayerAttribution.Applied, affiliate: String, kind: String, brand: BrandState): Unit =
    val f = e.fact
    val reason = kind match
      case "bonus-cost" => Some((FraudFlag.BonusCost, brand.thresholds.bonusCost, "bonus deductions"))
      case "chargeback" => Some((FraudFlag.Chargeback, brand.thresholds.chargeback, "chargebacks"))
      case _            => None
    reason.foreach { (code, threshold, what) =>
      val p     = calls.player(f.brand, f.pseudonym)
      val count = if code == FraudFlag.BonusCost then p.fraud.bonusCosts else p.fraud.chargebacks
      if count >= threshold then
        calls.raiseFlag(FraudFlag.entityId(f.brand, FraudFlag.flagId(f.brand, f.pseudonym, code)),
          RaiseFlag(f.brand, f.pseudonym, affiliate, code, s"$threshold+ $what", f.period.getOrElse(""), now()))
    }

object LedgerWriter:
  /** The original's postback events (`S/Postback.scala`). */
  val Registration = "registration"
  val Ftd          = "ftd"
  val Deposit      = "deposit"
  val RevShare     = "revshare"

final class ToLedger(context: ConsumerContext) extends Consumer[PlayerAttribution.Event, Unit]:
  def onMessage(event: PlayerAttribution.Event): Effect =
    LedgerWriter(LedgerCalls.of(context.componentClient), () => Wiring.clock.get().instant()).write(event)
    effects.done()

object ToLedger extends Consumer.Companion[ToLedger, PlayerAttribution.Event, Unit](
      componentId = ComponentId("to-ledger"),
      source = ChangeSource.eventsOf(PlayerAttribution)
    ):
  def create(ctx: ConsumerContext) = new ToLedger(ctx)
