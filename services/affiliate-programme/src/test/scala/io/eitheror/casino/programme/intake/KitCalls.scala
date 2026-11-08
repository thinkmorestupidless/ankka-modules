package io.eitheror.casino.programme.intake

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, KeyValueEntityTestKit}
import io.eitheror.casino.contract.facts.Click
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.brands.*
import io.eitheror.casino.programme.links.*
import io.eitheror.casino.programme.players.*

import scala.collection.mutable

/** `IntakeCalls` routed to entity kits by id, one kit per entity, as the runtime routes them. */
final class KitCalls extends IntakeCalls:
  val inbox       = mutable.Map.empty[String, KeyValueEntityTestKit[FactInbox, InboxState]]
  val brands      = mutable.Map.empty[String, KeyValueEntityTestKit[BrandProgramme, BrandState]]
  val memberships = mutable.Map.empty[String, EventSourcedTestKit[Membership, MembershipState, Membership.Event]]
  val plans       = mutable.Map.empty[String, KeyValueEntityTestKit[Plan, PlanState]]
  val links       = mutable.Map.empty[String, KeyValueEntityTestKit[Link, LinkState]]
  val players     = mutable.Map.empty[String, EventSourcedTestKit[PlayerAttribution, PlayerState, PlayerAttribution.Event]]
  val clicks      = mutable.ArrayBuffer.empty[Click]

  def inboxOf(brand: String, fact: String) = inbox.getOrElseUpdate(s"$brand/$fact", KeyValueEntityTestKit.of(FactInbox, s"$brand/$fact"))
  def brandOf(slug: String)                = brands.getOrElseUpdate(slug, KeyValueEntityTestKit.of(BrandProgramme, slug))
  def membershipOf(aff: String, brand: String) =
    memberships.getOrElseUpdate(s"$aff/$brand", EventSourcedTestKit.of(Membership, s"$aff/$brand"))
  def planOf(brand: String, plan: String) = plans.getOrElseUpdate(s"$brand/$plan", KeyValueEntityTestKit.of(Plan, s"$brand/$plan"))
  def linkOf(id: String)                  = links.getOrElseUpdate(id, KeyValueEntityTestKit.of(Link, id))
  def playerOf(brand: String, ps: String) = players.getOrElseUpdate(s"$brand/$ps", EventSourcedTestKit.of(PlayerAttribution, s"$brand/$ps"))

  def claim(c: ClaimFact): Claimed                         = inboxOf(c.brand, c.factId).call(FactInbox.claim)(c).replyValue
  def countDuplicate(brand: String): Unit                  = brandOf(brand).call(BrandProgramme.countDuplicate): Unit
  def countMismatch(brand: String): Unit                   = brandOf(brand).call(BrandProgramme.countMismatch): Unit
  def brand(slug: String): BrandState                      = brandOf(slug).call(BrandProgramme.get).replyValue
  def membership(a: String, b: String): MembershipState    = membershipOf(a, b).call(Membership.get).replyValue
  def plan(brand: String, plan: String): PlanState         = planOf(brand, plan).call(Plan.get).replyValue
  def link(id: String): LinkState                          = linkOf(id).call(Link.get).replyValue
  def player(brand: String, ps: String): PlayerState       = playerOf(brand, ps).call(PlayerAttribution.get).replyValue
  def apply(brand: String, ps: String, cmd: ApplyFact): Outcome = playerOf(brand, ps).call(PlayerAttribution.applyFact)(cmd).replyValue
  def outcome(brand: String, factId: String, o: String): Unit   = inboxOf(brand, factId).call(FactInbox.outcome)(o): Unit
  def click(fact: Click): Unit                             = clicks += fact
  val forgotten = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
  def forgetClicks(brand: String, clickId: String): Unit   = forgotten += (brand -> clickId)
