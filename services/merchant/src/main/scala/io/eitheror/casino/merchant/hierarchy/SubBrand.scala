package io.eitheror.casino.merchant.hierarchy

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.MerchantCodecs

import java.time.Instant

/** A sub-brand's types (FR-001). A casino's names the brand project it belongs to. */
object SubBrandType:
  val Payments = "Payments"
  val Casino   = "Casino"
  val Combined = "Combined"
  val all      = Set(Payments, Casino, Combined)

/** A merchant key: never the raw value, only its digest (FR-004). */
final case class MerchantKey(keyId: String, name: String, digest: String, active: Boolean, createdAt: Instant)

/**
 * A payment provider's credential (FR-006): its secrets are in the secret store under
 * `credential/<id>`; the record names only which secret keys are set, so a listing and the usable
 * rule (FR-008) need no store read.
 */
final case class CredentialRecord(id: String, provider: String, name: String, environment: String, scheme: Option[String],
    status: String, secretKeys: Vector[String], createdAt: Instant, deleted: Boolean = false):
  def active: Boolean = status == CredentialRecord.Active && !deleted

object CredentialRecord:
  val Active   = "active"
  val Disabled = "disabled"

final case class SubBrandState(
    id: String,
    companyId: String = "",
    holdingId: String = "",
    name: String = "",
    subBrandType: String = "",
    status: String = "",
    brand: Option[String] = None,
    keys: Vector[MerchantKey] = Vector.empty,
    credentials: Vector[CredentialRecord] = Vector.empty,
    createdAt: Option[Instant] = None
):
  def exists: Boolean = createdAt.isDefined

  def liveCredentials: Vector[CredentialRecord] = credentials.filterNot(_.deleted)

  /** The credential a provider is called with: the first active one with secrets (FR-008). */
  def usableCredential(provider: String): Option[CredentialRecord] =
    usable(provider).headOption

  /** Every usable credential for a provider; a second is unreachable, and the status says so (Edge cases). */
  def usable(provider: String): Vector[CredentialRecord] =
    liveCredentials.filter(c => c.provider.equalsIgnoreCase(provider) && c.active && c.secretKeys.nonEmpty)

  def credentialNamed(provider: String, name: String): Option[CredentialRecord] =
    liveCredentials.find(c => c.provider.equalsIgnoreCase(provider) && c.name == name)

enum SubBrandEvent:
  case Created(companyId: String, holdingId: String, name: String, subBrandType: String, brand: Option[String], actor: Actor, at: Instant)
  case StatusChanged(status: String, actor: Actor, at: Instant)
  case KeyGenerated(key: MerchantKey, actor: Actor)
  case KeyDisabled(keyId: String, actor: Actor, at: Instant)
  case CredentialRegistered(record: CredentialRecord, actor: Actor)
  /** The secret keys that are now set: names, never values (SC-007). */
  case CredentialSecretsChanged(id: String, secretKeys: Vector[String], actor: Actor, at: Instant)
  case CredentialStatusChanged(id: String, status: String, actor: Actor, at: Instant)
  case CredentialDeleted(id: String, actor: Actor, at: Instant)

final case class CreateSubBrand(companyId: String, holdingId: String, name: String, subBrandType: String, brand: Option[String],
    actor: Actor, at: Instant)
final case class ChangeSubBrandStatus(status: String, actor: Actor, at: Instant)
final case class GenerateKey(keyId: String, name: String, digest: String, actor: Actor, at: Instant)
final case class DisableKey(keyId: String, actor: Actor, at: Instant)
final case class RegisterCredential(id: String, provider: String, name: String, environment: String, scheme: Option[String],
    secretKeys: Vector[String], actor: Actor, at: Instant)
/** The answer to a registration: the record, and whether this call made it or found it (FR-006). */
final case class Registered(record: CredentialRecord, created: Boolean)
final case class ChangeCredentialSecrets(id: String, secretKeys: Vector[String], actor: Actor, at: Instant)
final case class ChangeCredentialStatus(id: String, status: String, actor: Actor, at: Instant)
final case class DeleteCredential(id: String, actor: Actor, at: Instant)

