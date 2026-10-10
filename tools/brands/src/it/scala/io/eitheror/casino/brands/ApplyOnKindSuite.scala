package io.eitheror.casino.brands

import com.thinkmorestupidless.ankka.controlplane.api.ServiceLifecycle

/**
 * Applies brands on a real installation (quickstart sections 3 and 4). It creates projects there,
 * and a deleted project's id is never reusable, so it runs only on purpose: `CASINO_IT=1 just it`,
 * with ANKKA_URL (or `ankka config`) naming the installation. The casino's services do not have
 * images yet, so every service runs CASINO_IT_IMAGE, a service that starts on ankka (ankka's
 * shopping-cart sample, loaded into kind): what is checked is the tool, not the services.
 */
class ApplyOnKindSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")

  private val enabled = sys.env.get("CASINO_IT").contains("1")
  private val image   = sys.env.getOrElse("CASINO_IT_IMAGE", "shopping-cart:latest")
  private val suffix  = java.lang.Long.toString(System.currentTimeMillis() / 1000, 36)

  override def munitTests() = if enabled then super.munitTests() else Seq.empty

  private def brand(slug: String): BrandManifest =
    val pilot = Fixtures.brand("goldvibe")
    pilot.copy(
      slug = slug,
      services = pilot.services.map(s => s.copy(image = image, env = s.env.filterNot(_._2.startsWith("secret:")))),
      grantsReceived = pilot.grantsReceived.map(g => g.copy(principal = g.principal.replace("goldvibe", slug))),
      secrets = Vector.empty
    )

  private def waitReady(cp: ControlPlane, project: String, deadline: Long): Unit =
    while cp.services(project).exists(_.lifecycle != ServiceLifecycle.Ready) && System.currentTimeMillis() < deadline do
      Thread.sleep(5000)

  test("a brand is created from its manifest, every service ready, in under 15 minutes (SC-001)") {
    val cp = HttpControlPlane.fromEnvironment()
    val a = brand(s"it-$suffix-a")
    val started = System.currentTimeMillis()
    val result = Apply.run(Render.plan(a, Some(Fixtures.devKind)), cp, Apply.Options(sys.env.getOrElse("ANKKA_ORGANIZATION", "eitheror")))
    assert(result.ok, result.failure.toString)
    waitReady(cp, a.slug, started + 15 * 60 * 1000)
    val elapsed = (System.currentTimeMillis() - started) / 1000
    println(s"brand ${a.slug} ready in ${elapsed}s")
    assert(cp.services(a.slug).forall(_.lifecycle == ServiceLifecycle.Ready))
    assertEquals(cp.topics(a.slug).map(_.name).toSet, a.topics.map(_.name).toSet, "every topic declared, dotted names included")
    assertEquals(Apply.run(Render.plan(a, Some(Fixtures.devKind)), cp, Apply.Options("eitheror")).changes, Vector.empty)
  }

  test("moving one brand's wallet leaves another brand's generations unchanged") {
    val cp = HttpControlPlane.fromEnvironment()
    val a = brand(s"it-$suffix-b")
    val b = brand(s"it-$suffix-c")
    Vector(a, b).foreach(m => assert(Apply.run(Render.plan(m, Some(Fixtures.devKind)), cp, Apply.Options("eitheror")).ok))
    val before = cp.services(a.slug).map(s => s.name -> s.generation).toMap
    val moved = b.copy(services = b.services.map(s => if s.name == "wallet" then s.copy(env = s.env + ("CASINO_IT_MOVE" -> suffix)) else s))
    assert(Apply.run(Render.plan(moved, Some(Fixtures.devKind)), cp, Apply.Options("eitheror")).ok)
    assertEquals(cp.services(a.slug).map(s => s.name -> s.generation).toMap, before)
  }
