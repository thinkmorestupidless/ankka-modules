package io.eitheror.casino.merchant.audit

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord, PlatformAuditFact}
import io.eitheror.casino.merchant.hierarchy.*
import io.eitheror.casino.merchant.operators.{Operator, OperatorEvent}
import io.eitheror.casino.merchant.payments.{Payment, PaymentEvent}
import io.eitheror.casino.merchant.routing.{RoutingTree, RoutingTreeEvent}
import io.eitheror.casino.merchant.terminals.{Terminal, TerminalEvent}

import java.time.Instant

/** What one event says for the audit (FR-034): the action in the glossary's words, who, when, what changed. */
final case class Audited(action: String, actor: Actor, at: Instant, changes: Vector[AuditChange] = Vector.empty, outcome: String = "accepted",
    reason: Option[String] = None, brand: Option[String] = None, recordClass: String = "operator")

/**
 * One `audit_record` on `platform.audit` per change FR-034 names, from the events of the entity that
 * recorded it, in the contract's `AuditRecord` (spec 018 FR-001): `service = merchant`, the brand of a
 * payment's sub-brand or `payments` for the hierarchy and operators, the actor the command recorded.
 * No secret and no personal field: an operator is named by id, a credential's secrets by nothing.
 * One consumer per entity, since a consumer reads one entity's events.
 */
