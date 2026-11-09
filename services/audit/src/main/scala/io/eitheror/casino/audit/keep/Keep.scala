package io.eitheror.casino.audit.keep

import io.eitheror.casino.contract.facts.AuditRecord

/** Whether the shared store keeps a record (spec 018 FR-008a, research R3). */
enum Decision:
  case Keep, PassOver

object Keep:
  /** The actor kinds no person stands behind. */
  val Unattended: Set[String] = Set("service", "system")

  /**
   * A money event no person caused stays in the brand's ledger: a record is passed over when its
   * category is `money`, its actor is a service or the system, and it was not refused. Everything
   * else is kept: every person's action, every refusal, every record of another category.
   */
  def decide(category: String, actorKind: String, outcome: String): Decision =
    if category == "money" && Unattended(actorKind) && outcome != "refused" then Decision.PassOver
    else Decision.Keep

  def decide(record: AuditRecord): Decision = decide(record.recordClass, record.actorKind, record.outcome)
