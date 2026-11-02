package io.eitheror.casino.programme.ledger

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.Wiring
import io.eitheror.casino.programme.accounts.{Affiliate, AffiliateState, Membership, MembershipState}
import io.eitheror.casino.programme.brands.{BrandProgramme, BrandState}
import io.eitheror.casino.programme.network.Network

import java.time.Instant

/** The calls `Overrides` makes, so its suite can route them to entity kits. */
trait OverrideCalls:
  def affiliate(id: String): AffiliateState
  def membership(affiliate: String, brand: String): MembershipState
  def brand(slug: String): BrandState
  def recompute(account: EntityId, c: RecomputeOverride): Unit

object OverrideCalls:
  def of(client: ComponentClient): OverrideCalls = new OverrideCalls:
    def affiliate(id: String): AffiliateState = client.forEventSourcedEntity(EntityId(id)).call(Affiliate.get).invoke()
    def membership(affiliate: String, brand: String): MembershipState =
      client.forEventSourcedEntity(Membership.entityId(affiliate, brand)).call(Membership.get).invoke()
    def brand(slug: String): BrandState = client.forKeyValueEntity(EntityId(slug)).call(BrandProgramme.get).invoke()
    def recompute(account: EntityId, c: RecomputeOverride): Unit =
      client.forEventSourcedEntity(account).call(CommissionAccount.recomputeOverride).invoke(c): Unit

/**
 * Override (research R7, FR-015): a source's earnings in a period moved, so each ancestor, visited
 * once, is asked to write its share as a delta. The share an ancestor earns is the override percentage
 * of the affiliate one step below it on the walk (`S/InternalRoutes.scala:278-346`); a step at 0 writes
 * nothing but the walk goes on. An ancestor with no active membership at the brand is passed over.
 */
final class OverrideWriter(calls: OverrideCalls, now: () => Instant):
  def write(account: String, e: CommissionAccount.EarningsChanged): Unit =
    account.split('/') match
      case Array(source, brand, currency) =>
        lazy val zone = calls.brand(brand).reportingZone
        val chain     = source +: Network.ancestors(source, a => calls.affiliate(a).parent)
        chain.zip(chain.drop(1)).foreach { (below, ancestor) =>
          val bp = calls.affiliate(below).overrideBp
          if bp > 0 && calls.membership(ancestor, brand).active then
            calls.recompute(CommissionAccount.entityId(ancestor, brand, currency),
              RecomputeOverride(source, e.period, e.earnings, bp, zone, now()))
        }
      case _ => ()

final class Overrides(context: ConsumerContext) extends Consumer[CommissionAccount.Event, Unit]:
  def onMessage(event: CommissionAccount.Event): Effect =
    event match
      case e: CommissionAccount.EarningsChanged =>
        OverrideWriter(OverrideCalls.of(context.componentClient), () => Wiring.clock.get().instant()).write(messageContext.subject, e)
      case _ => ()
    effects.done()

object Overrides extends Consumer.Companion[Overrides, CommissionAccount.Event, Unit](
      componentId = ComponentId("overrides"),
      source = ChangeSource.eventsOf(CommissionAccount)
    ):
  def create(context: ConsumerContext) = new Overrides(context)