object AuditFacts:
  val Service = "merchant"

  def record(subjectKind: String, subjectId: String, sequence: Long, a: Audited): AuditRecord =
    val id = s"$Service/$subjectKind/$subjectId/$sequence"
    AuditRecord(recordId = id, brand = a.brand.getOrElse("payments"), service = Service, action = a.action, actorKind = a.actor.kind,
      actorId = a.actor.id, approverId = None, subjectKind = subjectKind, subjectId = subjectId, outcome = a.outcome, reasonCode = a.reason,
      changes = a.changes, correlationId = subjectId, causationId = s"$subjectKind/$subjectId/$sequence", recordClass = a.recordClass,
      occurredAt = a.at)

  private def change(field: String, after: String) = Vector(AuditChange(field, None, Some(after)))

  def org(kind: String, e: OrgEvent): Option[Audited] = e match
    case OrgEvent.Created(name, _, actor, at)       => Some(Audited(s"$kind created", actor, at, change("name", name)))
    case OrgEvent.StatusChanged(status, actor, at) => Some(Audited(s"$kind status changed", actor, at, change("status", status)))

  def subBrand(e: SubBrandEvent): Option[Audited] = e match
    case SubBrandEvent.Created(_, _, name, t, brand, actor, at) =>
      Some(Audited("sub-brand created", actor, at, change("name", name) ++ change("type", t) ++ brand.toVector.flatMap(change("brand", _))))
    case SubBrandEvent.StatusChanged(status, actor, at)       => Some(Audited("sub-brand status changed", actor, at, change("status", status)))
    case SubBrandEvent.KeyGenerated(key, actor)               => Some(Audited("merchant key generated", actor, key.createdAt, change("keyId", key.keyId)))
    case SubBrandEvent.KeyDisabled(keyId, actor, at)          => Some(Audited("merchant key disabled", actor, at, change("keyId", keyId)))
    case SubBrandEvent.CredentialRegistered(r, actor)         => Some(Audited("credential registered", actor, r.createdAt, change("credentialId", r.id) ++ change("provider", r.provider)))
    case SubBrandEvent.CredentialSecretsChanged(id, _, actor, at) => Some(Audited("credential secrets changed", actor, at, change("credentialId", id)))
    case SubBrandEvent.CredentialStatusChanged(id, s, actor, at)  => Some(Audited("credential status changed", actor, at, change("credentialId", id) ++ change("status", s)))
    case SubBrandEvent.CredentialDeleted(id, actor, at)       => Some(Audited("credential deleted", actor, at, change("credentialId", id)))

  def terminal(e: TerminalEvent): Option[Audited] = e match
    case TerminalEvent.Created(_, gw, scheme, name, _, _, _, _, actor, at) =>
      Some(Audited("terminal created", actor, at, change("gateway", gw) ++ change("scheme", scheme) ++ change("name", name)))
    case TerminalEvent.Activated(actor, at)                   => Some(Audited("terminal activated", actor, at))
    case TerminalEvent.Disabled(actor, at)                    => Some(Audited("terminal disabled", actor, at))
    case TerminalEvent.PropertiesChanged(props, secrets, actor, at) =>
      Some(Audited("terminal properties changed", actor, at, change("keys", (props.keys ++ secrets).toVector.sorted.mkString(","))))
    case TerminalEvent.CapabilityChanged(c, actor, at) =>
      Some(Audited("terminal capability changed", actor, at, change("supportsDeposit", c.supportsDeposit.toString) ++ change("supportsWithdrawal", c.supportsWithdrawal.toString)))
    case TerminalEvent.Deleted(actor, at) => Some(Audited("terminal deleted", actor, at))

  def routing(e: RoutingTreeEvent): Option[Audited] = e match
    case RoutingTreeEvent.DraftSaved(_, actor, at)      => Some(Audited("routing tree draft saved", actor, at))
    case RoutingTreeEvent.Deployed(v, actor, at)        => Some(Audited("routing tree deployed", actor, at, change("version", v.toString)))
    case RoutingTreeEvent.RolledBack(v, actor, at)      => Some(Audited("routing tree rolled back", actor, at, change("version", v.toString)))
    case RoutingTreeEvent.Retired(actor, at)            => Some(Audited("routing tree retired", actor, at))

  def operator(e: OperatorEvent): Option[Audited] = e match
    case OperatorEvent.Created(_, role, level, id, _, _, _, actor, at) =>
      Some(Audited("operator created", actor, at, change("role", role) ++ change("scopeLevel", level) ++ change("scopeId", id)))
    case OperatorEvent.Invited(actor, at)                   => Some(Audited("operator invited", actor, at))
    case OperatorEvent.Accepted(at)                         => Some(Audited("operator invitation accepted", Actor("operator", "self"), at))
    case OperatorEvent.ScopeChanged(level, id, _, actor, at) => Some(Audited("operator scope changed", actor, at, change("scopeLevel", level) ++ change("scopeId", id)))
    case OperatorEvent.EnabledMirrored(enabled, at)         => Some(Audited("operator enabled mirrored", Actor("system", "operator-sync"), at, change("enabled", enabled.toString)))
    case OperatorEvent.Removed(actor, at)                   => Some(Audited("operator removed", actor, at))

  def payment(e: PaymentEvent, brand: Option[String]): Option[Audited] =
    val flow = Actor.workflow
    def money(action: String, at: Instant, changes: Vector[AuditChange] = Vector.empty, outcome: String = "accepted", reason: Option[String] = None) =
      Some(Audited(action, flow, at, changes, outcome, reason, brand, "money"))
    e match
      case PaymentEvent.Initiated(o, status)            => money("payment initiated", o.createdAt, change("status", status) ++ change("amount", o.amount.toString))
      case PaymentEvent.Captured(v, credited, at)       => money("payment captured", at, change("credited", credited.toString) ++ v.toVector.flatMap(x => change("variance", x.kind)))
      case PaymentEvent.Credited(_, at)                 => money("payment credited", at)
      case PaymentEvent.CreditRefused(code, _, _, at)   => money("payment credit refused", at, outcome = "refused", reason = Some(code))
      case PaymentEvent.CreditRetried(by, at)           => Some(Audited("payment credit retried", Actor.operator(by), at, Vector.empty, "accepted", None, brand, "money"))
      case PaymentEvent.Failed(code, _, _, at)          => money("payment failed", at, reason = code)
      case PaymentEvent.Expired(at)                     => money("payment expired", at)
      case PaymentEvent.Waiting(_, _) | PaymentEvent.CreditAttempted(_, _) => None

/**
 * The shared shape of the seven: the entity's events in, its audit records out, over the contract's
 * base (hoisted from here for `ingest`, spec 006 T003).
 */
