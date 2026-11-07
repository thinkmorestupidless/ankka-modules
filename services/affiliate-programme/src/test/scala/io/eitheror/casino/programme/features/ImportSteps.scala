package io.eitheror.casino.programme.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.accounts.{Affiliate, Membership}
import io.eitheror.casino.programme.brands.Plan
import io.eitheror.casino.programme.commission.FactType
import io.eitheror.casino.programme.it.{Facts, Kit, Reply, Spinvibe}
import io.eitheror.casino.programme.ledger.{CommissionAccount, Ledger}
import io.eitheror.casino.programme.links.{BonusCode, Link, SigningKeys}
import io.eitheror.casino.programme.settlement.Payout

import scala.jdk.CollectionConverters.*

/** features/programme/import.feature (US9). A service of its own (build.sbt), brand `spinvibe`. */
class ImportSteps extends ProgrammeFeatures("import.feature"):
  override protected def kit: Kit = ImportSteps.kit
  override def afterAll(): Unit = { super.afterAll(); ImportSteps.kit.stop() }

  private var s: Spinvibe = null
  private var first: Reply = null
  private var before: Any  = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    s = null; first = null; before = null

  private def brand = Spinvibe.Brand
  private def account(aff: String) = kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke()
  private def membership(aff: String) = kit.components.forEventSourcedEntity(Membership.entityId(aff, brand)).call(Membership.get).invoke()
  private def ledger(aff: String) =
    kit.components.forEventSourcedEntity(CommissionAccount.entityId(aff, brand, "EUR")).call(CommissionAccount.get).invoke()
  private def payout(aff: String, period: String) =
    kit.components.forEventSourcedEntity(Payout.entityId(aff, brand, "EUR", period)).call(Payout.get).invoke()
  private def rows(aff: String) =
    kit.views.forView(Ledger).ask(Ledger.ofAffiliate, "affiliate" -> aff, "brand" -> brand, "from" -> "0000-00", "to" -> "9999-99")

  private def exported(): Unit = s = Spinvibe(kit)
  private def imported(): Unit = first = s.run()

  Given("the affiliate accounts of brand {string}") { (_: String) => exported() }
  Given("the commission ledger of brand {string}") { (_: String) => exported() }
  Given("the links and bonus codes of brand {string}") { (_: String) => exported() }
  Given("the import of brand {string}") { (_: String) => exported(); imported() }
  Given("the import of brand {string} ran") { (_: String) => exported(); imported() }
  When("they are imported") { () => imported() }
  When("it is imported") { () => imported() }

  Then("each becomes one account with one active, pending, suspended or rejected membership at brand {string} on its plan") { (_: String) =>
    for a <- s.bundle.accounts do
      val m = membership(a.id)
      assertEquals(m.status, Some(a.membership.status), a.id)
      assertEquals(m.plan, a.membership.plan.map(Plan.idOf(brand, _)), a.id)
      assertEquals(kit.eventually(s"${a.id}'s one membership")(Option(kit.views.forView(io.eitheror.casino.programme.accounts.Memberships)
        .ask(io.eitheror.casino.programme.accounts.Memberships.byAffiliate, "affiliate" -> a.id)).filter(_.nonEmpty)).map(_.brand), Vector(brand))
  }
  Then("each keeps its parent, override, minimum payout threshold and postback address") { () =>
    for a <- s.bundle.accounts do
      val kept = account(a.id)
      assertEquals((kept.parent, kept.overrideBp, kept.minimumPayout, kept.postbackUrl), (a.parent, a.overrideBp, a.minimumPayout.getOrElse(Map.empty), a.postbackUrl), a.id)
  }
  Then("each affiliate's sign-in is unchanged") { () =>
    for a <- s.bundle.accounts do assertEquals(account(a.id).keycloakUser, a.keycloakUser, a.id)
    assert(kit.realm.usersWithRole("affiliate").fold(_ => Vector.empty, identity).isEmpty)
  }

  Then("every fact keeps its type, amount, currency, occurred-at, recorded-at, source affiliate and the payout that claimed it") { () =>
    for aff <- Seq(s.parent, s.sub) do
      val expected = s.facts.filter(_.affiliate == aff)
      val kept = kit.eventually(s"$aff's ledger")(Option(rows(aff)).filter(_.size == expected.size))
      for f <- expected do
        val r = kept.find(_.factId == f.id).getOrElse(fail(s"${f.id} missing"))
        assertEquals((r.kind, r.amount, r.currency, r.occurredAt, r.recordedAt, r.sourceAffiliate, r.settledBy),
          (FactType.parse(f.kind).get, f.amount, f.currency, f.occurredAt, f.recordedAt, f.sourceAffiliate, f.settledBy), f.id)
  }
  Then("every payout keeps its status, figures and approval") { () =>
    for p <- s.bundle.payouts do
      val kept = payout(p.affiliate, p.period)
      assertEquals((kept.status, kept.figures.cpa, kept.figures.revShare, kept.figures.overrideAmount, kept.figures.adjustments),
        (Some(p.status), p.cpa, p.revShare, p.overrideAmount, p.adjustments))
      assertEquals((kept.approvedBy, kept.approvedAt, kept.paidBy), (p.approvedBy, p.approvedAt, p.paidBy))
  }
  Then("a fold of the imported facts equals the original's revenue for every affiliate and period") { () =>
    for (aff, period) <- s.periods do
      val expected = s.folded(aff, period)
      def read = kit.request("GET", s"/v1/affiliates/$brand/accounts/$aff/revenue?period=$period", headers = kit.asManager(Seq(brand)))
      val figure = kit.eventually(s"$aff $period")(read.json.get("figures").elements().asScala.toVector.find(_.path("currency").asText == "EUR")
        .filter(_.path("totalCommission").asLong == expected("totalCommission")))
      expected.foreach((k, v) => assertEquals(figure.path(k).asLong, v, s"$aff $period $k"))
  }

  Then("each keeps its legacy tag for the transition period") { () =>
    assertEquals(kit.components.forKeyValueEntity(EntityId(s.link)).call(Link.get).invoke().legacyBtag, Some(s.link))
    kit.eventually("the legacy tag document")(Option(kit.request("GET", "/v1/programme/documents/legacy-tags")).filter(_.text.contains(s.link))): Unit
  }
  Then("each gains a tag signed by the programme") { () =>
    val kid     = kit.components.forKeyValueEntity(EntityId(SigningKeys.Id)).call(SigningKeys.get).invoke().signing.get.kid
    val linkTag = kit.components.forKeyValueEntity(EntityId(s.link)).call(Link.get).invoke().tag
    val codeTag = kit.components.forKeyValueEntity(BonusCode.entityId(brand, s.code)).call(BonusCode.get).invoke().tag
    for tag <- Seq(linkTag.getOrElse(""), codeTag) do
      val header = new String(java.util.Base64.getUrlDecoder.decode(tag.split('.').head), "UTF-8")
      assert(tag.count(_ == '.') == 2 && header.contains(kid), s"$tag, header $header, the programme's key $kid")
  }

  When("a fact arrives from brand {string} for a player attributed before the import") { (_: String) =>
    kit.publish(Facts(brand, s.parent, s.link).ggrDaily(s.player, "2026-10-24", stake = 10_000, win = 6_000, at = kit.now))
  }
  Then("it is attributed, because the import carried the player's pseudonym and attribution") { () =>
    kit.eventually("the RevShare it earns")(Option(ledger(s.parent).unsettled.filter(f => f.kind == FactType.RevShare && f.period == "2026-10"))
      .filter(_.nonEmpty)): Unit
  }

  When("it is run again") { () =>
    before = (s.bundle.accounts.map(a => (account(a.id), membership(a.id))), ledger(s.parent), ledger(s.sub), s.bundle.payouts.map(p => payout(p.affiliate, p.period)))
    assertEquals(s.post().json.get("importId").asText, first.json.get("importId").asText)
    s.run(s.again): Unit
  }
  Then("nothing is duplicated") { () =>
    assertEquals((s.bundle.accounts.map(a => (account(a.id), membership(a.id))), ledger(s.parent), ledger(s.sub),
      s.bundle.payouts.map(p => payout(p.affiliate, p.period))), before)
    val expected = s.facts.count(_.affiliate == s.parent)
    kit.eventually("the ledger's rows")(Option(rows(s.parent).size).filter(_ >= expected))
    Thread.sleep(1_000)
    assertEquals(rows(s.parent).size, expected)
  }

object ImportSteps:
  lazy val kit: Kit = Kit(Vector(Spinvibe.Brand))
