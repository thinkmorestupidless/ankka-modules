package io.eitheror.casino.reporting.features

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.reporting.it.{Kit, Reply, SharedKit}

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A feature file of features/reporting bound against the whole service (constitution VII), one suite per file, all on
 * the one kit of the test JVM. A scenario whose story is not built yet is reported ignored, naming the task that binds
 * it, never passed; a step no definition matches fails its scenario naming the step. Every scenario takes reporting
 * days of its own (`Kit.days`), since the database and the brand's days outlive scenarios.
 */
abstract class ReportingFeatures(file: String) extends GherkinSuite(s"${ReportingFeatures.Directory}/$file") with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit
  protected var last: Reply = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    last = null

object ReportingFeatures:
  /** The living features, from the service's directory, where a forked test runs. */
  val Directory = "../../features/reporting"

  /** Every scenario of `file` by name, as the feature writes it. */
  def scenarios(file: String): Vector[String] =
    Files.readAllLines(Paths.get(s"$Directory/$file")).asScala.toVector.map(_.trim).collect {
      case l if l.startsWith("Scenario Outline:") => l.stripPrefix("Scenario Outline:").trim
      case l if l.startsWith("Scenario:")         => l.stripPrefix("Scenario:").trim
    }

  /** Every scenario of `file`, left to the task that binds it. */
  def pending(task: String, file: String): Map[String, String] = scenarios(file).map(_ -> s"$task (pending)").toMap

/** features/reporting/bonus-and-providers.feature (US4), bound by T047. */
class BonusAndProvidersFeatures extends ReportingFeatures("bonus-and-providers.feature"):
  override protected def ranElsewhere = ReportingFeatures.pending("T047, US4", "bonus-and-providers.feature")

/** features/reporting/disputes.feature (US5), bound by T050. */
class DisputesFeatures extends ReportingFeatures("disputes.feature"):
  override protected def ranElsewhere = ReportingFeatures.pending("T050, US5", "disputes.feature")

/** features/reporting/day-end.feature (US6), bound by T058. */
class DayEndFeatures extends ReportingFeatures("day-end.feature"):
  override protected def ranElsewhere = ReportingFeatures.pending("T058, US6", "day-end.feature")

/** features/reporting/compliance-exports.feature (US7), bound by T061. */
class ComplianceFeatures extends ReportingFeatures("compliance-exports.feature"):
  override protected def ranElsewhere = ReportingFeatures.pending("T061, US7", "compliance-exports.feature")

/** features/reporting/predictions.feature (US8), bound by T064. */
class PredictionsFeatures extends ReportingFeatures("predictions.feature"):
  override protected def ranElsewhere = ReportingFeatures.pending("T064, US8", "predictions.feature")