object SubBrandCodecs:
  given Serializer[SubBrandState]          = MerchantCodecs.serializer[SubBrandState]("sub-brand-state")
  given Serializer[SubBrandEvent]          = MerchantCodecs.serializer[SubBrandEvent]("sub-brand-event")
  given Serializer[CreateSubBrand]         = MerchantCodecs.serializer[CreateSubBrand]("create-sub-brand")
  given Serializer[ChangeSubBrandStatus]   = MerchantCodecs.serializer[ChangeSubBrandStatus]("change-sub-brand-status")
  given Serializer[GenerateKey]            = MerchantCodecs.serializer[GenerateKey]("generate-key")
  given Serializer[MerchantKey]            = MerchantCodecs.serializer[MerchantKey]("merchant-key")
  given Serializer[DisableKey]             = MerchantCodecs.serializer[DisableKey]("disable-key")
  given Serializer[RegisterCredential]     = MerchantCodecs.serializer[RegisterCredential]("register-credential")
  given Serializer[Registered]             = MerchantCodecs.serializer[Registered]("registered")
  given Serializer[ChangeCredentialSecrets] = MerchantCodecs.serializer[ChangeCredentialSecrets]("change-credential-secrets")
  given Serializer[ChangeCredentialStatus] = MerchantCodecs.serializer[ChangeCredentialStatus]("change-credential-status")
  given Serializer[DeleteCredential]       = MerchantCodecs.serializer[DeleteCredential]("delete-credential")

import SubBrandCodecs.given

/**
 * A sub-brand (FR-001, FR-004, FR-006): its parents, type, status and brand; its merchant keys by
 * digest; its credentials' records. A raw key is minted by the caller and only its digest is sent here,
 * so the journal never holds one (FR-004); a credential's secrets are written to the store by the caller
 * and only their names recorded (FR-006, SC-007).
 */
final class SubBrand(context: EventSourcedEntityContext) extends EventSourcedEntity[SubBrandState, SubBrandEvent]:

  def emptyState: SubBrandState = SubBrandState(context.entityId)

  def applyEvent(event: SubBrandEvent): SubBrandState = SubBrand.fold(currentState, event)

  private def missing[R]: Effect[R] = effects.error("sub-brand not found", ErrorCode.NotFound)

  def create(cmd: CreateSubBrand): Effect[SubBrandState] =
    val s = currentState
    val brandRule =
      if !SubBrandType.all(cmd.subBrandType) then Some(s"'${cmd.subBrandType}' is not a sub-brand type")
      else if cmd.subBrandType != SubBrandType.Payments && cmd.brand.forall(_.isBlank) then Some(s"a ${cmd.subBrandType} sub-brand names its brand")
      else if cmd.subBrandType == SubBrandType.Payments && cmd.brand.isDefined then Some("a Payments sub-brand names no brand")
      else None
    brandRule match
      case Some(problem) => effects.error(problem, ErrorCode.BadRequest)
      case None if !s.exists =>
        effects.persist(SubBrandEvent.Created(cmd.companyId, cmd.holdingId, cmd.name.trim, cmd.subBrandType, cmd.brand, cmd.actor, cmd.at))
          .thenReplyState
      case None if s.companyId == cmd.companyId && s.name == cmd.name.trim && s.subBrandType == cmd.subBrandType && s.brand == cmd.brand =>
        effects.reply(s)
      case None => effects.error(s"${s.id} exists with other content", ErrorCode.Conflict)

  def changeStatus(cmd: ChangeSubBrandStatus): Effect[SubBrandState] =
    if !currentState.exists then missing
    else if !OrgStatus.all(cmd.status) then effects.error(s"'${cmd.status}' is not a status", ErrorCode.BadRequest)
    else if currentState.status == cmd.status then effects.reply(currentState)
    else effects.persist(SubBrandEvent.StatusChanged(cmd.status, cmd.actor, cmd.at)).thenReplyState

  def generateKey(cmd: GenerateKey): Effect[MerchantKey] =
    if !currentState.exists then missing
    else
      currentState.keys.find(_.keyId == cmd.keyId) match
        case Some(k) => effects.reply(k)
        case None =>
          val key = MerchantKey(cmd.keyId, cmd.name, cmd.digest, active = true, cmd.at)
          effects.persist(SubBrandEvent.KeyGenerated(key, cmd.actor)).thenReply(_ => key)

  def disableKey(cmd: DisableKey): Effect[SubBrandState] =
    currentState.keys.find(_.keyId == cmd.keyId) match
      case None                 => effects.error(s"key ${cmd.keyId} not found", ErrorCode.NotFound)
      case Some(k) if !k.active => effects.reply(currentState)
      case Some(_)              => effects.persist(SubBrandEvent.KeyDisabled(cmd.keyId, cmd.actor, cmd.at)).thenReplyState

  def registerCredential(cmd: RegisterCredential): Effect[Registered] =
    if !currentState.exists then missing
    else
      currentState.liveCredentials.find(c => c.provider.equalsIgnoreCase(cmd.provider) && c.name == cmd.name && c.environment == cmd.environment) match
        case Some(existing) => effects.reply(Registered(existing, created = false))
        case None if currentState.credentialNamed(cmd.provider, cmd.name).isDefined =>
          effects.error(s"a ${cmd.provider} credential named ${cmd.name} exists in another environment", ErrorCode.Conflict)
        case None =>
          val record = CredentialRecord(cmd.id, cmd.provider.toUpperCase, cmd.name, cmd.environment, cmd.scheme, CredentialRecord.Active,
            cmd.secretKeys.sorted, cmd.at)
          effects.persist(SubBrandEvent.CredentialRegistered(record, cmd.actor)).thenReply(_ => Registered(record, created = true))

  private def credential[R](id: String)(f: CredentialRecord => Effect[R]): Effect[R] =
    currentState.liveCredentials.find(_.id == id).fold(effects.error[R](s"credential $id not found", ErrorCode.NotFound))(f)

  def changeCredentialSecrets(cmd: ChangeCredentialSecrets): Effect[SubBrandState] =
    credential(cmd.id) { c =>
      if c.secretKeys == cmd.secretKeys.sorted then effects.reply(currentState)
      else effects.persist(SubBrandEvent.CredentialSecretsChanged(cmd.id, cmd.secretKeys.sorted, cmd.actor, cmd.at)).thenReplyState
    }

  def changeCredentialStatus(cmd: ChangeCredentialStatus): Effect[SubBrandState] =
    credential(cmd.id) { c =>
      if c.status == cmd.status then effects.reply(currentState)
      else effects.persist(SubBrandEvent.CredentialStatusChanged(cmd.id, cmd.status, cmd.actor, cmd.at)).thenReplyState
    }

  def deleteCredential(cmd: DeleteCredential): Effect[SubBrandState] =
    credential(cmd.id)(_ => effects.persist(SubBrandEvent.CredentialDeleted(cmd.id, cmd.actor, cmd.at)).thenReplyState)

  def get: ReadOnlyEffect[SubBrandState] = effects.reply(currentState)

