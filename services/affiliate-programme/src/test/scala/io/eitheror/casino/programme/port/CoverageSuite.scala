package io.eitheror.casino.programme.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.Recording

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * T093: every scenario of features/programme is bound by a suite of the service's, and every one is
 * either shown by a recording of the original or named, with its reason, in the programme driver's
 * `NotDriven` (or its file in `NotDrivenFeatures`). tests/port/programme's recordings each load, were
 * made on two runs, and are named as a feature names its scenario; until T034 records them, the last
 * test fails.
 */
class CoverageSuite extends munit.FunSuite with LogCapturing:
  private val features = Parity.root.resolve("features/programme")
  private val driver   = Files.readString(Parity.root.resolve("tests/original/programme-driver/scenarios.go"))
  private val sources  = Parity.root.resolve("services/affiliate-programme/src/test/scala")

  private def files: Vector[Path] =
    Files.list(features).iterator.asScala.filter(_.toString.endsWith(".feature")).toVector.sorted

  private def scenarios(f: Path): Vector[String] =
    Files.readString(f).linesIterator.map(_.trim).collect { case l if l.startsWith("Scenario") => l.dropWhile(_ != ':').drop(1).trim }.toVector

  /** Every string literal of the driver's scenarios.go: its scenario names, its `Covers`, `NotDriven`'s keys. */
  private val named: Set[String] = "\"((?:[^\"\\\\]|\\\\.)*)\"".r.findAllMatchIn(driver).map(_.group(1)).toSet

  /** The feature files `NotDrivenFeatures` names. */
  private val notDrivenFiles: Set[String] =
    val block = driver.substring(driver.indexOf("var NotDrivenFeatures"))
    "\"([a-z-]+\\.feature)\"".r.findAllMatchIn(block.take(block.indexOf("\n}"))).map(_.group(1)).toSet

  test("every feature file of features/programme is bound by a suite") {
    val bound = Files.walk(sources).iterator.asScala.filter(_.toString.endsWith(".scala")).flatMap { p =>
      "ProgrammeFeatures\\(\"([a-z-]+\\.feature)\"\\)".r.findAllMatchIn(Files.readString(p)).map(_.group(1))
    }.toSet
    assertEquals(files.map(_.getFileName.toString).filterNot(bound), Vector.empty)
  }

  test("every scenario is a recording's or named, with its reason, in the driver's NotDriven") {
    val missing = for
      f <- files if !notDrivenFiles(f.getFileName.toString)
      // An outline's examples are named `<scenario> [<first value>]`.
      s <- scenarios(f) if !named(s) && !named.exists(_.startsWith(s"$s ["))
    yield s"${f.getFileName}: $s"
    assert(missing.isEmpty, missing.mkString("\n"))
  }

  test("tests/port/programme's recordings load, were made on two runs, and are named as features/programme names a scenario") {
    val paths = Parity.recordings(Parity.programme)
    val loaded = paths.map(p => Recording.load(p))
    assertEquals(loaded.flatMap(_.left.toOption), Vector.empty)
    val recordings = loaded.flatMap(_.toOption)
    assert(recordings.size >= 13, s"${recordings.size} recordings in tests/port/programme (T034 records thirteen)")
    assertEquals(recordings.filter(_.runs < 2).map(_.scenario), Vector.empty)
    val all = files.flatMap(scenarios).toSet
    assertEquals(recordings.map(_.scenario).filterNot(n => all(n.takeWhile(_ != '[').trim)), Vector.empty)
  }
