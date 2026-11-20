package io.eitheror.casino.merchant.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Bytes, EndpointClients, Principal, RequestContext, Respond}
import com.thinkmorestupidless.ankka.sdk.SecretStore
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.merchant.{Json, Settings}
import io.eitheror.casino.merchant.hierarchy.*
import io.eitheror.casino.merchant.operators.{Access, Level, Scope, Target}
import io.eitheror.casino.merchant.security.Secrets

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What every endpoint is given: the settings, how an operator's token is verified (ankka-auth-oidc in
 * a deployment, a stand-in in the suites), the clock, and the service's clients.
 */
final class Ctx(val settings: Settings, val authenticate: () => Acl, val now: () => Instant, val clients: EndpointClients):
  val components = clients.componentClient
  val views      = clients.viewClient
  val secrets: Secrets = Ctx.secretsFor(clients.secrets, now)

  /** The settings as the service runs now: the deadline, the bound and the retention a suite may shorten. */
  def live: Settings = io.eitheror.casino.merchant.deposits.DepositRuntime.settings

  def subBrand(id: String): SubBrandState = components.forEventSourcedEntity(EntityId(id)).call(SubBrand.get).invoke()
  def holding(id: String): OrgState       = components.forEventSourcedEntity(EntityId(id)).call(Holding.get).invoke()
  def company(id: String): OrgState       = components.forEventSourcedEntity(EntityId(id)).call(Company.get).invoke()

  /** A sub-brand as a scope check reads it: with its company and holding, or none when it does not exist. */
  def subBrandTarget(s: SubBrandState): Target =
    Target(Option(s.holdingId).filter(_.nonEmpty), Option(s.companyId).filter(_.nonEmpty), Some(s.id))

object Ctx:
  /** One secrets cache per store, shared by every endpoint, so a write here is seen here at once. */
  private val caches = ConcurrentHashMap[SecretStore, Secrets]()
  def secretsFor(store: SecretStore, now: () => Instant): Secrets = caches.computeIfAbsent(store, s => Secrets(s, now = now))

/** Answers as the original wrote them: JSON, and a refusal `{error, message?}` with its code word. */
object Answers:
  def json(status: Int, body: JsonNode): Respond[Bytes] = Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status)
  def ok(body: JsonNode): Respond[Bytes]                = json(200, body)
  def created(body: JsonNode): Respond[Bytes]           = json(201, body)
  def noContent: Respond[Bytes]                         = Respond(Bytes("application/json", Array.emptyByteArray), 204)
  /** Akka HTTP's default entity for a bare `202`, which is what the original answered (its draft save). */
  def accepted: Respond[Bytes] =
    Respond(Bytes("text/plain; charset=UTF-8", "The request has been accepted for processing, but the processing has not been completed.".getBytes(UTF_8)), 202)

  def refuse(status: Int, error: String, message: String = "", fields: (String, Any)*): Respond[Bytes] =
    val o = Json.obj(("error" -> error) +: fields*)
    if message.nonEmpty then o.put("message", message)
    json(status, o)

  val scopeViolation: Respond[Bytes] = refuse(403, Routes.Refusal.ScopeViolation, Access.ScopeViolationMessage)

  /** A body that must be a JSON object, or the refusal naming why. */
  def body(text: String): Either[Respond[Bytes], ObjectNode] =
    Json.parseObject(text).toRight(refuse(400, "INVALID_REQUEST", "the body is not a JSON object"))

  /** An entity's refusal as the route answers it: a code word it named, else the generic one for its kind. */
  def fromError(e: CommandError, notFound: String = "NOT_FOUND"): Respond[Bytes] =
    val word = Option(e.message).filter(_.matches("[A-Z_]+"))
    e.code match
      case ErrorCode.NotFound   => refuse(404, word.getOrElse(notFound), e.message)
      case ErrorCode.Conflict   => refuse(409, word.getOrElse("CONFLICT"), e.message)
      case ErrorCode.BadRequest => refuse(400, word.getOrElse("INVALID_REQUEST"), e.message)
      case ErrorCode.Forbidden  => refuse(403, word.getOrElse("FORBIDDEN"), e.message)
      case _                    => refuse(503, "UNAVAILABLE", e.message)

  def guarded(f: => Respond[Bytes]): Respond[Bytes] =
    try f
    catch case e: CommandError => fromError(e)

/** An operator, as the payments operator ACL admitted them (decision 0050). */
final case class Operator(subject: String, roles: Set[String], scope: Scope):
  def actor: Actor = Actor.operator(subject)
  def permits(t: Target): Boolean = Access.permits(scope, t)
  def platform: Boolean = scope.level == Level.Platform

object Operator:
  def of(p: Principal): Operator =
    val level = p.claims.get(Routes.ScopeLevelClaim).flatMap(Level.from).getOrElse(Level.SubBrand)
    val id    = p.claims.get(Routes.ScopeIdClaim).getOrElse(if level == Level.Platform then "*" else "")
    Operator(p.subject, p.roles, Scope(level, id))

/**
 * Who reads the payments list, the summary and a payment (spec 030 H1): a payments operator, held to
 * their level as before, or an operator holding `brand_<slug>`, who reads the sub-brands naming those
 * brands and no other. `platform_admin` without a payments level holds every brand (`Routes.brandsOf`).
 * A sub-brand naming no brand is never a brand operator's.
 */
enum Reader:
  case Payments(op: Operator)
  case Brand(brands: Routes.BrandSet)

  def permits(s: SubBrandState, target: => Target): Boolean = this match
    case Payments(op) => op.permits(target)
    case Brand(set)   => s.exists && s.brand.exists(set.holds)

object Reader:
  /** Set by [[PaymentsReaders.acl]] on the principal it admits, over any claim of that name a token carries. */
  val AdmittedAs = "merchant_admitted_as"
  val AsPayments = "payments"
  val AsBrand    = "brand"

  def of(p: Principal): Reader =
    if p.claims.get(AdmittedAs).contains(AsBrand) then Brand(Routes.brandsOf(p)) else Payments(Operator.of(p))

/**
 * The ACL of the three payments reads (spec 030 H1), composed of `contract.access.Routes` and nothing
 * else: `Routes.paymentsOperator` admits exactly whom it admitted before; a staff token it refuses that
 * holds a `brand_<slug>` (`Routes.staff`, `Routes.brandsOf`) is admitted as a brand's reader; anyone
 * else gets the payments operator's refusal, as before.
 */
object PaymentsReaders:
  def acl(authenticate: => Acl): Acl =
    lazy val verifier = authenticate
    lazy val payments = Routes.paymentsOperator(verifier)
    lazy val staff    = Routes.staff(verifier)
    Acl.Authenticate { (context: RequestContext) =>
      decide(payments, context) match
        case AuthDecision.Allow(p) => AuthDecision.Allow(tagged(p, Reader.AsPayments))
        case refused @ AuthDecision.Forbidden(_) =>
          decide(staff, context) match
            case AuthDecision.Allow(p) if !Routes.brandsOf(p).isEmpty => AuthDecision.Allow(tagged(p, Reader.AsBrand))
            case _                                                    => refused
        case other => other
    }

  private def tagged(p: Principal, as: String): Principal = p.copy(claims = p.claims + (Reader.AdmittedAs -> as))

  private def decide(acl: Acl, context: RequestContext): AuthDecision = acl match
    case Acl.Authenticate(f) => f(context)
    case other               => AuthDecision.Forbidden(s"unexpected ACL $other")
