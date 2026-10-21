package io.eitheror.casino.pspgateway.it

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.pspgateway.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.jdk.CollectionConverters.*

/** How a fake receiver answers an outcome: as the contract's table lists them, or not at all. */
enum Scripted:
  case Ok, Pending, MissingToken, Forbidden, BadRequest, Silent

/** One outcome a fake receiver was sent: its body, the two signature headers, and the status it answered (`0`: none). */
final case class Delivered(key: String, body: String, signature: Option[String], timestamp: Option[String], answered: Int = 0)

/** One answer of a script: a status, or no answer for `silentMs` (the original's receiver's `__script`). */
final case class ScriptStep(status: Int, silentMs: Long = 0)

/**
 * `merchant` as the gateway sees it (contracts/merchant.md §3, §4): the credential, terminal and brand
 * listing routes from settable maps, logging every read; the capture and fail routes recording each
 * call under `<paymentId>/<outcome>`, verifying the signature as 004's `Hmac.verify` does when the
 * credential has a `webhookSecret`, and answering as scripted.
 */
final class FakeMerchant(bind: Int = 0) extends FakeServer(bind):
  private val credentials = ConcurrentHashMap[(String, String), Map[String, String]]()
  private val properties  = ConcurrentHashMap[String, Map[String, String]]()
  private val terminals   = ConcurrentHashMap[String, Vector[ObjectNode]]()
  private val outcomes    = java.util.concurrent.ConcurrentLinkedQueue[Delivered]()
  @volatile var answer: Scripted = Scripted.Ok
  @volatile var credentialsUnavailable: Boolean = false
  private val scripts = ConcurrentHashMap[String, Vector[ScriptStep]]()

  /** The payment's outcomes answered in turn by `steps`, the last holding, over `answer`; a new script starts again. */
  def script(paymentId: String, steps: Vector[ScriptStep]): Unit = scripts.put(paymentId, steps): Unit

  def credential(subBrandId: String, provider: String, values: Map[String, String]): Unit =
    credentials.put(subBrandId -> provider.toUpperCase, values): Unit

  /** A credential's values changed in the store (US5's rotation): the next read sees them. */
  def rotate(subBrandId: String, provider: String, values: Map[String, String]): Unit = credential(subBrandId, provider, values)

  def removeCredential(subBrandId: String, provider: String): Unit = credentials.remove(subBrandId -> provider.toUpperCase): Unit

  def terminal(id: String, props: Map[String, String]): Unit = properties.put(id, props): Unit

  def brandTerminals(brand: String, listed: Vector[ObjectNode]): Unit = terminals.put(brand, listed): Unit

  /** The credential reads, as `<subBrandId>/<PROVIDER>`, in order. */
  def credentialReads: Vector[String] =
    received.collect { case r if r.path.contains("/psp-credentials/") =>
      val p = r.path.split('/'); s"${p(4)}/${p(6)}" }

  def delivered: Vector[Delivered] = outcomes.asScala.toVector
  def deliveredTo(paymentId: String): Vector[Delivered] = delivered.filter(_.key.startsWith(s"$paymentId/"))

  def reset(): Unit =
    credentials.clear(); properties.clear(); terminals.clear(); outcomes.clear(); scripts.clear(); clearReceived()
    answer = Scripted.Ok; credentialsUnavailable = false

  protected def route(r: Received): Answer =
    r.path.split('/').toList.drop(1) match
      case "internal" :: "v1" :: "sub-brands" :: sb :: "psp-credentials" :: provider :: Nil if r.method == "GET" =>
        if credentialsUnavailable then Answer(503, """{"error":"UNAVAILABLE"}""")
        else Option(credentials.get(sb -> provider)) match
          case Some(values) => Answer.ok(Json.obj("provider" -> provider, "credentials" -> Json.objOf(values)))
          case None         => Answer(404, """{"error":"NOT_FOUND"}""")
      case "internal" :: "v1" :: "terminals" :: id :: "properties" :: Nil if r.method == "GET" =>
        Option(properties.get(id)).fold(Answer(404, """{"error":"NOT_FOUND"}"""))(p => Answer.ok(Json.obj("properties" -> Json.objOf(p))))
      case "internal" :: "v1" :: "brands" :: brand :: "terminals" :: Nil if r.method == "GET" =>
        val listed = Option(terminals.get(brand)).getOrElse(Vector.empty)
        Answer.ok(Json.obj("terminals" -> Json.arr(listed*)))
      case "v1" :: "payments" :: paymentId :: (verb @ ("capture" | "fail")) :: Nil if r.method == "POST" =>
        outcome(paymentId, verb, r)
      case _ => Answer(404, """{"error":"NO_ROUTE"}""")

  private def outcome(paymentId: String, verb: String, r: Received): Answer =
    val secret = credentials.asScala.values.flatMap(_.get("webhookSecret")).headOption
    val sig    = r.headers.get("x-psp-signature")
    val ts     = r.headers.get("x-psp-timestamp")
    val verified = (secret, sig, ts) match
      case (None, None, None) => None
      case (Some(_), None, _) | (Some(_), _, None) => Some(Answer(401, """{"error":"missing_token"}"""))
      case (Some(s), Some(sent), Some(at)) =>
        val fresh = at.toLongOption.exists(t => math.abs(Instant.now().getEpochSecond - t) <= 300)
        if fresh && FakeMerchant.sign(s, r.body) == sent.toLowerCase then None else Some(Answer(403, """{"error":"invalid_token"}"""))
      case _ => None
    verified.orElse(scripted(paymentId, verb, r, sig, ts)).getOrElse {
      answer match
        case Scripted.Silent => Thread.sleep(12_000); Answer(200, """{"ok":true}""")
        case a =>
          val answered = a match
            case Scripted.Ok           => Answer(200, """{"ok":true}""")
            case Scripted.Pending      => Answer(503, """{"error":"OUTCOME_PENDING"}""")
            case Scripted.MissingToken => Answer(401, """{"error":"missing_token"}""")
            case Scripted.Forbidden    => Answer(403, """{"error":"invalid_token"}""")
            case _                     => Answer(400, """{"error":"INVALID_REQUEST"}""")
          outcomes.add(Delivered(s"$paymentId/$verb", r.body, sig, ts, answered.status))
          answered
    }

  /** The script's next step for the payment, if it has one, recorded as answered (`0` for silence) before it is sent. */
  private def scripted(paymentId: String, verb: String, r: Received, sig: Option[String], ts: Option[String]): Option[Answer] =
    var taken = Option.empty[ScriptStep]
    scripts.computeIfPresent(paymentId, (_, steps) => { taken = steps.headOption; if steps.size > 1 then steps.tail else steps }): Unit
    taken.map { step =>
      val silent = step.silentMs > 0
      outcomes.add(Delivered(s"$paymentId/$verb", r.body, sig, ts, if silent then 0 else step.status))
      if silent then Thread.sleep(step.silentMs)
      if silent || step.status / 100 == 2 then Answer(if silent then 200 else step.status, """{"ok":true}""")
      else Answer(step.status, s"""{"error":"SCRIPTED_${step.status}"}""")
    }

