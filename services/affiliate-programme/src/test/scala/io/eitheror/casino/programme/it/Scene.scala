package io.eitheror.casino.programme.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.AttributionFact
import io.eitheror.casino.programme.accounts.{Membership, Suspend}
import io.eitheror.casino.programme.brands.{BrandProgramme, BrandState, Fees, Plan, SetGgrSource, UpdateBrand}
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.intake.FactInbox
import io.eitheror.casino.programme.ledger.{Ledger, LedgerRow}
import io.eitheror.casino.programme.players.{PlayerAttribution, PlayerState}

import java.time.Instant

/**
 * One affiliate's world at one brand for a scenario: a plan of its own, an active membership on it, a
 * link, and players bound through that link. Every fact is published on the brand's topic and waited
 * for at the inbox; the ledger is read through its view, polled until it says what is expected.
 */
final class Scene(val kit: Kit, val brand: String = "goldvibe", planType: String = Plan.RevShare, cpa: Long = 0,
    currency: String = "EUR", revShareBp: Int = 3000, minimumDeposit: Long = 0, carryover: Boolean = false):
  val plan: String      = kit.plan(brand, kit.fresh("plan"), planType, cpa, currency, revShareBp, minimumDeposit, carryover)
  val affiliate: String = kit.affiliate(kit.fresh("aff"), brand, plan)
  val link: String      = kit.link(kit.fresh("lnk"), affiliate, brand)
  val facts: Facts      = Facts(brand, affiliate, link)

  /** A player registered through the link, bound before the scene goes on. */
  def player(at: Instant = kit.now): String =
    val p = kit.fresh("ps")
    send(facts.registration(p, at))
    p

  /** Publishes a fact and answers what the inbox recorded of it. */
  def send(fact: AttributionFact, on: Option[String] = None): String =
    kit.publish(fact, on)
    outcome(fact.factId)

  def outcome(factId: String): String =
    kit.eventually(s"the inbox's outcome of $factId")(
      kit.components.forKeyValueEntity(EntityId(s"$brand/$factId")).call(FactInbox.get).invoke().outcome)

  def state(pseudonym: String): PlayerState =
    kit.components.forEventSourcedEntity(PlayerAttribution.entityId(brand, pseudonym)).call(PlayerAttribution.get).invoke()

  def rows: Vector[LedgerRow] =
    kit.views.forView(Ledger).ask(Ledger.ofAffiliate, "affiliate" -> affiliate, "brand" -> brand, "from" -> "0000-00", "to" -> "9999-99")

  /** The ledger once `ok` holds of it. */
  def rowsWhen(what: String)(ok: Vector[LedgerRow] => Boolean): Vector[LedgerRow] =
    kit.eventually(what)(Option(rows).filter(ok))

  def sum(rs: Vector[LedgerRow], kind: FactType, currency: String = currency, period: Option[String] = None): Long =
    rs.filter(r => r.kind == kind && r.currency == currency && period.forall(_ == r.period)).map(_.amount).sum

  def suspend(): Unit =
    kit.components.forEventSourcedEntity(Membership.entityId(affiliate, brand)).call(Membership.suspend)
      .invoke(Suspend("kit", Membership.Operator, kit.now)): Unit

object Scene:
  def brand(kit: Kit, slug: String): BrandState = kit.components.forKeyValueEntity(EntityId(slug)).call(BrandProgramme.get).invoke()

  /** Runs `body` with the brand's fees set, and puts them back after. */
  def withFees[A](kit: Kit, slug: String, fees: Fees)(body: => A): A =
    val entity = kit.components.forKeyValueEntity(EntityId(slug))
    val before = brand(kit, slug).fees
    entity.call(BrandProgramme.update).invoke(UpdateBrand(fees = Some(fees))): Unit
    try body
    finally entity.call(BrandProgramme.update).invoke(UpdateBrand(fees = Some(before))): Unit

  def setGgrSource(kit: Kit, slug: String, source: String, fromDay: String): Unit =
    kit.components.forKeyValueEntity(EntityId(slug)).call(BrandProgramme.setGgrSource).invoke(SetGgrSource(source, fromDay)): Unit