object SubBrand extends EventSourcedEntity.Companion[SubBrand, SubBrandState, SubBrandEvent](
      componentId = ComponentId("sub-brand"),
      stateSerializer = SubBrandCodecs.given_Serializer_SubBrandState,
      eventSerializer = SubBrandCodecs.given_Serializer_SubBrandEvent
    ):
  def create(context: EventSourcedEntityContext) = new SubBrand(context)

  val create                  = command("create")(_.create)
  val changeStatus            = command("change-status")(_.changeStatus)
  val generateKey             = command("generate-key")(_.generateKey)
  val disableKey              = command("disable-key")(_.disableKey)
  val registerCredential      = command("register-credential")(_.registerCredential)
  val changeCredentialSecrets = command("change-credential-secrets")(_.changeCredentialSecrets)
  val changeCredentialStatus  = command("change-credential-status")(_.changeCredentialStatus)
  val deleteCredential        = command("delete-credential")(_.deleteCredential)
  val get                     = query("get")(_.get)

  def fold(s: SubBrandState, event: SubBrandEvent): SubBrandState =
    def withCredential(id: String)(f: CredentialRecord => CredentialRecord) =
      s.copy(credentials = s.credentials.map(c => if c.id == id then f(c) else c))
    event match
      case SubBrandEvent.Created(company, holding, name, t, brand, _, at) =>
        s.copy(companyId = company, holdingId = holding, name = name, subBrandType = t, status = OrgStatus.Active, brand = brand, createdAt = Some(at))
      case SubBrandEvent.StatusChanged(status, _, _)               => s.copy(status = status)
      case SubBrandEvent.KeyGenerated(key, _)                       => s.copy(keys = s.keys :+ key)
      case SubBrandEvent.KeyDisabled(keyId, _, _)                   => s.copy(keys = s.keys.map(k => if k.keyId == keyId then k.copy(active = false) else k))
      case SubBrandEvent.CredentialRegistered(record, _)            => s.copy(credentials = s.credentials :+ record)
      case SubBrandEvent.CredentialSecretsChanged(id, keys, _, _)   => withCredential(id)(_.copy(secretKeys = keys))
      case SubBrandEvent.CredentialStatusChanged(id, status, _, _)  => withCredential(id)(_.copy(status = status))
      case SubBrandEvent.CredentialDeleted(id, _, _)                => withCredential(id)(_.copy(deleted = true))
