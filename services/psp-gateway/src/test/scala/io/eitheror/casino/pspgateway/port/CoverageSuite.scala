package io.eitheror.casino.pspgateway.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.Recording
import io.eitheror.casino.pspgateway.it.Kit
import io.eitheror.casino.pspgateway.sessions.Provider

/**
 * SC-001: tests/port/psp-gateway holds a recording of a deposit through each of the nine providers and of a
 * payout through each of the four, every one made on two runs of the original and every one among those
 * `ParitySuite` replays. A provider with none is named.
 */
class CoverageSuite extends munit.FunSuite with LogCapturing:
  private val directory = Parity.root.resolve("tests/port/psp-gateway")
  private val paths     = Parity.recordings(directory)
  private lazy val recordings = paths.map(p => Recording.load(p).fold(e => throw AssertionError(e), identity))

  private def deposits(r: Recording): Boolean = r.steps.exists(s => s.request.method == "POST" && s.request.path == "/v1/psp/hpp/initiate")
  private def paysOut(r: Recording, p: Provider): Boolean = r.steps.exists(_.request.path.startsWith(s"/v1/psp/payout/${p.path}"))

  test("the recordings exist") { assert(paths.nonEmpty, s"no recordings in $directory (T026)") }

  test("a deposit through each of the nine providers is recorded") {
    val missing = Provider.values.toVector.filterNot(p => recordings.exists(r => deposits(r) && Parity.provider(r) == p))
    assert(missing.isEmpty, s"no deposit recording for ${missing.map(_.wire).mkString(", ")}")
  }

  test("a payout through each of the four payout providers is recorded") {
    val missing = Kit.PayoutProviders.filterNot(p => recordings.exists(paysOut(_, p)))
    assert(missing.isEmpty, s"no payout recording for ${missing.map(_.wire).mkString(", ")}")
  }

  test("every recording was made on two runs of the original") {
    val once = recordings.filter(_.runs < 2).map(_.scenario)
    assert(once.isEmpty, s"recorded on one run: ${once.mkString("; ")}")
  }

  test("every recording loads, so ParitySuite replays each one") {
    val broken = paths.flatMap(p => Recording.load(p).left.toOption)
    assert(broken.isEmpty, broken.mkString("\n"))
  }
