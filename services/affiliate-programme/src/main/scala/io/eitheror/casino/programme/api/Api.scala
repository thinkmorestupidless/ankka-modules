package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.EndpointClients
import io.eitheror.casino.programme.Settings
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.brands.{BrandProgramme, BrandState, Plan, PlanState, Plans}
import io.eitheror.casino.programme.ledger.{Ledger, LedgerRow, Revenue}
import io.eitheror.casino.programme.players.{PlayerFactRow, PlayerFacts}
import io.eitheror.casino.programme.sealing.{Identity, Sealing}

import java.time.Instant

/** What every endpoint is given: the settings, the clients, the ACLs, the realm and the clock. */
final class Api(val settings: Settings, val clients: EndpointClients, val access: Access, val realm: KeycloakAdmin, val now: () => Instant):
  def components = clients.componentClient
  def views      = clients.viewClient
  def secrets    = clients.secrets

  /** An account's parent, for the portal's walk: the entity's, since a view may lag a new sub. */
  def parentOf(affiliate: String): Option[String] =
    val s = account(affiliate)
    if s.exists then s.parent else None

  def account(affiliate: String): AffiliateState = components.forEventSourcedEntity(EntityId(affiliate)).call(Affiliate.get).invoke()
  def membership(affiliate: String, brand: String): MembershipState =
    components.forEventSourcedEntity(Membership.entityId(affiliate, brand)).call(Membership.get).invoke()
  def brand(slug: String): BrandState = components.forKeyValueEntity(EntityId(slug)).call(BrandProgramme.get).invoke()
  def plan(brand: String, plan: String): PlanState = components.forKeyValueEntity(Plan.entityId(brand, plan)).call(Plan.get).invoke()
  def plansOf(brand: String): Vector[PlanState] = views.forView(Plans).ask(Plans.ofBrand, "brand" -> brand)

  /** An account's memberships, from the view. */
  def membershipsOf(affiliate: String): Vector[MembershipState] =
    views.forView(Memberships).ask(Memberships.byAffiliate, "affiliate" -> affiliate)

  /** The account's identity, opened with its data key; none once erased. */
  def identity(affiliate: String, account: AffiliateState): Either[String, Option[Identity]] =
    Secrets(secrets).existingDataKey(affiliate) match
      case Left(e)          => Left(e.reason)
      case Right(None)      => Right(None)
      case Right(Some(key)) => Sealing.open(account.identity, key, affiliate).map(Some(_))

  /** The two views revenue reads fold. */
  val reads: Revenue.Reads = new Revenue.Reads:
    def ledger(affiliate: Option[String], brand: String, from: String, to: String): Vector[LedgerRow] =
      affiliate.fold(views.forView(Ledger).ask(Ledger.ofBrand, "brand" -> brand, "from" -> from, "to" -> to))(a =>
        views.forView(Ledger).ask(Ledger.ofAffiliate, "affiliate" -> a, "brand" -> brand, "from" -> from, "to" -> to))
    def players(affiliate: Option[String], brand: String, from: String, to: String): Vector[PlayerFactRow] =
      affiliate.fold(views.forView(PlayerFacts).ask(PlayerFacts.ofBrand, "brand" -> brand, "from" -> from, "to" -> to))(a =>
        views.forView(PlayerFacts).ask(PlayerFacts.ofAffiliate, "affiliate" -> a, "brand" -> brand, "from" -> from, "to" -> to))
