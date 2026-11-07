package io.eitheror.casino.programme.intake

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.facts.{AttributionFact, Click}
import io.eitheror.casino.programme.Wiring
import io.eitheror.casino.programme.accounts.{Membership, MembershipState}
import io.eitheror.casino.programme.brands.{BrandProgramme, BrandState, Plan, PlanState}
import io.eitheror.casino.programme.links.{Link, LinkState}
import io.eitheror.casino.programme.periods.Periods
import io.eitheror.casino.programme.players.*

import java.time.{Instant, LocalDate}

/** What became of a fact on a channel: what the webhook answers, and what the consumer logs. */
enum Received:
  case Mismatched
  case Duplicate
  case Recorded(outcome: String)

/** The calls `Intake` makes, so the kit suite can route them to entity kits by id. */
trait IntakeCalls:
  def claim(c: ClaimFact): Claimed
  def countDuplicate(brand: String): Unit
  def countMismatch(brand: String): Unit
  def brand(slug: String): BrandState
  def membership(affiliate: String, brand: String): MembershipState
  def plan(brand: String, plan: String): PlanState
  def link(id: String): LinkState
  def player(brand: String, pseudonym: String): PlayerState
  def apply(brand: String, pseudonym: String, cmd: ApplyFact): Outcome
  def outcome(brand: String, factId: String, outcome: String): Unit
  /** A click fact: recorded against its link (FR-024). */
  def click(fact: Click): Unit

object IntakeCalls:
  /** The calls made through the component client, as the consumers and the webhook endpoint make them. */
  def of(client: ComponentClient): IntakeCalls = new IntakeCalls:
    def claim(c: ClaimFact): Claimed =
      client.forKeyValueEntity(FactInbox.entityId(c.brand, c.factId)).call(FactInbox.claim).invoke(c)
    def countDuplicate(brand: String): Unit =
      client.forKeyValueEntity(EntityId(brand)).call(BrandProgramme.countDuplicate).invoke(): Unit
    def countMismatch(brand: String): Unit =
      client.forKeyValueEntity(EntityId(brand)).call(BrandProgramme.countMismatch).invoke(): Unit
    def brand(slug: String): BrandState = client.forKeyValueEntity(EntityId(slug)).call(BrandProgramme.get).invoke()
    def membership(affiliate: String, brand: String): MembershipState =
      client.forEventSourcedEntity(Membership.entityId(affiliate, brand)).call(Membership.get).invoke()
    def plan(brand: String, plan: String): PlanState = client.forKeyValueEntity(Plan.entityId(brand, plan)).call(Plan.get).invoke()
    def link(id: String): LinkState                  = client.forKeyValueEntity(EntityId(id)).call(Link.get).invoke()
    def player(brand: String, pseudonym: String): PlayerState =
      client.forEventSourcedEntity(PlayerAttribution.entityId(brand, pseudonym)).call(PlayerAttribution.get).invoke()
    def apply(brand: String, pseudonym: String, cmd: ApplyFact): Outcome =
      client.forEventSourcedEntity(PlayerAttribution.entityId(brand, pseudonym)).call(PlayerAttribution.applyFact).invoke(cmd)
    def outcome(brand: String, factId: String, outcome: String): Unit =
      client.forKeyValueEntity(FactInbox.entityId(brand, factId)).call(FactInbox.outcome).invoke(outcome): Unit
    def click(fact: Click): Unit =
      client.forKeyValueEntity(ClickRecord.entityId(fact.brand, fact.factId)).call(ClickRecord.record)
        .invoke(RecordClick(fact, Wiring.clock.get().instant())): Unit

/**
 * A fact received on a channel (contracts/inbound.md *Intake*): the brand's own channel only, claimed
 * once on any channel, then recorded on its player with what the brand and the plan say. The checks of
 * FR-011 come before the player is touched: a revoked link, or an affiliate who is not a member (active
 * or suspended) at the brand, is unattributed and writes nothing.
 */
final class Intake(calls: IntakeCalls, now: () => Instant):
  def handle(fact: AttributionFact, channel: String, channelBrand: String): Received =
    if fact.brand != channelBrand then
      calls.countMismatch(channelBrand)
      Received.Mismatched
    else
      val body    = new String(com.github.plokhotnyuk.jsoniter_scala.core.writeToArray(fact)(using AttributionFact.given_JsonValueCodec_AttributionFact), "UTF-8")
      val claimed = calls.claim(ClaimFact(fact.brand, fact.factId, Intake.kindOf(fact), channel, FactInbox.currencyOf(body), fact.occurredAt, now()))
      // A claim with no outcome is a fact whose handling failed after the claim: it is handled again, the
      // player's own record of the fact ids it has seen keeping a second pass from writing twice.
      if !claimed.isNew && claimed.state.outcome.isDefined then
        calls.countDuplicate(fact.brand)
        Received.Duplicate
      else
        val outcome = fact match
          case c: Click =>
            calls.click(c)
            "click"
          case _ => PlayerFact.from(fact).fold("ignored:unreadable")(record)
        calls.outcome(fact.brand, fact.factId, outcome)
        Received.Recorded(outcome)

  private def record(f: PlayerFact): String =
    val player   = calls.player(f.brand, f.pseudonym)
    val named    = if f.kind == PlayerFact.Registration then Some((f.affiliate, f.link)) else player.binding.map(b => (b.affiliate, b.link))
    val refusal  = named.flatMap { (affiliate, link) =>
      if calls.link(link).revokedAt.isDefined then Some("revoked_link")
      else if !Intake.member(calls.membership(affiliate, f.brand)) then Some("not_a_member")
      else None
    }
    refusal match
      case Some(reason) => s"unattributed:$reason"
      case None =>
        val brand   = calls.brand(f.brand)
        val day     = f.reportingDay.map(LocalDate.parse).getOrElse(Periods.dayOf(f.occurredAt, brand.zone))
        val minimum =
          if f.kind != PlayerFact.FirstDeposit && f.kind != PlayerFact.Deposit then None
          else
            named.flatMap((affiliate, _) => calls.membership(affiliate, f.brand).plan)
              .map(calls.plan(f.brand, _)).filter(_.paysCpa).map(_.minimumDeposit)
        val dated   = f.copy(period = Some(Periods.periodOf(f.occurredAt, brand.zone)))
        Intake.describe(calls.apply(f.brand, f.pseudonym, ApplyFact(dated, brand.ggrSource.on(day), minimum)))

object Intake:
  /** A membership that receives facts: active, or suspended (FR-016). */
  def member(m: MembershipState): Boolean = m.status.exists(s => s == Membership.Active || s == Membership.Suspended)

  def kindOf(fact: AttributionFact): String = fact match
    case _: Click => "click"
    case f        => PlayerFact.from(f).fold("unknown")(_.kind)

  /** What `FactInbox` keeps of the player's answer. */
  def describe(o: Outcome): String = o match
    case Outcome.Bound            => "bound"
    case Outcome.Duplicate        => "duplicate"
    case Outcome.Unattributed(r)  => s"unattributed:$r"
    case Outcome.FirstDeposit(q)  => if q then "first-deposit" else "deposit"
    case Outcome.Deposit          => "deposit"
    case Outcome.Ngr(k, _)        => k
    case Outcome.BeforeBinding    => "before-binding"
    case Outcome.WithdrawalTotal  => "withdrawal"
    case Outcome.Ignored(r)       => s"ignored:$r"
    case Outcome.Closed           => "closed"
