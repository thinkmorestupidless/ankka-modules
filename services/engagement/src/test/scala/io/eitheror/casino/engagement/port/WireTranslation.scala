package io.eitheror.casino.engagement.port

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.port.Recording

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/**
 * contracts/wire.md: what a recorded step of the original is to this service. Engagement's own routes are sent to it
 * (`Ours`, `Stream`); a step of another service that changes what engagement knows is made the way that service tells
 * this one, as a fact (`StandIn`); the reset route this service does not serve is the deposit-day timer (`Reset`); every
 * other step is answered as recorded (`Answered`). The recordings name the brand `spinvibe`; the kit's brand replaces it.
 */
object WireTranslation:
  val Brand = "spinvibe"

  /** Who a route of ours is sent as. */
  enum Caller:
    case Nobody, Operator, Player

  /** The facts another service's step stands for, read from its request and its recorded answer. */
  enum StandIn:
    case Registered(email: Option[String], country: Option[String], currency: Option[String], dateOfBirth: Option[String],
        givenName: Option[String], familyName: Option[String])
    case Verified
    case LoggedIn
    case Limit(limitType: String, value: Option[Long], pending: Boolean)
    case CoolingOff(until: Option[String])
    case SelfExcluded(permanent: Boolean)
    case DocumentSubmitted(key: String)
    case DocumentReviewed(key: String, decision: String, reason: Option[String], kycStatus: Option[String])
    case KycStatus(status: String)
    case Deposit(transactionId: String, cents: Long, currency: String)
    case Tier(tier: String)
    case Profile(fields: Vector[String])
    case MessageCreated(messageId: String)
    case MessageRead(messageId: String)
    case KycShown(status: String)
    case Payment(twin: String, cents: Long, currency: String, player: String)
    case Captured(twin: String)
    case Duplicate(suspected: Boolean)
    case SigningKey(key: String)

  enum Step:
    case Ours(method: String, path: String, caller: Caller)
    case Stream(path: String, caller: Caller)
    case Webhook(path: String)
    case StandFor(player: Option[String], standIn: StandIn)
    case Reset
    case Answered

  private val Limits    = """/v1/casino/(?:rg/spinvibe/([^/]+)|spinvibe/players/([^/]+)/rg)/limits/([A-Z_]+)""".r
  private val Cooling   = """/v1/casino/rg/spinvibe/([^/]+)/cooling-off""".r
  private val Exclude   = """/v1/casino/spinvibe/players/([^/]+)/self-exclude""".r
  private val Permanent = """/v1/casino/rg/spinvibe/([^/]+)/permanent-exclusion""".r
  private val Documents = """/v1/casino/spinvibe/players/([^/]+)/kyc/documents""".r
  private val Reviewed  = """/v1/casino/spinvibe/players/([^/]+)/kyc/documents/([^/]+)/(approve|reject)""".r
  private val Status    = """/v1/casino/spinvibe/players/([^/]+)/kyc/(status|verify|reject)""".r
  private val Tier      = """/v1/casino/spinvibe/players/([^/]+)/vip-tier""".r
  private val Profile   = """/v1/casino/backoffice/spinvibe/players/([^/]+)/profile""".r
  private val Messages  = """/v1/casino/backoffice/spinvibe/players/([^/]+)/messages""".r
  private val Read      = """/v1/casino/players/spinvibe/messages/([^/]+)/read""".r
  private val PaymentTwin = """/twins/(casino-payments/[^/]+)""".r
  private val Concept     = """/twins/(casino-payments/[^/]+)/concept""".r
  private val PlayerTwin  = """/twins/casino-spinvibe/([^/]+)""".r
  private val BrandTwin   = """/twins/casino/[^/]+""".r

  private def cents(amount: Option[String]): Long =
    amount.flatMap(a => scala.util.Try(BigDecimal(a)).toOption).map(a => (a * 100).toLong).getOrElse(0L)

  private def text(n: Option[JsonNode], field: String): Option[String] =
    n.flatMap(b => Option(b.get(field))).filterNot(_.isNull).map(_.asText).filter(_.nonEmpty)

  /** The step as wire.md has it; `current` is the player the recording last registered or signed in. */
  def of(step: Recording.Step, slug: String, current: Option[String]): Step =
    val r       = step.request
    val path    = r.path.takeWhile(_ != '?')
    val ok      = step.response.status / 100 == 2
    val body    = r.body
    val answer  = step.response.body
    val ours    = r.path.replace(s"/$Brand", s"/$slug")
    val caller  = if r.headers.contains("Authorization") then Caller.Operator else Caller.Nobody
    def stand(p: Option[String], s: StandIn) = if ok then Step.StandFor(p.orElse(current), s) else Step.Answered
    (r.method, path) match
      case (_, p) if p.startsWith(s"/v1/casino/crm-config/$Brand") => Step.Ours(r.method, ours, caller)
      case ("POST", p) if p == s"/v1/casino/customerio/webhook/$Brand"    => Step.Webhook(ours)
      case ("POST", p) if p == s"/v1/casino/backoffice/$Brand/agent-stream/ticket" => Step.Ours("POST", ours, Caller.Operator)
      case ("GET", p) if p == s"/v1/casino/backoffice/$Brand/agent-stream"          => Step.Stream(ours, Caller.Nobody)
      case ("GET", "/v1/stream") =>
        Step.Stream("/v1/stream", if r.headers.get("Authorization").exists(_.contains("operatorToken")) then Caller.Operator else Caller.Player)
      case ("GET", "/v1/presence") => Step.Ours("GET", s"/v1/casino/backoffice/$slug/presence", Caller.Operator)
      case ("POST", p) if p == s"/internal/v1/casino/$Brand/daily-deposit-count/reset" => Step.Reset
      case ("POST", p) if p == s"/v1/casino/players/$Brand/register" =>
        if !ok then Step.Answered
        else Step.StandFor(text(answer, "playerId"), StandIn.Registered(text(body, "email").filterNot(_.startsWith("{")),
          text(body, "country"), text(body, "currency"), text(body, "dateOfBirth"), text(body, "firstName"), text(body, "lastName")))
      case ("POST", p) if p == s"/v1/casino/players/$Brand/verify-email" => stand(None, StandIn.Verified)
      case ("PUT", p) if p.startsWith(s"/admin/realms/casino-$Brand/users/") && text(body, "emailVerified").contains("true") =>
        stand(None, StandIn.Verified)
      case ("POST", p) if p == s"/v1/casino/players/$Brand/login" => stand(text(answer, "playerId"), StandIn.LoggedIn)
      case (m @ ("PUT" | "DELETE"), Limits(a, b, tpe)) =>
        val pending = answer.exists(_.path("pendingChanges").elements.asScala.exists(_.path("limitType").asText == tpe))
        stand(Option(a).orElse(Option(b)), StandIn.Limit(tpe, if m == "DELETE" then None else text(body, "value").flatMap(_.toLongOption), pending))
      case ("PUT", Cooling(p))   => stand(Some(p), StandIn.CoolingOff(text(body, "until")))
      case ("POST", Exclude(p))  => stand(Some(p), StandIn.SelfExcluded(text(body, "period").contains("permanent")))
      case ("PUT", Permanent(p)) => stand(Some(p), StandIn.SelfExcluded(permanent = true))
      case ("POST", Documents(p)) => stand(Some(p), StandIn.DocumentSubmitted(text(body, "key").getOrElse("")))
      case ("PATCH", Reviewed(p, key, act)) =>
        stand(Some(p), StandIn.DocumentReviewed(URLDecoder.decode(key, UTF_8), if act == "approve" then "approved" else "rejected",
          text(body, "reason"), text(answer, "kycStatus")))
      case (_, Status(p, act)) =>
        stand(Some(p), StandIn.KycStatus(act match
          case "verify" => "verified"
          case "reject" => "rejected"
          case _        => text(body, "status").getOrElse("")))
      case ("POST", p) if p == s"/internal/v1/casino/$Brand/notify/deposit" =>
        stand(text(body, "playerId"), StandIn.Deposit(text(body, "transactionId").getOrElse(""), cents(text(body, "amount")),
          text(body, "currency").getOrElse("EUR")))
      case ("PUT", Tier(p))       => stand(Some(p), StandIn.Tier(text(body, "tier").getOrElse("")))
      case ("PATCH", Profile(p))  => stand(Some(p), StandIn.Profile(body.toVector.flatMap(_.fieldNames.asScala)))
      case ("POST", Messages(p))  => stand(Some(p), StandIn.MessageCreated(text(answer, "notificationId").getOrElse("")))
      case ("POST", Read(m))      => stand(None, StandIn.MessageRead(m))
      // kyc's answer to a review is stale (the status it held before deciding); its read that follows is not.
      case ("GET", Documents(p))  => text(answer, "kycStatus").fold(Step.Answered)(s => stand(Some(p), StandIn.KycShown(s)))
      // The Go suites write the original's twins: a deposit's, captured by a concept; the player's flags; the brand's key.
      case ("POST", Concept(t)) if text(body, "conceptId").contains("payment.captured") => stand(None, StandIn.Captured(t))
      case ("POST", PaymentTwin(t)) if text(body, "payment_type").contains("deposit") =>
        stand(None, StandIn.Payment(t, cents(text(body, "amount")), text(body, "currency").getOrElse("EUR"),
          text(body, "customer_ref").map(_.dropWhile(_ != ':').drop(1)).getOrElse("")))
      case ("POST", PlayerTwin(p)) if body.exists(_.has("suspected_duplicate")) =>
        stand(Some(p), StandIn.Duplicate(text(body, "suspected_duplicate").contains("true")))
      case ("POST", BrandTwin()) if text(body, "crm_config").isDefined =>
        text(body, "crm_config").flatMap(c => scala.util.Try(com.fasterxml.jackson.databind.ObjectMapper().readTree(c)).toOption)
          .flatMap(c => Option(c.get("webhookSigningKey"))).map(_.asText).fold(Step.Answered)(k => stand(None, StandIn.SigningKey(k)))
      case _                      => Step.Answered

  /** A path of ours back in the recording's terms, so captures and answers read alike. */
  def back(text: String, slug: String): String = text.replace(s"/$slug", s"/$Brand").replace(s"_seed_$slug", s"_seed_$Brand")
