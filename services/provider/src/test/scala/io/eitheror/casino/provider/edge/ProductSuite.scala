package io.eitheror.casino.provider.edge

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Nothing scripted is deployable (spec 026 FR-016): the provider's runtime classpath holds no class
 * of the simulator, no scenario file, and the provider serves no `/game` page of its own (a game's
 * page is its game service's). The test classpath carries the library's test kit, so the classpath
 * checks read the runtime classpath sbt hands the forked tests, less the test directories and jars.
 */
class ProductSuite extends munit.FunSuite:

  private val runtime: Vector[Path] =
    sys.props("java.class.path").split(java.io.File.pathSeparator).toVector.map(Path.of(_))
      .filterNot(p => p.toString.contains("test-classes") || p.getFileName.toString.contains("-tests"))

  private def entries(p: Path): Vector[String] =
    if Files.isDirectory(p) then Files.walk(p).iterator().asScala.map(f => p.relativize(f).toString).toVector
    else if p.toString.endsWith(".jar") && Files.exists(p) then
      val jar = java.util.jar.JarFile(p.toFile)
      try jar.entries().asScala.map(_.getName).toVector finally jar.close()
    else Vector.empty

  private lazy val all: Vector[(Path, String)] = runtime.flatMap(p => entries(p).map(p -> _))

  test("no class of the simulator is on the provider's runtime classpath") {
    // The classpath read is the real one: the library the provider runs on is in it.
    assert(all.exists(_._2 == "io/eitheror/casino/provider/core/Deliver.class"), runtime.mkString("\n"))
    val found = all.filter(_._2.startsWith("io/eitheror/casino/simulator/"))
    assert(found.isEmpty, found.take(5).mkString("\n"))
  }

  test("no scenario file is on the provider's runtime classpath") {
    val found = all.filter((_, e) => e.startsWith("scenarios/") || e.contains("/scenarios/"))
    assert(found.isEmpty, found.take(5).mkString("\n"))
  }

  test("the provider serves no /game endpoint") {
    val sources = Files.walk(Path.of("src/main/scala")).iterator().asScala.filter(_.toString.endsWith(".scala")).toVector
    val pages   = sources.filter(f => """HttpEndpoint\("/game["/]""".r.findFirstIn(Files.readString(f)).isDefined)
    assert(pages.isEmpty, pages.mkString("\n"))
    val library = all.filter((_, e) => e.endsWith("GamePage.class"))
    assert(library.isEmpty, library.take(5).mkString("\n"))
  }
