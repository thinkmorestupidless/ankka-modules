package io.eitheror.casino.wallet.port

import com.thinkmorestupidless.ankka.http.Caller
import io.eitheror.casino.contract.port.{Recording, Replay}
import io.eitheror.casino.wallet.it.Harness

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * The parity tests (ankka-port §7; quickstart 7): one test per recording of the original's wallet
 * routes under `tests/port/wallet/`, each step translated by `WireTranslation` and replayed against
 * the rebuild with `contract.port.Replay`. A failure names the recording and the first differing
 * JSON path. The recordings come from `tests/original/wallet-driver` (T074); until they are made,
 * the suite says so as an ignored test rather than passing.
 *
 * The e2e recordings that reach the wallet through `game-provider`, `rewards` and `payouts` are listed
 * by `Blocked` and replay when those services exist (SC-001).
 */
class PortSuite extends Harness:

  private val root       = Paths.get(sys.props.getOrElse("casino.root", "../.."))
  private val directory  = root.resolve("tests/port/wallet")
  private val recordings: Vector[Path] =
    if Files.isDirectory(directory) then Files.list(directory).iterator().asScala.filter(_.toString.endsWith(".json")).toVector.sorted
    else Vector.empty

  if recordings.isEmpty then
    test(s"no recordings of the original's wallet routes yet under $directory: run the wallet driver (tasks.md T074)".ignore) {}

  for path <- recordings do
    val recording = Recording.load(path).fold(e => throw IllegalArgumentException(e), identity)
    test(s"${path.getFileName}: ${recording.scenario}") {
      val translated = scala.collection.mutable.Map.empty[Int, WireTranslation.Translated]
      var index      = 0
      val differences = Replay.run(recording, { request =>
        val t = WireTranslation.request(recording.scenario, index, request, brand)
        val caller = if t.verb == "deposit" then Caller.Service("payments", "merchant") else Caller.Service("local", "game-provider")
        val (status, body) = send(t.request.method, t.request.path, t.request.body.map(_.toString), as = caller)
        val observed = Recording.Response(status, Option(body).filter(_.nonEmpty).map(WireTranslation.parse))
        val expected = recording.steps(index).response
        translated(index) = t
        index += 1
        WireTranslation.answer(t, expected, observed)
      })
      assert(differences.isEmpty, differences.map(_.message).mkString("\n"))
    }

  for (name, blockedBy) <- Blocked.recordings do
    test(s"$name: blocked by $blockedBy".ignore) {}
