package io.eitheror.casino.modules

import com.thinkmorestupidless.ankka.controlplane.api.ServiceLifecycle

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/**
 * SC-002 on a local installation (quickstart step 2, the `kind` job): a brand applied with the core
 * alone runs the core and serves no game route; with `--modules casino` it runs game-provider beside
 * the core, and the core's services are not touched. It creates projects, and a deleted project's id is
 * never reusable, so it runs only on purpose: `CASINO_IT=1 just it`, with ANKKA_URL (or `ankka config`)
 * naming an installation with no vendor.
 *
 * Only `wallet` and `game-provider` of the modules' services have builds; their images are taken at
 * CASINO_IT_VERSION from the manifests' registry (load them into kind first). Every other service runs
 * CASINO_IT_IMAGE, a service that starts on ankka, and the suite prints each one it could not prove.
 */
class ModulesOnKindSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")

  private val enabled = sys.env.get("CASINO_IT").contains("1")
  private val image   = sys.env.getOrElse("CASINO_IT_IMAGE", "shopping-cart:latest")
  private val version = sys.env.getOrElse("CASINO_IT_VERSION", "0.0.0")
  private val slug    = s"it-${java.lang.Long.toString(System.currentTimeMillis() / 1000, 36)}"
  private val root    = Fixtures.root

  override def munitTests() = if enabled then super.munitTests() else Seq.empty

  private lazy val installation = Installation.load(root, "dev-kind").fold(e => fail(e), identity)
  private lazy val manifests    = ModuleManifest.validateTree(root, ModuleManifest.ContractView.fromTree(root))._1
  private lazy val built        = Keys.built(root)
  private lazy val values =
    val goldvibe = Values.load(root.resolve("brands/goldvibe/values.json"), Main.valuesView(root)).fold(rs => fail(rs.mkString), identity)
    goldvibe.copy(slug = slug, name = slug, secrets = goldvibe.secrets.map(s => s.copy(name = s.name.replace("goldvibe", slug))),
      values = goldvibe.values.updated("player-site-base", s"https://$slug.127.0.0.1.sslip.io:8443"))

  /** The plan, every service without a build on CASINO_IT_IMAGE. */
  private def plan(named: Vector[String]): BrandPlan =
    val p = BrandRender.plan(values, Keys.load(root), manifests, named, manifests.map(_.name -> version).toMap, Some(installation))
      .fold(e => fail(e), identity)
    def stand(m: ModulePlan) = m.copy(services = m.services.map(d =>
      if built.contains(d.name) then d else d.copy(service = d.service.copy(image = image))))
    p.copy(modules = p.modules.map(stand), held = p.held.map(stand))

  private def apply(cp: ControlPlane, named: Vector[String]): ApplyResult =
    if cp.project(slug).isEmpty then cp.createProject(slug, slug, sys.env.getOrElse("ANKKA_ORGANIZATION", "eitheror"))
    cp.setSecret(slug, BrandRender.TenantValues, Map("kyc-provider-key" -> "it-not-a-key", "st8-api-key" -> "it-not-a-key"))
    LocalApply.run(plan(named), values, Some(installation), cp,
      LocalApply.Options(sys.env.getOrElse("ANKKA_ORGANIZATION", "eitheror"), ready = LocalApply.poll(within = java.time.Duration.ofMinutes(15))))

  private val http = HttpClient.newHttpClient()

  /** The status a service's exposed hostname answers a path with, or None when it is not exposed. */
  private def answer(cp: ControlPlane, service: String, path: String): Option[Int] =
    cp.services(slug).find(_.name == service).flatMap(_.hostname).map { host =>
      http.send(HttpRequest.newBuilder(URI.create(s"${host.stripSuffix("/")}$path")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode
    }

  private def unproven(p: BrandPlan): Vector[String] = p.services.map(_.name).filterNot(built.contains)

  test("the core alone runs the core and no game route; casino then adds game-provider and leaves the core's services alone (SC-002)") {
    assert(LocalApply.local(installation, sys.env).isRight, "the installation named has a vendor: this suite is local mode's")
    val cp = HttpControlPlane.fromEnvironment()

    val core = apply(cp, Vector.empty)
    assert(core.ok, core.failure.toString)
    assertEquals(cp.services(slug).map(_.name).toSet, manifests.find(_.name == "core").get.services.map(_.name).toSet)
    assert(cp.services(slug).forall(_.lifecycle == ServiceLifecycle.Ready))
    assertEquals(answer(cp, "game-provider", s"/v1/casino/catalog/games?brand=$slug"), None, "no game-provider, so no game route")
    val wallet = answer(cp, "wallet", s"/v1/casino/brands/$slug/wallets/p-1/EUR/balance")
    assert(wallet.exists(c => c != 404 && c < 500), s"the wallet answers on its route: $wallet")
    val before = cp.services(slug).map(s => s.name -> s.generation).toMap

    val casino = apply(cp, Vector("casino"))
    assert(casino.ok, casino.failure.toString)
    assert(cp.services(slug).exists(s => s.name == "game-provider" && s.lifecycle == ServiceLifecycle.Ready))
    assert(answer(cp, "game-provider", s"/v1/casino/catalog/games?brand=$slug").exists(c => c != 404 && c < 500), "the game route is served")
    val after = cp.services(slug).map(s => s.name -> s.generation).toMap
    assertEquals(after.filter((n, _) => before.contains(n)), before, "the core's services gained no generation")

    println(s"not proven here (no build; ran $image): ${unproven(plan(Vector("casino"))).mkString(", ")}")
  }
