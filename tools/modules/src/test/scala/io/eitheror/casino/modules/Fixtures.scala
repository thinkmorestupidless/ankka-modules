package io.eitheror.casino.modules

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The repository the suites run in, and its fixtures (tests/fixtures). */
object Fixtures:
  val root: Path = Paths.get(sys.props.getOrElse("casino.root", ".")).toAbsolutePath.normalize

  /**
   * The repository as the suites that apply read it (spec 031 T029): `root`'s tree, but for a `dev-kind`
   * overlay that states `PLATFORM_VERSION=0.18.0`, the platform the services' runtimes need. `root`'s
   * overlay states what dev-kind runs today, which refuses them; this one is a fixture, not an
   * installation's. Built once, in a temporary directory, of links to `root` but for that one file.
   */
  lazy val applyRoot: Path = withPlatform("0.18.0")

  /** `root` with dev-kind's overlay stating `version` as its platform's, or stating none when it is None. */
  def withPlatform(version: String | Null): Path =
    val tmp = Files.createTempDirectory("casino-root-")
    def linkExcept(from: Path, to: Path, except: String): Unit =
      Files.list(from).iterator().asScala.filterNot(_.getFileName.toString == except)
        .foreach(e => Files.createSymbolicLink(to.resolve(e.getFileName.toString), e))
    linkExcept(root, tmp, "installation")
    val installation = Files.createDirectory(tmp.resolve("installation"))
    linkExcept(root.resolve("installation"), installation, "overlays")
    val overlays = Files.createDirectory(installation.resolve("overlays"))
    linkExcept(root.resolve("installation/overlays"), overlays, "dev-kind")
    val devKind = Files.createDirectory(overlays.resolve("dev-kind"))
    linkExcept(root.resolve("installation/overlays/dev-kind"), devKind, "settings.env")
    val settings = Files.readAllLines(root.resolve("installation/overlays/dev-kind/settings.env")).asScala.toVector
      .filterNot(_.trim.startsWith("PLATFORM_VERSION="))
    Files.write(devKind.resolve("settings.env"), (settings ++ Option(version).map(v => s"PLATFORM_VERSION=$v")).asJava)
    tmp.toFile.deleteOnExit()
    tmp

  /** The core's secret keys with test values: players' six (spec 008 T075), as `secrets` would set them. */
  val CoreSecrets: Map[String, String] =
    Seq("realm-admin", "webhook-secret", "realm-secret", "email-token", "lookup-key", "merchant-key").map(k => s"players-$k" -> "test").toMap

  def manifest(name: String): Path = root.resolve("tests/fixtures/manifests").resolve(s"$name.json")

  def load(name: String): Manifest =
    Manifest.load(manifest(name)).fold(ps => throw AssertionError(ps.mkString("; ")), identity)

  def brand(name: String): BrandManifest = load(name).asInstanceOf[BrandManifest]

  def devKind: Installation = Installation.load(root, "dev-kind").fold(e => throw AssertionError(e), identity)
