package io.eitheror.casino.ingest.features

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.ingest.it.{Kit, RawKey, Reply, SharedKit}

import scala.concurrent.duration.*

/**
 * A feature file of features/payments bound against the whole service (constitution VII): the cashier is
 * `FakeMerchant`, a sub-brand a key issued through the broker, and every id a scenario names is its own,
 * since the database outlives scenarios.
 */
abstract class IngestFeatures(file: String) extends GherkinSuite(s"../../features/payments/$file") with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit
  protected var key: RawKey     = null
  protected var last: Reply     = null
  protected var replies         = Vector.empty[Reply]

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()
    key = null; last = null; replies = Vector.empty; ids.clear()

  /** A request id of this scenario's own for the name the feature gives it. */
  protected val ids = scala.collection.mutable.Map.empty[String, String]
  protected def id(name: String): String = ids.getOrElseUpdate(name, s"$name-${java.util.UUID.randomUUID().toString.take(8)}")
