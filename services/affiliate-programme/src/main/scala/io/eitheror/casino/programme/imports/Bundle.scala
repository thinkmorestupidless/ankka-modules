package io.eitheror.casino.programme.imports

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.brands.ProgrammePage
import io.eitheror.casino.programme.commission.FactType

import java.time.Instant

/**
 * What the exporter writes from one brand of the original and the import reads (FR-039, research R19):
 * the programme's own terms, money in minor units, every id the original's or made from it, a player
 * by the pseudonym the brand's `attribution` gave it. Version 1.
 */
final case class Bundle(
    version: Int = Bundle.Version,
    brand: String,
    exportedAt: Instant,
    settings: Option[BundleSettings] = None,
    programme: Option[ProgrammePage] = None,
    plans: Vector[BundlePlan] = Vector.empty,
    accounts: Vector[BundleAccount] = Vector.empty,
    links: Vector[BundleLink] = Vector.empty,
    bonusCodes: Vector[BundleCode] = Vector.empty,
    inviteLinks: Vector[BundleInvite] = Vector.empty,
    creatives: Vector[BundleCreative] = Vector.empty,
    notes: Vector[BundleNote] = Vector.empty,
    audit: Vector[BundleAudit] = Vector.empty,
    fraudFlags: Vector[BundleFlag] = Vector.empty,
    players: Vector[BundlePlayer] = Vector.empty,
    facts: Vector[BundleFact] = Vector.empty,
    payouts: Vector[BundlePayout] = Vector.empty
)

/** The brand's settings: fees and the override as basis points, the thresholds, the cycle, the zone and the default plan by name. */
final case class BundleSettings(adminFeeBp: Int = 0, paymentFeeBp: Int = 0, providerFeeBp: Int = 0, bonusCostThreshold: Option[Int] = None,
    chargebackThreshold: Option[Int] = None, payoutCycleDay: Option[Int] = None, reportingZone: Option[String] = None,
    defaultPlan: Option[String] = None)

/** A plan by its name: the programme's plan id is made from the brand and the name. */
final case class BundlePlan(name: String, planType: String, cpaAmount: Long = 0, cpaCurrency: String = "EUR", revShareBp: Int = 0,
    minimumDeposit: Long = 0, negativeCarryover: Boolean = false, status: String = "active", createdAt: Instant)

/**
 * An account and its one membership at the brand. The identity arrives in clear and is sealed by the
 * route before anything is kept: the import's own state holds the sealed form only (FR-037).
 */
final case class BundleAccount(
    id: String,
    email: String,
    name: String,
    displayName: Option[String] = None,
    companyName: Option[String] = None,
    website: Option[String] = None,
    trafficSource: Option[String] = None,
    paymentMethod: Option[String] = None,
    parent: Option[String] = None,
    overrideBp: Int = 0,
    keycloakUser: Option[String] = None,
    postbackUrl: Option[String] = None,
    minimumPayout: Option[Map[String, Long]] = None,
    managerId: Option[String] = None,
    group: Option[String] = None,
    tags: Option[Vector[String]] = None,
    termsVersion: Option[String] = None,
    termsAcceptedAt: Option[Instant] = None,
    createdAt: Instant,
    membership: BundleMembership
)

/** `status`: pending, active, suspended or rejected; `plan` a plan's name; `revShareOverrideBp` the membership's own rate. */
final case class BundleMembership(status: String, plan: Option[String] = None, revShareOverrideBp: Option[Int] = None, appliedAt: Instant,
    since: Option[Instant] = None, source: String = "import", suspendedBy: Option[String] = None, rejectedReason: Option[String] = None)

/** A tracking link under its original id, its `btag` kept as the legacy tag for the transition (FR-026). */
final case class BundleLink(id: String, affiliate: String, campaign: Option[String] = None, landingPath: Option[String] = None,
    utm: Map[String, String] = Map.empty, subIds: Vector[String] = Vector.empty, legacyBtag: Option[String] = None, createdAt: Instant,
    revokedAt: Option[Instant] = None)

final case class BundleCode(code: String, affiliate: String, link: String, offer: Option[String] = None, createdAt: Instant,
    revokedAt: Option[Instant] = None)

