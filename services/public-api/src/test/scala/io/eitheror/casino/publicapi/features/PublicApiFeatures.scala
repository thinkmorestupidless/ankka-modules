package io.eitheror.casino.publicapi.features

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.publicapi.it.{Kit, Reply, SharedKit}

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A feature file of features/public-api bound against the whole service (constitution VII), one suite per file, on the
 * one kit of the test JVM, as reporting's. A scenario whose story is not built yet is reported ignored, naming the task
 * that binds it, never passed; a step no definition matches fails its scenario naming the step.
 */
abstract class PublicApiFeatures(file: String) extends GherkinSuite(s"${PublicApiFeatures.Directory}/$file") with LogCapturing:
  override val munitTimeout: Duration = 10.minutes

  protected def kit: Kit = SharedKit.kit
  protected var last: Reply = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    last = null

object PublicApiFeatures:
  /** The living features, from the service's directory, where a forked test runs. */
  val Directory = "../../features/public-api"

  /** Every scenario of `file` by name, as the feature writes it. */
  def scenarios(file: String): Vector[String] =
    Files.readAllLines(Paths.get(s"$Directory/$file")).asScala.toVector.map(_.trim).collect {
      case l if l.startsWith("Scenario Outline:") => l.stripPrefix("Scenario Outline:").trim
      case l if l.startsWith("Scenario:")         => l.stripPrefix("Scenario:").trim
    }

  /** Every scenario of `file`, left to the task that binds it. */
  def pending(task: String, file: String): Map[String, String] = scenarios(file).map(_ -> s"$task (pending)").toMap
