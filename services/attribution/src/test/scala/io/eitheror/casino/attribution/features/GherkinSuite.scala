package io.eitheror.casino.attribution.features

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.attribution.it.{Kit, Reply, SharedKit}

import scala.concurrent.duration.*

/**
 * A feature file of features/attribution bound against the whole service (constitution VII), one suite per
 * file. A step no story has bound yet matches no definition, so its scenario fails naming the step and the
 * definition to paste: a feature that ran nothing has proved nothing. Every id a scenario names is its own,
 * since the database outlives scenarios.
 */
abstract class AttributionFeatures(file: String) extends GherkinSuite(s"../../features/attribution/$file") with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit
  protected var last: Reply = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()
    last = null; ids.clear()

  /** An id of this scenario's own for the name the feature gives it. */
  protected val ids = scala.collection.mutable.Map.empty[String, String]
  protected def id(name: String): String = ids.getOrElseUpdate(name, kit.fresh(name))
