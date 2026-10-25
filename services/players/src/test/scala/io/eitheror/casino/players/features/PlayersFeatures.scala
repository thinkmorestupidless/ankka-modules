package io.eitheror.casino.players.features

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.players.it.{Kit, SharedKit}

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A feature file of features/players bound against the whole service (constitution VII). Until its story
 * binds a scenario, the scenario is reported ignored naming the task that will (`pending`), never passed:
 * a story's task removes its scenarios from `pending` as it binds them.
 */
abstract class PlayersFeatures(file: String) extends GherkinSuite(s"../../features/players/$file") with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit

  /** The scenarios still unbound, each with the task that binds it. */
  protected def pending: Map[String, String]

  override protected def ranElsewhere: Map[String, String] = pending

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()

object PlayersFeatures:
  /** Every scenario name of a feature file, as the parser names its pickles. */
  def scenarios(file: String): Vector[String] =
    Files.readAllLines(Paths.get(s"../../features/players/$file")).asScala.toVector.map(_.trim)
      .collect { case l if l.startsWith("Scenario:") || l.startsWith("Scenario Outline:") => l.dropWhile(_ != ':').drop(1).trim }

  /** Every scenario of `file` pending on `task`. */
  def all(file: String, task: String): Map[String, String] = scenarios(file).map(_ -> s"$task (pending)").toMap

class SessionsFeatures extends PlayersFeatures("sessions.feature"):
  protected def pending = PlayersFeatures.all("sessions.feature", "T039 or T045")

class ProfileFeatures extends PlayersFeatures("profile.feature"):
  protected def pending = PlayersFeatures.all("profile.feature", "T050")

class StatusFeatures extends PlayersFeatures("status.feature"):
  protected def pending = PlayersFeatures.all("status.feature", "T057")

class DuplicatesFeatures extends PlayersFeatures("duplicates.feature"):
  protected def pending = PlayersFeatures.all("duplicates.feature", "T063")

class ErasureFeatures extends PlayersFeatures("erasure.feature"):
  protected def pending = PlayersFeatures.all("erasure.feature", "T068")
