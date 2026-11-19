package io.eitheror.casino.modules

import java.nio.file.{Files, Path}

/** What counts as built is the release's rule for an image (`release.yml`, `web=`): review of spec 027. */
class KeysBuiltSuite extends munit.FunSuite:
  private def tree(services: (String, Seq[String])*): Path =
    val root = Files.createTempDirectory("keys-built")
    for (name, files) <- services do
      val dir = Files.createDirectories(root.resolve("services").resolve(name))
      files.foreach(f => Files.writeString(dir.resolve(f), ""))
    root

  test("an sbt build, or a web service with a package.json and a Dockerfile; a package.json alone has no image") {
    val root = tree("wallet" -> Seq("build.sbt"), "site" -> Seq("package.json", "Dockerfile"), "half" -> Seq("package.json"), "bare" -> Seq.empty)
    assertEquals(Keys.built(root), Set("wallet", "site"))
  }
