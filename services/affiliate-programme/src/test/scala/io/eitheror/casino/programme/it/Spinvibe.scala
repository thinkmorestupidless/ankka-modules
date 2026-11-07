package io.eitheror.casino.programme.it

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import io.eitheror.casino.programme.brands.ProgrammePage
import io.eitheror.casino.programme.imports.*

import java.time.Instant
import scala.concurrent.duration.*

/**
 * A bundle shaped as the exporter writes one from the original (tests/original/programme-export), with
 * ids of the scenario's own: a parent and an active sub on a hybrid plan, a rejected applicant, a link
 * with its btag, a bonus code, an invite link, a creative, a note, an audit entry, a dismissed flag, a
 * player with its first deposit, and a ledger over two periods with one payout paid and one approved.
 */
final case class Spinvibe(kit: Kit, brand: String = Spinvibe.Brand):
  val plan      = kit.fresh("Hybrid")
  val parent    = kit.fresh("imp")
  val sub       = kit.fresh("sub")
  val rejected  = kit.fresh("rej")
  val link      = java.util.UUID.randomUUID().toString
  val code      = kit.fresh("SPIN").toUpperCase
  val token     = kit.fresh("tok")
  val player    = kit.fresh("ps")
  val creative  = kit.fresh("cre")
  val note      = kit.fresh("note")
  val audit     = kit.fresh("aud")
  val flag      = kit.fresh("flag")
  val Aug       = "2026-08"
  val Sep       = "2026-09"
  def paidPayout     = s"$parent/$brand/EUR/$Aug"
  def approvedPayout = s"$parent/$brand/EUR/$Sep"

  private def t(s: String) = Instant.parse(s)
  private def fact(id: String, aff: String, kind: String, amount: Long, at: String, period: String, settledBy: Option[String] = None,
      source: Option[String] = None) =
    BundleFact(s"$id-$aff", aff, "EUR", kind, amount, t(at), t(at).plusSeconds(1), period, source, settledBy)

  def email(aff: String) = s"$aff@Spinvibe.Example"

  val facts: Vector[BundleFact] = Vector(
    fact("cpa", parent, "cpa", 2_500, "2026-08-04T10:00:00Z", Aug, Some(paidPayout)),
    fact("rs8", parent, "revshare", 1_000, "2026-08-20T10:00:00Z", Aug, Some(paidPayout)),
    fact("ggr8", parent, "ggr", 5_000, "2026-08-20T10:00:00Z", Aug),
    fact("fee8", parent, "admin-fee", -100, "2026-08-20T10:00:00Z", Aug),
    fact("rs9", parent, "revshare", 700, "2026-09-10T10:00:00Z", Sep, Some(approvedPayout)),
    fact("ov9", parent, "override", 150, "2026-09-30T23:59:59Z", Sep, source = Some(sub)),
    fact("rs9", sub, "revshare", 1_500, "2026-09-12T10:00:00Z", Sep),
    fact("adj9", sub, "adjustment", -200, "2026-09-15T10:00:00Z", Sep)
  )

  private def account(id: String, status: String, parentOf: Option[String] = None, overrideBp: Int = 0, reason: Option[String] = None) =
    BundleAccount(id, s"  ${email(id)} ", s"Name of $id", displayName = Some(s"display $id"), companyName = Some(s"company $id"),
      parent = parentOf, overrideBp = overrideBp, keycloakUser = Some(s"kc-$id"), postbackUrl = Some(s"https://$id.example/pb?c={click_id}"),
      minimumPayout = Some(Map("EUR" -> 5_000L)), tags = Some(Vector("vip")), createdAt = t("2026-07-01T09:00:00Z"),
      membership = BundleMembership(status, Option.when(status != "rejected")(plan), revShareOverrideBp = Option.when(id == parent)(3_500),
        appliedAt = t("2026-07-01T09:00:00Z"), rejectedReason = reason))

  val bundle: Bundle = Bundle(
    brand = brand,
    exportedAt = t("2026-10-20T00:00:00Z"),
    settings = Some(BundleSettings(adminFeeBp = 1_000, paymentFeeBp = 250, bonusCostThreshold = Some(4), payoutCycleDay = Some(5),
      defaultPlan = Some(plan))),
    programme = Some(ProgrammePage(payoutCycleDays = Some(30), minimumPayoutAmount = Some(5_000))),
    plans = Vector(BundlePlan(plan, "hybrid", 2_500, "EUR", 3_000, 1_000, createdAt = t("2026-06-01T00:00:00Z"))),
    accounts = Vector(account(parent, "active"), account(sub, "active", Some(parent), 1_000),
      account(rejected, "rejected", reason = Some("no traffic"))),
    links = Vector(BundleLink(link, parent, Some("spring"), utm = Map("utmSource" -> "x"), legacyBtag = Some(link),
      createdAt = t("2026-07-02T00:00:00Z"))),
    bonusCodes = Vector(BundleCode(code, parent, link, createdAt = t("2026-07-03T00:00:00Z"))),
    inviteLinks = Vector(BundleInvite(token, kit.fresh("inv"), parent, t("2026-07-03T00:00:00Z"))),
    creatives = Vector(BundleCreative(creative, "banner", Some("image"), Some("https://cdn.example/b.png"), by = "mgr",
      createdAt = t("2026-07-03T00:00:00Z"))),
    notes = Vector(BundleNote(note, parent, "called about traffic", "mgr", t("2026-07-04T00:00:00Z"))),
    audit = Vector(BundleAudit(audit, parent, "AFFILIATE_APPROVED", "mgr", t("2026-07-01T10:00:00Z"), details = Map("planId" -> plan))),
    fraudFlags = Vector(BundleFlag(flag, player, parent, "3+ chargebacks", "", Aug, t("2026-08-25T00:00:00Z"), Some("mgr"),
      Some(t("2026-08-26T00:00:00Z")), Some("reviewed"))),
    players = Vector(BundlePlayer(player, parent, link, Some("spring"), country = Some("DE"), registeredAt = t("2026-08-03T10:00:00Z"),
      firstDeposit = Some(BundleDeposit(2_000, "EUR", t("2026-08-04T10:00:00Z"))))),
    facts = facts,
    payouts = Vector(
      BundlePayout(parent, "EUR", Aug, "paid", cpa = 2_500, revShare = 1_000, createdAt = t("2026-09-01T00:00:00Z"),
        approvedAt = Some(t("2026-09-02T00:00:00Z")), paidBy = Some("fin")),
      BundlePayout(parent, "EUR", Sep, "approved", revShare = 700, createdAt = t("2026-10-01T00:00:00Z"), approvedBy = Some("fin"),
        approvedAt = Some(t("2026-10-02T00:00:00Z")))
    )
  )

  def json: String = writeToString(bundle)

  /** The bundle exported again a day later: the same data, another digest. */
  def again: String = writeToString(bundle.copy(exportedAt = bundle.exportedAt.plusSeconds(86_400)))

  def post(body: String = json, as: Seq[(String, String)] = kit.asPlatformAdmin()): Reply =
    kit.request("POST", s"/v1/programme/imports/$brand", Some(body), as)

  /** Posts the bundle and waits for the import's end. */
  def run(body: String = json): Reply =
    val r = post(body)
    assert(r.status == 200 || r.status == 202, s"${r.status} ${r.text}")
    kit.eventually("the import's end", within = 60.seconds)(Option(post(body)).filter(_.json.get("status").asText == "finished"))

  /** The affiliates and periods the original has revenue for: those with facts. */
  def periods: Vector[(String, String)] = facts.map(f => (f.affiliate, f.period)).distinct

  /** What the original's revenue answers for an affiliate and period: the facts folded by type. */
  def folded(aff: String, period: String): Map[String, Long] =
    val of = facts.filter(f => f.affiliate == aff && f.period == period)
    def sum(kind: String) = of.filter(_.kind == kind).map(_.amount).sum
    Map("cpaCommission" -> sum("cpa"), "revShareCommission" -> sum("revshare"), "overrideCommission" -> sum("override"),
      "adjustments" -> sum("adjustment"), "totalCommission" -> (sum("cpa") + sum("revshare") + sum("override") + sum("adjustment")),
      "ggr" -> sum("ggr"), "adminFees" -> sum("admin-fee"))

object Spinvibe:
  val Brand = "spinvibe"
