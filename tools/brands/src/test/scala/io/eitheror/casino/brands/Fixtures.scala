package io.eitheror.casino.brands

import java.nio.file.{Path, Paths}

/** The repository the suites run in, and its fixtures (tests/fixtures). */
object Fixtures:
  val root: Path = Paths.get(sys.props.getOrElse("casino.root", ".")).toAbsolutePath.normalize

  def manifest(name: String): Path = root.resolve("tests/fixtures/manifests").resolve(s"$name.json")

  def load(name: String): Manifest =
    Manifest.load(manifest(name)).fold(ps => throw AssertionError(ps.mkString("; ")), identity)

  def brand(name: String): BrandManifest = load(name).asInstanceOf[BrandManifest]

  def devKind: Installation = Installation.load(root, "dev-kind").fold(e => throw AssertionError(e), identity)
