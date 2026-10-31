package io.eitheror.casino.payouts.facts

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits}
import io.eitheror.casino.payouts.limits.Pair
import io.eitheror.casino.payouts.limits.LimitsEvent.LimitsSet
import io.eitheror.casino.payouts.player.PlayerEvent
import io.eitheror.casino.payouts.request.{Actor, RequestEvent, RequestState, Source, Unanswered}
import io.eitheror.casino.payouts.request.RequestEvent.*

import java.time.Instant

/**
 * The request's, the player's and the limits' events as facts and audit records (contracts/facts.md), pure.
 * A fact names no destination, address, name or reason's text; an audit record names a personal field, never
 * its value. The ids are the event's, so a redelivered event is the same message.
 */
object Facts:
  final case class Published[F](fact: F, id: String)

  // ── casino.withdrawals ──────────────────────────────────────────────────

  def withdrawalId(requestId: String, sequence: Long): String = s"withdrawal-$requestId-$sequence"

  /**
   * The family an event is, or none. `state` is the request as it stands: the player, the paid amount, the
   * currency and the terminal are fixed when it opens, and the reference is the last the gateway gave.
   */
  def withdrawal(requestId: String, sequence: Long, event: RequestEvent, state: RequestState): Option[Published[f.CasinoWithdrawalsFact]] =
    val player   = state.playerId
    val paid     = MinorUnits.unsafe(state.paidAmount)
    val terminal = state.terminal.map(_.id)
    val gateway  = state.terminal.map(_.gateway)
    val fact: Option[f.CasinoWithdrawalsFact] = event match
      case e: Opened =>
        Some(f.WithdrawalRequested(e.playerId, requestId, MinorUnits.unsafe(e.paidAmount), Currency.unsafe(e.currency), e.terminal.id, e.at,
          Some(e.terminal.gateway)))
      case e: Approved => Some(f.WithdrawalApproved(player, requestId, paid, state.currencyOf, e.operatorId, e.at, terminal, gateway))
      case e: Completed =>
        Some(f.WithdrawalCompleted(player, requestId, paid, state.currencyOf, e.at, terminal, gateway, e.reference.orElse(state.providerReference)))
      case e: Cancelled =>
        Some(f.WithdrawalCancelled(player, requestId, paid, state.currencyOf, e.by.wire, e.at, terminal, gateway, e.reasonCode))
      case e: Rejected =>
        Some(f.WithdrawalCancelled(player, requestId, paid, state.currencyOf, Actor.Operator.wire, e.at, terminal, gateway, Some("rejected")))
      case e: Failed =>
        Some(f.WithdrawalFailed(player, requestId, paid, state.currencyOf, e.reasonCode, e.at, terminal, gateway, state.providerReference))
      case e: Reversed =>
        Some(f.WithdrawalReversed(player, requestId, paid, state.currencyOf, e.reasonCode, e.at, terminal, gateway,
          e.reference.orElse(state.providerReference)))
      case _ => None
    fact.map(Published(_, withdrawalId(requestId, sequence)))

  // ── platform.audit ──────────────────────────────────────────────────────

  /** Who an audit record names: the player, an operator by id, `psp-gateway`, or the workflow. */
  final case class Who(kind: String, id: String)
  private val Gateway  = Who("service", "psp-gateway")
  private val Workflow = Who("system", "payouts")

  private def change(field: String, before: Option[String], after: Option[String]) = f.AuditChange(field, before, after)
  private def to(field: String, after: String)                                    = f.AuditChange(field, None, Some(after))

  /** A field whose value is personal or free text: named, with no value. */
  private def named(field: String) = f.AuditChange(field, None, None)

  private def record(brand: String, id: String, action: String, who: Who, subjectKind: String, subjectId: String,
      changes: Vector[f.AuditChange], reasonCode: Option[String], recordClass: String, at: Instant): Published[f.AuditRecord] =
    Published(f.AuditRecord(recordId = id, brand = brand, service = "payouts", action = action, actorKind = who.kind,
      actorId = who.id, approverId = None, subjectKind = subjectKind, subjectId = subjectId, outcome = "accepted",
      reasonCode = reasonCode, changes = changes, correlationId = id, causationId = id, recordClass = recordClass,
      occurredAt = at), id)

  private def settledBy(source: Source, operatorId: Option[String]): Who = source match
    case Source.Operator => Who("operator", operatorId.getOrElse("operator"))
    case Source.Poll | Source.Workflow => Workflow
    case _               => Gateway

  /** One record per audited event of a request (contracts/facts.md), with its amounts and its status after. */
  def requestAudit(brand: String, requestId: String, sequence: Long, event: RequestEvent, state: RequestState): Option[Published[f.AuditRecord]] =
    val player = Who("player", state.playerId)
    def status(s: String)                     = to("status", s)
    def note(n: Option[?]): Vector[f.AuditChange] = n.map(_ => named("note")).toVector
    val found: Option[(String, Who, Vector[f.AuditChange], Option[String], Instant)] = event match
      case e: Opened =>
        Some(("payouts.requested", Who("player", e.playerId),
          Vector(to("amount", e.amount.toString), to("paidAmount", e.paidAmount.toString), to("currency", e.currency),
            to("terminal", e.terminal.id), status("pending_review")), None, e.at))
      case e: Cancelled =>
        Some(("payouts.cancelled", if e.by == Actor.Player then player else Workflow, Vector(status("cancelled")), e.reasonCode, e.at))
      case e: Approved => Some(("payouts.approved", Who("operator", e.operatorId), Vector(status("approved")), None, e.at))
      case e: Rejected =>
        Some(("payouts.rejected", Who("operator", e.operatorId), Vector(status("rejected")) ++ note(e.reason), Some("rejected"), e.at))
      case e: AwaitingManualPayout =>
        Some(("payouts.awaiting_manual_payout", Workflow, Vector(status("awaiting_manual_payout")), Some(e.reason), e.at))
      case e: PayoutInitiated =>
        Some(("payouts.initiated", if Unanswered.All(e.answer) then Workflow else Gateway, Vector(to("answer", e.answer), to("payoutStatus", "initiated")) ++
          e.reference.map(to("providerReference", _)), None, e.at))
      case OutcomeRecorded(r) if r.source != Source.Operator =>
        Some(("payouts.outcome_recorded", if r.source == Source.Poll || r.source == Source.Workflow then Workflow else Gateway,
          Vector(to("source", r.source.wire), to("answer", r.answer)) ++ r.reference.map(to("providerReference", _)) ++ note(r.note),
          r.reasonCode, r.at))
      case e: MarkedNeedsReview =>
        Some(("payouts.needs_review", Workflow, Vector(to("payoutStatus", "needs_review")), Some(e.reason), e.at))
      case e: Completed =>
        Some(("payouts.completed", settledBy(e.source, e.operatorId),
          Vector(status("completed"), to("paidAmount", state.paidAmount.toString)) ++ e.reference.map(to("providerReference", _)) ++ note(e.note),
          None, e.at))
      case e: Failed =>
        Some(("payouts.failed", settledBy(e.source, e.operatorId), Vector(status("failed")) ++ note(e.note), Some(e.reasonCode), e.at))
      case e: Reversed =>
        Some(("payouts.reversed", Gateway, Vector(change("status", Some("completed"), Some("reversed"))) ++
          e.reference.map(to("providerReference", _)), Some(e.reasonCode), e.at))
      case e: ReferenceMismatched =>
        Some(("payouts.reference_mismatched", Gateway, Vector(to("providerReference", e.reference)), None, e.at))
      case _ => None
    found.map { (action, who, changes, reasonCode, at) =>
      val currency = Option.when(state.currency.nonEmpty && !event.isInstanceOf[Opened])(to("currency", state.currency)).toVector
      record(brand, s"audit/request/$requestId/$sequence", action, who, "withdrawal", requestId, changes ++ currency, reasonCode, "money", at)
    }

  /** A player's destinations, saved, made the default or deleted: the id, never the holder or the IBAN. */
  def playerAudit(brand: String, playerId: String, sequence: Long, event: PlayerEvent): Option[Published[f.AuditRecord]] =
    val found: Option[(String, Vector[f.AuditChange], Instant)] = event match
      case PlayerEvent.DestinationSaved(d) =>
        Some(("payouts.destination_saved", Vector(to("destination", d.id), named("holder"), named("iban"), to("default", d.default.toString)),
          d.createdAt))
      case PlayerEvent.DefaultSet(id, at)         => Some(("payouts.destination_default_set", Vector(to("destination", id)), at))
      case PlayerEvent.DestinationDeleted(id, at) => Some(("payouts.destination_deleted", Vector(to("destination", id)), at))
      case _                                      => None
    found.map { (action, changes, at) =>
      record(brand, s"audit/player/$playerId/$sequence", action, Who("player", playerId), "player", playerId, changes, None, "player", at)
    }

  /** Every limit that changed, per currency, before and after (FR-005a). */
  def limitsAudit(brand: String, sequence: Long, event: LimitsSet): Published[f.AuditRecord] =
    def of(m: Map[String, Pair], c: String) = m.getOrElse(c, Pair())
    val changes = (event.previous.keySet ++ event.next.keySet).toVector.sorted.flatMap { c =>
      val (b, a) = (of(event.previous, c), of(event.next, c))
      Vector(("weekly", b.weekly, a.weekly), ("monthly", b.monthly, a.monthly)).collect {
        case (period, before, after) if before != after => change(s"limits.$c.$period", before.map(_.toString), after.map(_.toString))
      }
    }
    record(brand, s"audit/limits/$brand/$sequence", "payouts.limits_set", Who("operator", event.operatorId), "brand", brand, changes,
      None, "operator", event.at)