abstract class AuditConsumer[E](subjectKind: String) extends audit.AuditConsumer[E]:
  protected def audited(event: E): Option[Audited]
  protected def audit(event: E): Option[AuditRecord] =
    audited(event).map(AuditFacts.record(subjectKind, messageContext.subject, messageContext.sequenceNumber, _))

abstract class AuditCompanion[C <: Consumer[E, PlatformAuditFact], E](id: String, source: ChangeSource[E])
    extends audit.AuditCompanion[C, E](id, source)

final class HoldingAudit extends AuditConsumer[OrgEvent]("holding"):
  protected def audited(e: OrgEvent) = AuditFacts.org("holding", e)
object HoldingAudit extends AuditCompanion[HoldingAudit, OrgEvent]("holding-audit", ChangeSource.eventsOf(Holding)):
  def create(ctx: ConsumerContext) = new HoldingAudit

final class CompanyAudit extends AuditConsumer[OrgEvent]("company"):
  protected def audited(e: OrgEvent) = AuditFacts.org("company", e)
object CompanyAudit extends AuditCompanion[CompanyAudit, OrgEvent]("company-audit", ChangeSource.eventsOf(Company)):
  def create(ctx: ConsumerContext) = new CompanyAudit

final class SubBrandAudit extends AuditConsumer[SubBrandEvent]("sub-brand"):
  protected def audited(e: SubBrandEvent) = AuditFacts.subBrand(e)
object SubBrandAudit extends AuditCompanion[SubBrandAudit, SubBrandEvent]("sub-brand-audit", ChangeSource.eventsOf(SubBrand)):
  def create(ctx: ConsumerContext) = new SubBrandAudit

final class TerminalAudit extends AuditConsumer[TerminalEvent]("terminal"):
  protected def audited(e: TerminalEvent) = AuditFacts.terminal(e)
object TerminalAudit extends AuditCompanion[TerminalAudit, TerminalEvent]("terminal-audit", ChangeSource.eventsOf(Terminal)):
  def create(ctx: ConsumerContext) = new TerminalAudit

final class RoutingAudit extends AuditConsumer[RoutingTreeEvent]("routing-tree"):
  protected def audited(e: RoutingTreeEvent) = AuditFacts.routing(e)
object RoutingAudit extends AuditCompanion[RoutingAudit, RoutingTreeEvent]("routing-tree-audit", ChangeSource.eventsOf(RoutingTree)):
  def create(ctx: ConsumerContext) = new RoutingAudit

final class OperatorAudit extends AuditConsumer[OperatorEvent]("operator"):
  protected def audited(e: OperatorEvent) = AuditFacts.operator(e)
object OperatorAudit extends AuditCompanion[OperatorAudit, OperatorEvent]("operator-audit", ChangeSource.eventsOf(Operator)):
  def create(ctx: ConsumerContext) = new OperatorAudit

/** A payment's record names its sub-brand's brand, read from the payment (the first event carries it). */
final class PaymentAudit(context: ConsumerContext) extends AuditConsumer[PaymentEvent]("payment"):
  protected def audited(e: PaymentEvent) =
    val brand = e match
      case PaymentEvent.Initiated(o, _) => o.brand
      case _ => context.componentClient.forEventSourcedEntity(EntityId(messageContext.subject)).call(Payment.get).invoke().opened.flatMap(_.brand)
    AuditFacts.payment(e, brand)
object PaymentAudit extends AuditCompanion[PaymentAudit, PaymentEvent]("payment-audit", ChangeSource.eventsOf(Payment)):
  def create(ctx: ConsumerContext) = new PaymentAudit(ctx)

object AuditConsumers:
  val descriptors: Seq[com.thinkmorestupidless.ankka.core.ComponentDescriptor] = Seq(HoldingAudit.descriptor, CompanyAudit.descriptor, SubBrandAudit.descriptor, TerminalAudit.descriptor,
    RoutingAudit.descriptor, OperatorAudit.descriptor, PaymentAudit.descriptor)