final case class BundleInvite(token: String, link: String, affiliate: String, createdAt: Instant, revokedAt: Option[Instant] = None)

final case class BundleCreative(id: String, name: String, creativeType: Option[String] = None, fileUrl: Option[String] = None,
    dimensions: Option[String] = None, description: Option[String] = None, by: String, createdAt: Instant, deletedAt: Option[Instant] = None)

final case class BundleNote(id: String, affiliate: String, text: String, author: String, createdAt: Instant)

final case class BundleAudit(id: String, affiliate: String, action: String, actor: String, at: Instant, before: Option[String] = None,
    after: Option[String] = None, details: Map[String, String] = Map.empty)

final case class BundleFlag(id: String, pseudonym: String, affiliate: String, reason: String, description: String = "", period: String,
    raisedAt: Instant, dismissedBy: Option[String] = None, dismissedAt: Option[Instant] = None, dismissalReason: Option[String] = None)

/** A player bound before the import: the binding, and its qualifying first deposit when it had one. */
final case class BundlePlayer(pseudonym: String, affiliate: String, link: String, campaign: Option[String] = None,
    subIds: Vector[String] = Vector.empty, clickId: Option[String] = None, country: Option[String] = None, registeredAt: Instant,
    firstDeposit: Option[BundleDeposit] = None)

final case class BundleDeposit(amount: Long, currency: String, at: Instant)

/**
 * A commission fact as the original wrote it. `kind` is the ledger's type (`cpa`, `revshare`,
 * `override`, `adjustment`, `ggr`, `bonus-cost`, `chargeback`, `admin-fee`, `payment-fee`,
 * `provider-fee`); `settledBy` the payout that claimed it, as `<affiliate>/<brand>/<currency>/<period>`.
 */
final case class BundleFact(id: String, affiliate: String, currency: String, kind: String, amount: Long, occurredAt: Instant,
    recordedAt: Instant, period: String, sourceAffiliate: Option[String] = None, settledBy: Option[String] = None,
    reason: Option[String] = None)

/** A payout as it stood: `status` pending, approved, rejected or paid. */
final case class BundlePayout(affiliate: String, currency: String, period: String, status: String, cpa: Long = 0, revShare: Long = 0,
    overrideAmount: Long = 0, adjustments: Long = 0, createdAt: Instant, approvedBy: Option[String] = None, approvedAt: Option[Instant] = None,
    rejectedBy: Option[String] = None, rejectedReason: Option[String] = None, paidBy: Option[String] = None, paidAt: Option[Instant] = None)

object Bundle:
  val Version = 1
  given JsonValueCodec[Bundle] = ProgrammeCodecs.wire[Bundle]

  /** Why a bundle cannot be imported at a brand, before anything is written. */
  def problem(b: Bundle, brand: String): Option[String] =
    def dup[A](what: String, ids: Vector[A]) = Option.when(ids.distinct.size != ids.size)(s"$what repeat an id")
    val plans = b.plans.map(_.name.trim).toSet
    Option.when(b.version != Version)(s"version is $Version, not ${b.version}")
      .orElse(Option.when(b.brand != brand)(s"the bundle is brand ${b.brand}'s, not $brand's"))
      .orElse(dup("accounts", b.accounts.map(_.id)))
      .orElse(dup("facts", b.facts.map(f => (f.affiliate, f.currency, f.id))))
      .orElse(dup("payouts", b.payouts.map(p => (p.affiliate, p.currency, p.period))))
      .orElse(b.accounts.collectFirst { case a if a.membership.plan.exists(p => !plans(p.trim)) => s"account ${a.id} names plan ${a.membership.plan.get}, not in the bundle" })
      .orElse(b.facts.collectFirst { case f if FactType.parse(f.kind).isEmpty => s"fact ${f.id} has kind ${f.kind}" })
      .orElse(b.accounts.collectFirst { case a if !Statuses(a.membership.status) => s"account ${a.id} has status ${a.membership.status}" })
      .orElse(b.payouts.collectFirst { case p if !PayoutStatuses(p.status) => s"payout ${p.affiliate}/${p.period} has status ${p.status}" })

  private val Statuses       = Set("pending", "active", "suspended", "rejected")
  private val PayoutStatuses = Set("pending", "approved", "rejected", "paid")