object FakeMerchant:
  def sign(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(body.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

/**
 * A brand's `payouts` (contracts/payouts.md): the outcome route, per brand, recording each call under
 * `<requestId>/<outcome>` and answering as scripted; `403` once its grant is revoked.
 */
final class FakePayouts(bind: Int = 0) extends FakeServer(bind):
  private val outcomes = java.util.concurrent.ConcurrentLinkedQueue[(String, Delivered)]()
  @volatile var answer: Scripted = Scripted.Ok
  @volatile var revoked: Set[String] = Set.empty

  def delivered(brand: String): Vector[Delivered] = outcomes.asScala.toVector.collect { case (`brand`, d) => d }

  def reset(): Unit = { outcomes.clear(); clearReceived(); answer = Scripted.Ok; revoked = Set.empty }

  protected def route(r: Received): Answer =
    r.path.split('/').toList.drop(1) match
      case "v1" :: "casino" :: "brands" :: brand :: "withdrawals" :: requestId :: "outcome" :: Nil if r.method == "POST" =>
        if revoked(brand) then Answer(403, """{"error":"FORBIDDEN"}""")
        else answer match
          case Scripted.Silent => Thread.sleep(12_000); Answer(200, "{}")
          case a =>
            val word = Json.parse(r.body).flatMap(n => Json.text(n)("outcome")).getOrElse("?")
            val answered = a match
              case Scripted.Ok      => Answer(200, """{"recorded":true}""")
              case Scripted.Pending => Answer(503, """{"error":"UNAVAILABLE"}""")
              case Scripted.Forbidden | Scripted.MissingToken => Answer(403, """{"error":"FORBIDDEN"}""")
              case _                => Answer(400, """{"error":"INVALID_REQUEST"}""")
            outcomes.add(brand -> Delivered(s"$requestId/$word", r.body, None, None, answered.status))
            answered
      case _ => Answer(404, """{"error":"NO_ROUTE"}""")
