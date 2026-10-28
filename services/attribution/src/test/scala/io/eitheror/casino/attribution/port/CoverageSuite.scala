package io.eitheror.casino.attribution.port

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.Recording

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * tests/port/attribution's recordings: each loads, each was made on two runs of the original, the
 * driver's are named as features/attribution names its scenarios, and every divergence `Parity` marks is
 * a row of plan.md's *Divergences*.
 */
class CoverageSuite extends munit.FunSuite with LogCapturing:
  private val paths = Parity.recordings(Parity.directory)
  private lazy val recordings = paths.map(p => Recording.load(p).fold(e => throw AssertionError(e), identity))

  test("every marked divergence is a row of plan.md's Divergences") {
    val plan    = Files.readString(Parity.root.resolve("specs/012-affiliate-protocol/plan.md"))
    val section = plan.substring(plan.indexOf("## Divergences"))
    val missing = Parity.divergences.map(_.row).distinct.filterNot(r => section.linesIterator.exists(_.startsWith(s"| $r")))
    assert(missing.isEmpty, missing.mkString("; "))
  }

  test("every recording loads") {
    val broken = paths.flatMap(p => Recording.load(p).left.toOption)
    assert(broken.isEmpty, broken.mkString("\n"))
  }

  test("every recording was made on two runs of the original") {
    val once = recordings.filter(_.runs < 2).map(_.scenario)
    assert(once.isEmpty, s"recorded on one run: ${once.mkString("; ")}")
  }

  test("the suite's four scenarios are there") {
    val names = recordings.map(_.scenario).toSet
    val want = Seq("TestScenario17_AffiliateGate3_CPAPipeline", "TestScenario15_St8Wallet_RestrictedOnlyRoundEarnsNoAffiliateGGR",
      "TestScenario15_St8Wallet_SplitRoundEarnsRealGGROnly", "TestScenario15_St8Wallet_ConvertedMoneyRoundEarnsNoAffiliateGGR")
    assertEquals(want.filterNot(names), Seq.empty)
  }

  test("the driver's recordings are there, each named as features/attribution names a scenario") {
    val dir = Parity.root.resolve("features/attribution")
    val scenarios = Files.list(dir).iterator().asScala.filter(_.toString.endsWith(".feature")).flatMap(f =>
      Files.readString(f).linesIterator.map(_.trim).collect { case l if l.startsWith("Scenario") => l.dropWhile(_ != ':').drop(1).trim })
      .toSet
    // The suite's four name a requirement too; the driver's are named by their feature scenario.
    val driven = recordings.filter(r => r.requirement.exists(_.startsWith("012/")) && !r.scenario.startsWith("TestScenario"))
    assert(driven.size >= 8, s"${driven.size} driver recordings (T035 records eight)")
    val unnamed = driven.map(_.scenario).filterNot(n => scenarios(n.takeWhile(_ != '[').trim))
    assert(unnamed.isEmpty, unnamed.mkString("; "))
  }

