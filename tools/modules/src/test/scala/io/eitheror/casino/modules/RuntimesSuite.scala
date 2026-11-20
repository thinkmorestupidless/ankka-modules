package io.eitheror.casino.modules

/** Which runtimes a platform runs, and where its version is read (spec 031 contracts/runtime-check.md). */
class RuntimesSuite extends munit.FunSuite:

  test("a platform runs its own minor version and the one below; patch versions are not compared") {
    val table = Vector(
      ("0.18.0", "0.18.0", true), ("0.18.0", "0.18.3", true), ("0.18.0", "0.17.0", true),
      ("0.18.0", "0.16.3", false), ("0.18.0", "0.13.0", false), ("0.18.0", "0.19.0", false),
      ("0.16.3", "0.18.0", false), ("1.0.0", "0.18.0", false),
      ("0.18.0+3-abc-SNAPSHOT", "0.17.2", true), ("0.18.0", "0.17.0-SNAPSHOT", true)
    )
    table.foreach((platform, runtime, verdict) => assertEquals(Runtimes.supports(platform, runtime), verdict, s"$platform runs $runtime"))
  }

  test("every runtime is unsupported when the platform's version is not reported") {
    assertEquals(Runtimes.unsupported(None, Set("0.18.0", "0.17.0")), Set("0.18.0", "0.17.0"))
    assertEquals(Runtimes.unsupported(Some("0.18.0"), Set("0.18.0", "0.16.3")), Set("0.16.3"))
  }

  private def overlay(version: Option[String]) =
    Installation("dev-kind", version.map("PLATFORM_VERSION" -> _).toMap, java.nio.file.Path.of("installation/overlays/dev-kind/settings.env"))

  test("the control plane's platformVersion is read first, else the overlay's PLATFORM_VERSION") {
    val reporting = InMemoryControlPlane(installationJson = Some("""{"platformVersion":"0.18.0","backups":{}}"""))
    assertEquals(Runtimes.platform(overlay(Some("0.16.3")), Some(reporting)), Runtimes.Platform(Some("0.18.0"), "GET /installation"))
    val silent = InMemoryControlPlane(installationJson = Some("""{"backups":{}}"""))
    assertEquals(Runtimes.platform(overlay(Some("0.16.3")), Some(silent)).version, Some("0.16.3"))
    assertEquals(Runtimes.platform(overlay(None), Some(InMemoryControlPlane())).version, None)
  }

  test("the refusal names the platform, the runtimes and the services naming them, as the contract words it") {
    val lines = Runtimes.refusal(overlay(Some("0.16.3")), Runtimes.Platform(Some("0.16.3"), "settings.env"),
      Vector("core/wallet" -> "0.18.0", "core/players" -> "0.18.0", "casino/game-provider" -> "0.18.0"))
    assertEquals(lines, Vector(
      "refused: the installation dev-kind runs platform 0.16.3, which cannot run runtime 0.18.0",
      "  (named by: core/wallet, core/players, casino/game-provider)",
      "  a platform runs runtimes of its own minor version and the one below"))
    val none = Runtimes.refusal(overlay(None), Runtimes.Platform(None, "installation/overlays/dev-kind/settings.env"), Vector("core/wallet" -> "0.18.0"))
    assertEquals(none.head, "refused: the installation dev-kind reports no platform version (from installation/overlays/dev-kind/settings.env)")
    assertEquals(Runtimes.refusal(overlay(Some("0.18.0")), Runtimes.Platform(Some("0.18.0"), "x"), Vector("core/wallet" -> "0.18.0")), Vector.empty)
  }
