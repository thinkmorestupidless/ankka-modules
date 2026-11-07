package io.eitheror.casino.programme.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.activity.{FlagState, FraudFlag}
import io.eitheror.casino.programme.brands.{BrandProgramme, Plan}
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.imports.Import
import io.eitheror.casino.programme.ledger.{CommissionAccount, Ledger}
import io.eitheror.casino.programme.links.{BonusCode, InviteLink, Link}
import io.eitheror.casino.programme.players.PlayerAttribution
import io.eitheror.casino.programme.settlement.Payout

import scala.jdk.CollectionConverters.*

/**
 * A brand's single-brand affiliates imported (US9, T088): every section lands, the ledger folds to what
 * the original's revenue answers, links keep their btag and gain a signed tag, a later fact for an
 * imported player is attributed, and an import run again writes nothing (SC-008). A service of its own
 * (build.sbt), brand `spinvibe`; the bundle exported from the recorded original is replayed by
 * port/ImportParitySuite.
 */
class ImportSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(240, "s")
  private def kit = ImportSuite.kit
  override def afterAll(): Unit = { super.afterAll(); ImportSuite.kit.stop() }
  override def beforeEach(context: BeforeEach): Unit = { kit.rewire(); kit.reset() }

  private def account(aff: String) = kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke()
  private def membership(aff: String) =
    kit.components.forEventSourcedEntity(Membership.entityId(aff, Spinvibe.Brand)).call(Membership.get).invoke()
  private def ledger(aff: String) =
    kit.components.forEventSourcedEntity(CommissionAccount.entityId(aff, Spinvibe.Brand, "EUR")).call(CommissionAccount.get).invoke()
  private def payout(aff: String, period: String) =
    kit.components.forEventSourcedEntity(Payout.entityId(aff, Spinvibe.Brand, "EUR", period)).call(Payout.get).invoke()
  /** The EUR figure revenue answers, once the views have it. */
  private def revenue(aff: String, period: String): Option[JsonNode] =
    val r = kit.request("GET", s"/v1/affiliates/spinvibe/accounts/$aff/revenue?period=$period", headers = kit.asManager(Seq("spinvibe")))
    assertEquals(r.status, 200, r.text)
    r.json.get("figures").elements().asScala.find(_.path("currency").asText == "EUR")

  test("each account becomes one account with one membership at the brand on its plan, its sign-in and settings kept") {
    val s = Spinvibe(kit)
    s.run()
    val a = account(s.parent)
    assertEquals((a.parent, a.keycloakUser, a.postbackUrl, a.minimumPayout), (None, Some(s"kc-${s.parent}"), Some(s"https://${s.parent}.example/pb?c={click_id}"),
      Map("EUR" -> 5_000L)))
    assertEquals((account(s.sub).parent, account(s.sub).overrideBp), (Some(s.parent), 1_000))
    val m = membership(s.parent)
    assertEquals((m.status, m.plan, m.revShareOverride), (Some(Membership.Active), Some(Plan.idOf("spinvibe", s.plan)), Some(3_500)))
    assertEquals((membership(s.rejected).status, membership(s.rejected).rejectedReason), (Some(Membership.Rejected), Some("no traffic")))
    val read = kit.request("GET", s"/v1/affiliates/spinvibe/accounts/${s.parent}", headers = kit.asManager(Seq("spinvibe")))
    assertEquals(read.json.get("email").asText, s.email(s.parent).toLowerCase)
    // Sign-in is the realm's, untouched: the import neither made nor changed a realm user.
    assertEquals(kit.realm.user(s"kc-${s.parent}"), None)
    val brand = kit.components.forKeyValueEntity(EntityId("spinvibe")).call(BrandProgramme.get).invoke()
    assertEquals((brand.fees.admin, brand.fees.payment, brand.thresholds.bonusCost, brand.payoutCycleDay, brand.defaultPlan, brand.page.payoutCycleDays),
      (1_000, 250, 4, Some(5), Some(Plan.idOf("spinvibe", s.plan)), Some(30)))
    // No notice and no default link: the affiliate had both in the original.
    Thread.sleep(1_000)
    assert(kit.customerIo.sent.isEmpty, kit.customerIo.sent.toString)
    assertEquals(kit.components.forKeyValueEntity(EntityId(Link.defaultIdOf(s.parent, "spinvibe"))).call(Link.get).invoke().exists, false)
  }

  test("the ledger keeps every fact and payout and folds to the original's revenue for every affiliate and period") {
    val s = Spinvibe(kit)
    s.run()
    for (aff, period) <- s.periods do
      val expected = s.folded(aff, period)
      val figure   = kit.eventually(s"$aff $period")(revenue(aff, period).filter(_.path("totalCommission").asLong == expected("totalCommission")))
      expected.foreach((k, v) => assertEquals(figure.path(k).asLong, v, s"$aff $period $k"))
    val paid = payout(s.parent, s.Aug)
    assertEquals((paid.status, paid.figures.cpa, paid.figures.revShare, paid.paidBy), (Some(Payout.Paid), 2_500L, 1_000L, Some("fin")))
    val approved = payout(s.parent, s.Sep)
    assertEquals((approved.status, approved.approvedBy, approved.figures.revShare), (Some(Payout.Approved), Some("fin"), 700L))
    // The paid payout's facts are settled and paid; the approved one's claimed; the rest owed.
    val rows = kit.eventually("the ledger rows")(Option(kit.views.forView(Ledger).ask(Ledger.ofPayout, "payout" -> s.paidPayout)).filter(_.size == 2))
    assert(rows.forall(_.paid), rows.toString)
    assertEquals(ledger(s.parent).unsettled.map(f => (f.kind, f.settledBy)).toSet,
      Set[(FactType, Option[String])]((FactType.RevShare, Some(s.approvedPayout)), (FactType.Override, None)))
    kit.owed(s.sub, "spinvibe", "EUR")
    assertEquals(ledger(s.sub).unsettled.size, 2)
    // No postback, no override recomputed: the original's figures stand as they were.
    assertEquals(ledger(s.parent).unsettled.count(_.kind == FactType.Override), 1)
  }

  test("links and bonus codes keep their legacy tag for the transition and gain a signed tag; flags, notes and invite links arrive") {
    val s = Spinvibe(kit)
    s.run()
    val l = kit.components.forKeyValueEntity(EntityId(s.link)).call(Link.get).invoke()
    assertEquals((l.legacyBtag, l.affiliate, l.campaign), (Some(s.link), s.parent, Some("spring")))
    assert(l.tag.exists(_.count(_ == '.') == 2), l.toString)
    val c = kit.components.forKeyValueEntity(BonusCode.entityId("spinvibe", s.code)).call(BonusCode.get).invoke()
    assertEquals((c.live, c.link, c.affiliate), (true, s.link, s.parent))
    assert(c.tag.nonEmpty)
    val legacy = kit.eventually("the legacy tag listed")(Option(kit.request("GET", "/v1/programme/documents/legacy-tags")).filter(_.text.contains(s.link)))
    assertEquals(legacy.status, 200)
    assertEquals(kit.components.forKeyValueEntity(InviteLink.entityId(s.token)).call(InviteLink.get).invoke().live, true)
    val f: FlagState = kit.components.forKeyValueEntity(FraudFlag.entityId("spinvibe", s.flag)).call(FraudFlag.get).invoke()
    assertEquals((f.dismissed, f.imported, f.pseudonym), (true, true, s.player))
    kit.eventually("the note")(Option(kit.request("GET", s"/v1/affiliates/spinvibe/accounts/${s.parent}/notes",
      headers = kit.asManager(Seq("spinvibe")))).filter(_.text.contains("called about traffic"))): Unit
    // The dismissal was told in the original; it is not told again.
    Thread.sleep(1_000)
    assertEquals(kit.request("GET", s"/v1/my/${s.parent}/notifications", headers = kit.asAffiliate(s.parent)).json.size, 0)
  }

  test("a fact for a player attributed before the import is attributed; its first deposit pays no second CPA") {
    val s = Spinvibe(kit)
    s.run()
    val facts = Facts("spinvibe", s.parent, s.link)
    kit.publish(facts.ggrDaily(s.player, "2026-10-24", stake = 10_000, win = 6_000, at = kit.now))
    kit.publish(facts.firstDeposit(s.player, 5_000, kit.now))
    val rs = kit.eventually("the RevShare of the later fact")(
      Option(ledger(s.parent).unsettled.filter(f => f.kind == FactType.RevShare && f.period == "2026-10")).filter(_.nonEmpty))
    // 4,000 of GGR less the imported 10% admin fee, at the membership's own 35%.
    assertEquals(rs.map(_.amount).sum, 1_260L)
    Thread.sleep(1_000)
    assertEquals(ledger(s.parent).unsettled.count(_.kind == FactType.Cpa), 0)
    val p = kit.components.forEventSourcedEntity(PlayerAttribution.entityId("spinvibe", s.player)).call(PlayerAttribution.get).invoke()
    assertEquals((p.affiliate, p.guard.map(_.factId)), (Some(s.parent), Some(s"import/first-deposit/${s.player}")))
  }

  test("running the import again writes nothing: the same bundle answers the same import, a later export of the same data adds nothing") {
    val s = Spinvibe(kit)
    val first = s.run()
    val before = (ledger(s.parent), ledger(s.sub), payout(s.parent, s.Sep), membership(s.parent), account(s.parent))
    val same = s.post()
    assertEquals((same.status, same.json.get("importId").asText), (200, first.json.get("importId").asText))
    val again = s.run(s.again)
    assertNotEquals(again.json.get("importId").asText, first.json.get("importId").asText)
    assertEquals((ledger(s.parent), ledger(s.sub), payout(s.parent, s.Sep), membership(s.parent), account(s.parent)), before)
    assertEquals(again.json.get("counts").get("ledger").asInt, 2)
  }

  test("the import's state keeps no identity in clear") {
    val s = Spinvibe(kit)
    val r = s.run()
    val state = kit.components.forWorkflow(EntityId(r.json.get("importId").asText)).call(Import.get).invoke()
    assertEquals((state.bundle, state.accounts), (None, Vector.empty))
    assert(r.json.get("counts").get("accounts").asInt == 3, r.text)
  }

  test("a bundle is refused whole: another brand's, a brand the programme lacks, an email another account holds, or not a platform administrator") {
    val s = Spinvibe(kit)
    assertEquals(s.post(as = kit.asManager(Seq("spinvibe"))).status, 403)
    val other = s.bundle.copy(brand = "goldvibe")
    assertEquals(s.post(com.github.plokhotnyuk.jsoniter_scala.core.writeToString(other)).status, 400)
    assertEquals(kit.request("POST", "/v1/programme/imports/nowhere", Some(s.json), kit.asPlatformAdmin()).status, 404)
    val taken = kit.request("POST", "/v1/affiliates/spinvibe/apply", Some(s"""{"email":"${s.email(s.sub)}"}"""), kit.fromInternet)
    assertEquals(taken.status, 201, taken.text)
    val refused = s.post()
    assertEquals((refused.status, refused.json.get("error").asText), (409, "email_already_registered"))
    assert(refused.json.get("message").asText.contains(s.sub))
    assertEquals(account(s.parent).exists, false)
  }

object ImportSuite:
  lazy val kit: Kit = Kit(Vector(Spinvibe.Brand))
