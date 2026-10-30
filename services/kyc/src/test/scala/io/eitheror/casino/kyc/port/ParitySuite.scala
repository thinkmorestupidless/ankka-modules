package io.eitheror.casino.kyc.port

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * One test per recording in tests/port/kyc (constitution I), named after its file. The recordings come
 * from scenarios 28 and 29 run against a local original (T047); until they are here and the replay is
 * written (T048), the suite says so as an ignored test rather than passing empty.
 */
class ParitySuite extends munit.FunSuite:
  private val root      = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize
  private val directory = root.resolve("tests/port/kyc")

  private def recordings: Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.filter(p => p.toString.endsWith(".json") && !p.getFileName.toString.startsWith("suite-result")).toVector.sorted

  test(s"tests/port/kyc replays through WireTranslation (${recordings.size} recordings; T047 records them, T048 replays them)".ignore) {}
