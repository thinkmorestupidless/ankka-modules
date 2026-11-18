package io.eitheror.casino.modules

import com.thinkmorestupidless.ankka.testkit.GherkinSuite

/**
 * features/player-site/reference-site.feature (spec 027): its first scenario, the brand guyanabet
 * rendered by the modules tool from its values. The rest run in a browser against the built site,
 * in the site's own suite.
 */
class ReferenceSiteFeatures extends GherkinSuite("features/player-site/reference-site.feature"):

  private def InBrowser = "services/player-site/tests/features (playwright-bdd over the built site and the doubles)"

  override protected def ranElsewhere: Map[String, String] = Map(
    "the reference site answers at the brand's player site address in French and in English" -> InBrowser,
    "a visitor registers, signs in, deposits and plays at the reference site" -> InBrowser,
    "the reference site's pages call no service and hold no rule of a deposit or a launch" -> InBrowser,
    "a page of an old build finds its files during a rollout" -> InBrowser,
    "a reference site without the casino module is served without play" -> InBrowser
  )

  private val root    = Fixtures.root
  private val keys    = Keys.load(root)
  private val modules = ModuleManifest.validateTree(root, ModuleManifest.ContractView.fromTree(root))._1
  private var values: Values = null
  private var plan: BrandPlan = null

  When("the modules tool renders brand {string} with the modules {string}, {string}, {string} and {string}") {
    (slug: String, a: String, b: String, c: String, d: String) =>
      values = Values.load(root.resolve(s"brands/$slug/values.json"), Main.valuesView(root)).fold(rs => fail(rs.mkString("; ")), identity)
      val at = modules.map(_.name -> "0.1.0").toMap
      plan = BrandRender.plan(values, keys, modules, Vector(a, b, c, d), at, Some(Fixtures.devKind), routes = ModuleManifest.ContractView.contractRoutes(root))
        .fold(e => fail(e), identity)
  }

  Then("brand {string} runs the services of the modules {string}, {string}, {string}, {string} and {string}") {
    (slug: String, a: String, b: String, c: String, d: String, e: String) =>
      assertEquals(values.slug, slug)
      assertEquals(plan.held, Vector.empty)
      assertEquals(plan.modules.map(_.name).toSet, Set(a, b, c, d, e))
      val expected = modules.filter(m => Set(a, b, c, d, e).contains(m.name)).flatMap(_.serviceNames).toSet
      assertEquals(plan.services.map(_.name).toSet, expected)
      assert(expected.contains("player-site"), expected.toString)
  }

  Then("brand {string} takes no real money") { (slug: String) =>
    assertEquals(values.slug, slug)
    assert(!values.realMoney, s"$slug's values take real money")
    assert(!plan.realMoney, s"$slug's plan takes real money")
    plan.services.flatMap(s => s.service.env.find(_.name == "CASINO_REAL_MONEY").map(s.name -> _.value)).foreach { (name, v) =>
      assertEquals(v, Some("false"), name)
    }
  }
