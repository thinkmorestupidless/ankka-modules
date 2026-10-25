package io.eitheror.casino.ingest.port

import io.eitheror.casino.contract.port.Recording

import java.nio.file.Files

/**
 * tests/port/ingest's recordings: each loads (so `ParitySuite` replays it), each was made on two runs of the
 * original, and every divergence `Parity` marks is a row of plan.md's *Divergences*. The driver's
 * recordings (contracts/recording.md's table; T026) are named as the feature names its scenarios.
 */
class CoverageSuite extends munit.FunSuite:
  private val directory  = Parity.root.resolve("tests/port/ingest")
  private val paths      = Parity.recordings(directory)
  private lazy val recordings = paths.map(p => Recording.load(p).fold(e => throw AssertionError(e), identity))

  test("every recording loads") {
    val broken = paths.flatMap(p => Recording.load(p).left.toOption)
    assert(broken.isEmpty, broken.mkString("\n"))
  }

  test("every recording was made on two runs of the original") {
    val once = recordings.filter(_.runs < 2).map(_.scenario)
    assert(once.isEmpty, s"recorded on one run: ${once.mkString("; ")}")
  }

  test("every recording has a step of this service's") {
    val none = recordings.filterNot(_.steps.exists(s => Parity.ours(s.request))).map(_.scenario)
    assert(none.isEmpty, none.mkString("; "))
  }

  test("every marked divergence is a row of plan.md's Divergences") {
    val plan    = Files.readString(Parity.root.resolve("specs/006-ingest/plan.md"))
    val section = plan.substring(plan.indexOf("## Divergences"))
    val missing = Parity.divergences.map(_.row).distinct.filterNot(r => section.linesIterator.exists(_.startsWith(s"| $r")))
    assert(missing.isEmpty, missing.mkString("; "))
  }

  test("the driver's recordings are there, each named as cashier.feature names a scenario, recorded at 659c2d6b") {
    val feature = Files.readString(Parity.root.resolve("features/payments/cashier.feature")).linesIterator.map(_.trim)
      .collect { case l if l.startsWith("Scenario") => l.dropWhile(_ != ':').drop(1).trim }.toSet
    val driven  = recordings.filter(_.requirement.exists(_.startsWith("006/")))
    assert(driven.nonEmpty, "no driver recording")
    val unnamed = driven.map(_.scenario).filterNot(n => feature(n.takeWhile(_ != '[').trim) || n == "a key presented in a different header case")
    assert(unnamed.isEmpty, unnamed.mkString("; "))
    assertEquals(driven.flatMap(_.original.get("commit")).distinct, Vector("659c2d6b"))
  }
