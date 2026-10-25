package io.eitheror.casino.modules

import java.nio.file.{Path, Paths}

/** The repository the suites run in, and its fixtures (tests/fixtures). */
object Fixtures:
  val root: Path = Paths.get(sys.props.getOrElse("casino.root", ".")).toAbsolutePath.normalize

  /** The core's secret keys with test values: kyc's and players' six (spec 008 T075), as `secrets` would set them. */
  val CoreSecrets: Map[String, String] =
    Map("kyc-provider-key" -> "test") ++
      Seq("realm-admin", "webhook-secret", "realm-secret", "email-token", "lookup-key", "merchant-key").map(k => s"players-$k" -> "test")

  def manifest(name: String): Path = root.resolve("tests/fixtures/manifests").resolve(s"$name.json")

  def load(name: String): Manifest =
    Manifest.load(manifest(name)).fold(ps => throw AssertionError(ps.mkString("; ")), identity)

  def brand(name: String): BrandManifest = load(name).asInstanceOf[BrandManifest]

  def devKind: Installation = Installation.load(root, "dev-kind").fold(e => throw AssertionError(e), identity)
