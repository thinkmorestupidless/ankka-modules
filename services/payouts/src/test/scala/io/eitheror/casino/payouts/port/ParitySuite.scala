package io.eitheror.casino.payouts.port

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * One test per recording in tests/port/payouts (constitution I), named after its file. The recordings are
 * made against a local original (T052–T053); until the replay is wired to them (T054), each is listed and
 * ignored, so a recording that arrives is seen and none is passed without being replayed.
 */
class ParitySuite extends munit.FunSuite:
  private val root: Path      = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize
  private val directory: Path = root.resolve("tests/port/payouts")

  private val found: Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.filter(p => p.toString.endsWith(".json") && !p.getFileName.toString.startsWith("suite-result"))
      .toVector.sorted

  if found.isEmpty then test("tests/port/payouts holds the recordings T052 makes".ignore) {}

  found.foreach(path => test((path.getFileName.toString.stripSuffix(".json") + " (replayed from T054)").ignore) {})
