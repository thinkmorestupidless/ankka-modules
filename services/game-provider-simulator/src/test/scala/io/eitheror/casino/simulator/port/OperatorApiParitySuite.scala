package io.eitheror.casino.simulator.port

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.{Recording, Replay}
import io.eitheror.casino.simulator.SimulatorTestKit

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Parity with the original's stand-in (constitution I; spec 001 contracts/recording.md): one test per
 * recording under `tests/port/simulator/`, made against `fake-st8` run from source, replayed against
 * the simulator's operator API. A difference fails naming the recording and the first JSON path.
 */
class OperatorApiParitySuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val mapper = ObjectMapper()
  private val dir    = Paths.get(sys.props("casino.root"), "tests", "port", "simulator")

  private val recordings = Files.list(dir).iterator().asScala.toVector.filter(_.toString.endsWith(".json")).sorted

  test("there are recordings to replay") { assert(recordings.nonEmpty, s"no recordings under $dir") }

  recordings.foreach { path =>
    val recording = Recording.load(path).fold(e => throw IllegalStateException(e), identity)
    test(recording.scenario) {
      val kit = SimulatorTestKit.kit
      kit.start()
      val differences = Replay.run(recording, request => {
        val answer = kit.call(request.method, request.path, request.body.map(mapper.writeValueAsString))
        Recording.Response(answer.status, Option(answer.body).filter(_.nonEmpty).flatMap(b => Try(mapper.readTree(b)).toOption))
      })
      assertEquals(differences.map(_.message), Vector.empty)
    }
  }
