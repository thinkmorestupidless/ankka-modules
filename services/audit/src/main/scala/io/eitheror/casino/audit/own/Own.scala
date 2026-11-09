package io.eitheror.casino.audit.own

import io.eitheror.casino.audit.Wiring
import io.eitheror.casino.audit.ids.Ids
import io.eitheror.casino.audit.ingest.IngestCalls
import io.eitheror.casino.audit.registry.Bind
import io.eitheror.casino.audit.stored.{Stored, StoredChange, Visibility}

import java.time.Instant
import java.util.UUID

/**
 * The audit's own records (research R5): queries, exports, re-identifications, grants and sweeps, each
 * a record in the store as it is done. The member of staff is named by a pseudonym under the brand the
 * record concerns, or `installation`, as every person in the store is.
 */
object Own:
  val Installation = "installation"

  /** The pseudonym of a member of staff, issued once under the brand and bound in the registry. */
  def staff(calls: IngestCalls, brand: String, subject: String): String =
    val keys      = Wiring.requiredKeys
    val pseudonym = calls.issue(Ids.registryId(keys.registry, brand, "operator", subject))
    calls.bind(pseudonym, Bind(brand, "operator", subject)): Unit
    pseudonym

  /**
   * Writes one record of the audit's own, its actor the staff member's pseudonym (from [[staff]]); its id is
   * new, so no two are the same record.
   */
  def record(calls: IngestCalls, brand: String, action: String, actor: String, subjectKind: String, subject: String,
      outcome: String, reasonCode: Option[String], changes: Vector[StoredChange], now: Instant,
      visibility: String = Visibility.Installation, actorKind: String = "operator"): Stored =
    val s = Stored.own(UUID.randomUUID().toString, brand, action, actorKind, actor, subjectKind, subject,
      outcome, reasonCode, changes, Wiring.requiredKeys.registry, now, visibility)
    calls.keep(s): Unit
    s

  def change(field: String, value: String): StoredChange = StoredChange(field, None, Some(value))
