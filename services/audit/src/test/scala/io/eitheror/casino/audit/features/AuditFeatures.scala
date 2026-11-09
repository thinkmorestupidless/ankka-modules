package io.eitheror.casino.audit.features

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.audit.ids.Ids
import io.eitheror.casino.audit.it.{Kit, SharedKit}
import io.eitheror.casino.audit.registry.RegistryKey
import io.eitheror.casino.audit.store.Records
import io.eitheror.casino.audit.stored.{Source, Stored}
import io.eitheror.casino.contract.facts.AuditRecord

import scala.concurrent.duration.*

/**
 * A feature file of features/audit bound against the whole service (constitution VII), one suite per
 * file. A step no story has bound yet matches no definition, so its scenario fails naming the step:
 * pending is red, never skipped. Every id a scenario names is its own, since the database outlives
 * scenarios.
 */
abstract class AuditFeatures(file: String) extends GherkinSuite(s"../../features/audit/$file") with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.rewire()
    kit.revoked = Set.empty
    ids.clear()

  /** An id of this scenario's own for the name the feature gives it. */
  protected val ids = scala.collection.mutable.Map.empty[String, String]
  protected def id(name: String): String = ids.getOrElseUpdate(name, kit.fresh(name))

  /** The stored record's key for a record from `source`. */
  protected def keyOf(source: Source, record: AuditRecord): String =
    s"${source.name}/${Ids.digest(RegistryKey.required(kit.secrets), source.project, record.recordId)}"

  /** The stored record, waited for within the ingest bound (SC-003: 30 seconds). */
  protected def stored(source: Source, record: AuditRecord): Stored =
    kit.eventually(30.seconds)(kit.views.forView(Records).get(keyOf(source, record)))

  protected def absent(source: Source, record: AuditRecord): Boolean =
    kit.views.forView(Records).get(keyOf(source, record)).isEmpty
