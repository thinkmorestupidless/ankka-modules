package io.eitheror.casino.programme.ledger

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.Wiring
import io.eitheror.casino.programme.accounts.{Membership, MembershipState}
import io.eitheror.casino.programme.brands.{BrandProgramme, BrandState, Plan, PlanState}
import io.eitheror.casino.programme.commission.Window
import io.eitheror.casino.programme.players.{Outcome, PlayerAttribution, PlayerFact}

import java.time.Instant

/** The calls `ToLedger` makes, so its suite can route them to entity kits. */
trait LedgerCalls:
  def brand(slug: String): BrandState
  def membership(affiliate: String, brand: String): MembershipState
  def plan(brand: String, plan: String): PlanState
  def recordNgr(account: EntityId, c: RecordNgr): Unit
  def recordCpa(account: EntityId, c: RecordCpa): Unit
  def recordPaymentFee(account: EntityId, c: RecordPaymentFee): Unit

object LedgerCalls:
  def of(client: ComponentClient): LedgerCalls = new LedgerCalls:
    def brand(slug: String): BrandState = client.forKeyValueEntity(EntityId(slug)).call(BrandProgramme.get).invoke()
    def membership(affiliate: String, brand: String): MembershipState =
      client.forEventSourcedEntity(Membership.entityId(affiliate, brand)).call(Membership.get).invoke()
    def plan(brand: String, plan: String): PlanState = client.forKeyValueEntity(Plan.entityId(brand, plan)).call(Plan.get).invoke()
    def recordNgr(account: EntityId, c: RecordNgr): Unit =
      client.forEventSourcedEntity(account).call(CommissionAccount.recordNgr).invoke(c): Unit
    def recordCpa(account: EntityId, c: RecordCpa): Unit =
      client.forEventSourcedEntity(account).call(CommissionAccount.recordCpa).invoke(c): Unit
    def recordPaymentFee(account: EntityId, c: RecordPaymentFee): Unit =
      client.forEventSourcedEntity(account).call(CommissionAccount.recordPaymentFee).invoke(c): Unit

/**
 * What a player's recorded fact means for the ledger (data-model.md *Consumers*), at the binding's
 * affiliate: a qualifying first deposit writes CPA in the plan's currency while the membership is active;
 * every deposit writes its payment fee; an NGR input is folded at the plan's rate (the membership's
 * RevShare override when it has one), payable only while the membership is active (FR-016).
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
      e.outcome match
        case Outcome.FirstDeposit(qualifies) =>
          if qualifies && m.active then
            plan.filter(_.paysCpa).foreach { p =>
              calls.recordCpa(account(p.cpaCurrency),
                RecordCpa(s"cpa/${f.brand}/${f.pseudonym}", p.cpaAmount, f.occurredAt, brand.reportingZone, now()))
            }
          paymentFee()
        case Outcome.Deposit => paymentFee()
        case Outcome.Ngr(kind, amount) =>
          f.currency.foreach { c =>
            val bp   = plan.filter(_.paysRevShare).fold(0)(p => m.revShareOverride.getOrElse(p.revShareBp))
            val rate = Rate(bp, plan.fold(Window.Period)(_.window), payable = m.active)
            val fees = FeeRates(brand.fees.admin, brand.fees.payment, brand.fees.provider)
            calls.recordNgr(account(c), RecordNgr(f.factId, kind, amount, f.occurredAt, rate, fees, brand.reportingZone, now()))
          }
        case _ => ()
    }

final class ToLedger(context: ConsumerContext) extends Consumer[PlayerAttribution.Event, Unit]:
  def onMessage(event: PlayerAttribution.Event): Effect =
    LedgerWriter(LedgerCalls.of(context.componentClient), () => Wiring.clock.get().instant()).write(event)
    effects.done()

object ToLedger extends Consumer.Companion[ToLedger, PlayerAttribution.Event, Unit](
      componentId = ComponentId("to-ledger"),
      source = ChangeSource.eventsOf(PlayerAttribution)
    ):
  def create(ctx: ConsumerContext) = new ToLedger(ctx)
