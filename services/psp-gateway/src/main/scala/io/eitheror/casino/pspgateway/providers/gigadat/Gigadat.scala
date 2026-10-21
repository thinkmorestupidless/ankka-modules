package io.eitheror.casino.pspgateway.providers.gigadat

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.Json.{text, textOr}
import io.eitheror.casino.pspgateway.http.Raw
import io.eitheror.casino.pspgateway.money.Render
import io.eitheror.casino.pspgateway.providers.*
import io.eitheror.casino.pspgateway.sessions.{Outcome, Provider}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Gigadat (Interac): CPI deposits and ETO/ACH payouts through one token call, CAD only, and one
 * webhook route for both, told apart by the body's `type` (`gigadat/adapter.go`, `webhook.go`,
 * `handler/gigadat.go`). The original authenticates no webhook (its OQ-8); the port admits one on the
 * reference and the cross-check of type and amount against the session (research R8.4, *new*).
 *
 * `providerData`: `gigadatTxnId`, the id Gigadat assigned in the token answer.
 */
object Gigadat extends Deposits with Payouts:
  val provider: Provider = Provider.Gigadat

  /** Nothing generated at claim for either side (both traits declare it). */
  override def generate(@scala.annotation.unused random: java.security.SecureRandom): Map[String, String] = Map.empty

  /** The terminal keys every Gigadat call needs (`adapter.go:133-141`, `handler/gigadat.go:232-249`). */
  val required: Vector[String] = Vector("campaignId", "accessToken", "securityToken", "siteUrl", "environment")

  private val Cad = "CAD"

  /** What the token call answered: Gigadat's token and its own transaction id. */
  final case class Token(token: String, transactionId: String)

  /** A webhook body's fields (`webhook.go:9-15`). `cookie` is read and never trusted (OQ-8). */
  final case class Notice(kind: String, rcode: String, amount: String, cookie: String, transactionId: String):
    def recognised: Boolean = Seq(kind, rcode, amount, cookie, transactionId).exists(_.nonEmpty)
    override def toString: String = s"Notice($kind, $rcode, $amount)"

  object Notice:
    val empty: Notice = Notice("", "", "", "", "")

  // ── Requests ───────────────────────────────────────────────────────────────────────────────────

  /** Digits only: Gigadat wants ten for a Canadian mobile (`adapter.go:29-34`). */
  def digits(mobile: String): String = mobile.filter(c => c >= '0' && c <= '9')

  private def missing(credential: Credential): Vector[String] = required.filter(credential.get(_).isEmpty)

  private def basic(credential: Credential): (String, String) =
    val pair = s"${credential.get("accessToken").getOrElse("")}:${credential.get("securityToken").getOrElse("")}"
    "Authorization" -> s"Basic ${Digests.base64(pair.getBytes(UTF_8))}"

  /** `sandbox:true` only for a sandbox credential; absent otherwise (`adapter.go:69-77`). */
  private def sandbox(credential: Credential): Option[Boolean] = credential.get("environment").filter(_ == "sandbox").map(_ => true)

  /** Go's `FirstName + " " + LastName`: always sent, a lone space when both are empty. */
  private def name(first: Option[String], last: Option[String]): String = s"${first.getOrElse("")} ${last.getOrElse("")}"

  /** The token body in the original's field order (`adapter.go:37-55`); `None` and empty fields are left out. */
  def tokenBody(
      amount: Long, currency: Currency, credential: Credential, kind: String, transactionId: String, userId: Option[String],
      email: Option[String] = None, mobile: Option[String] = None, name: String, province: Option[String] = None,
      userIp: Option[String] = None, account: Option[String] = None, institution: Option[String] = None,
      transit: Option[String] = None
  ): ObjectNode =
    def present(o: Option[String]) = o.map(_.trim).filter(_.nonEmpty)
    Json.obj(
      "amount"        -> Render.dollars(amount, currency),
      "campaign"      -> credential.get("campaignId").getOrElse(""),
      "currency"      -> Cad,
      "email"         -> present(email),
      "mobile"        -> present(mobile.map(digits)),
      "name"          -> name,
      "province"      -> present(province),
      "sandbox"       -> sandbox(credential),
      "site"          -> credential.get("siteUrl").getOrElse(""),
      "transactionId" -> transactionId,
      "type"          -> kind,
      "userId"        -> userId.getOrElse(""),
      "userIp"        -> present(userIp),
      "acct"          -> present(account),
      "fi"            -> present(institution),
      "transit"       -> present(transit)
    )

  private def tokenUrl(base: String, credential: Credential) =
    s"$base/api/payment-token/${credential.get("campaignId").getOrElse("")}"

  /**
   * The CPI token call (`adapter.go:115-160`): CAD only, the five keys required, `transactionId` the
   * payment id, `userId` the player's id, mobile digits only, province sent when given. A currency other
   * than CAD or a missing key is refused before any call, naming the keys, never their values.
   */
  def tokenRequest(charge: Charge, credential: Credential, customer: Customer, base: String): Either[Initiation, Outbound] =
    val absent = missing(credential)
    if charge.currency.code != Cad then
      Left(Initiation.Refused("unsupported_currency", s"gigadat: unsupported currency \"${charge.currency.code}\" — only CAD is supported"))
    else if absent.nonEmpty then
      Left(Initiation.Refused("missing_credentials", s"gigadat: missing required credentials (${absent.mkString(", ")})"))
    else
      val body = tokenBody(
        charge.amount, charge.currency, credential, "CPI", charge.paymentId, charge.customerId,
        email = customer.email, mobile = customer.mobile, name = name(customer.firstName, customer.lastName),
        province = customer.province, userIp = customer.ip
      )
      Right(Outbound.json(tokenUrl(base, credential), body, basic(credential)))

  /** The token answer: exactly `200` (`adapter.go:101`), a token and Gigadat's transaction id. */
  def token(answered: Answered): Either[String, Token] =
    if answered.status != 200 then Left(s"token endpoint returned ${answered.status}")
    else
      answered.json.filter(_.isObject) match
        case None => Left("token answer unreadable")
        case Some(node) =>
          val data = Option(node.get("data")).filter(_.isObject)
          (node.text("token"), data.flatMap(_.text("transactionId"))) match
            case (Some(t), Some(id)) => Right(Token(t, id))
            case _                   => Left("token answer without a token or transaction id")

  /** The player's page, and the payout's first `webflow` GET (`adapter.go:167`, `:275`). */
  def redirect(base: String, transaction: String, token: String): String = s"$base/webflow?transaction=$transaction&token=$token"

  /** The payout's finalising GET, which moves the money (`adapter.go:291`). */
  def finalise(base: String, transaction: String, token: String): String =
    s"$base/webflow/deposit?transaction=$transaction&token=$token"

  def initiate(charge: Charge, credential: Credential, customer: Customer, base: String, call: Call): Initiation =
    tokenRequest(charge, credential, customer, base) match
      case Left(refused) => refused
      case Right(out) =>
        call(out) match
          case Left(CallFailure.TimedOut)       => Initiation.Unavailable("gigadat: token call timed out")
          case Left(CallFailure.Unreachable(r)) => Initiation.Unavailable(s"gigadat: token call failed: $r")
          case Right(answered) =>
            token(answered) match
              case Left(reason) => Initiation.Unavailable(s"gigadat: $reason")
              case Right(t) =>
                Initiation.Redirected(redirect(base, t.transactionId, t.token), Some(t.transactionId),
                  Map("gigadatTxnId" -> t.transactionId))

  // ── Payouts ────────────────────────────────────────────────────────────────────────────────────

  /**
   * The ETO or ACH token call (`adapter.go:221-266`, `handler/gigadat.go:209-249`): ETO with email and
   * mobile, ACH with account, institution and transit; CAD only; `transactionId` the request id. What
   * this refuses is refused before any call, so the money provably has not moved.
   */
  def payoutRequest(withdrawal: Withdrawal, credential: Credential, base: String): Either[PayoutAnswer, Outbound] =
    val kind   = withdrawal.method.trim.toUpperCase
    val d      = withdrawal.destination.get
    val absent = missing(credential)
    if kind != "ETO" && kind != "ACH" then Left(PayoutAnswer.Rejected("unsupported_method"))
    else if withdrawal.currency.code != Cad then Left(PayoutAnswer.Rejected("unsupported_currency"))
    else if absent.nonEmpty then Left(PayoutAnswer.Rejected("missing_credentials"))
    else
      val holder = name(d("firstName"), d("lastName"))
      val body =
        if kind == "ETO" then
          tokenBody(withdrawal.amount, withdrawal.currency, credential, kind, withdrawal.requestId, withdrawal.customerId,
            email = d("email"), mobile = d("mobile"), name = holder, userIp = d("ip"))
        else
          tokenBody(withdrawal.amount, withdrawal.currency, credential, kind, withdrawal.requestId, withdrawal.customerId,
            name = holder, userIp = d("ip"), account = d("account"), institution = d("institution"), transit = d("transit"))
      Right(Outbound.json(tokenUrl(base, credential), body, basic(credential)))

  /** The two `webflow` GETs that follow a payout's token, each under the same Basic credential. */
  def webflow(base: String, token: Token, credential: Credential): Vector[Outbound] =
    Vector(redirect(base, token.transactionId, token.token), finalise(base, token.transactionId, token.token))
      .map(Outbound.get(_, basic(credential)))

  /**
   * Token, then the two GETs, each to answer exactly `200`; any failure after the request is built is
   * in doubt (`handler/gigadat.go:273-277`). Initiated under the request id (`:291-294`).
   */
  def payout(withdrawal: Withdrawal, credential: Credential, base: String, call: Call): PayoutAnswer =
    def reached(out: Outbound): Either[String, Answered] =
      call(out) match
        case Left(CallFailure.TimedOut)       => Left(s"${out.describe} timed out")
        case Left(CallFailure.Unreachable(r)) => Left(s"${out.describe} failed: $r")
        case Right(a)                         => Right(a)
    def ok(out: Outbound): Either[String, Unit] =
      reached(out).flatMap(a => if a.status == 200 then Right(()) else Left(s"${out.describe} returned ${a.status}"))
    payoutRequest(withdrawal, credential, base) match
      case Left(answer) => answer
      case Right(out) =>
        // A 401/403 to the token call is unauthorised (PayoutAnswer.unauthorised); one to a later webflow
        // step stays in doubt, since Gigadat already holds the token that makes the payout.
        reached(out) match
          case Right(answered) if PayoutAnswer.unauthorised(answered.status).nonEmpty =>
            PayoutAnswer.unauthorised(answered.status).get
          case first =>
            val done =
              for
                answered <- first
                t        <- token(answered)
                _        <- webflow(base, t, credential).foldLeft[Either[String, Unit]](Right(()))((acc, o) => acc.flatMap(_ => ok(o)))
              yield ()
            done.fold(reason => PayoutAnswer.InDoubt(s"gigadat payout: $reason"), _ => PayoutAnswer.Initiated(Some(withdrawal.requestId)))

  // ── Webhooks ───────────────────────────────────────────────────────────────────────────────────

  /**
   * The body in each shape Gigadat may send (`webhook.go:38-71`): a bare JSON array of `{key, value}`,
   * a form whose `data` is that array, or a flat form. The arrays are tried first.
   */
  def notice(body: String): Notice =
    def pairs(text: String): Option[Notice] =
      Json.parse(text).filter(_.isArray).flatMap { arr =>
        val entries = arr.elements.asScala.toVector
        if !entries.forall(_.isObject) then None
        else
          val m = entries.map(e => e.textOr("key", "") -> e.textOr("value", "")).toMap
          Some(fromMap(m.get)).filter(_.recognised)
      }
    if body.isEmpty then Notice.empty
    else
      pairs(body).getOrElse {
        val form = io.eitheror.casino.pspgateway.http.Form.decode(body)
        form.get("data").filter(_.nonEmpty).flatMap(pairs).getOrElse(fromMap(form.get))
      }

  private def fromMap(get: String => Option[String]): Notice =
    def f(k: String) = get(k).getOrElse("")
    Notice(f("type"), f("rcode"), f("amount"), f("cookie"), f("transactionId"))

  private val failures: Map[String, String] = Map(
    "STATUS_ABORTED1"  -> "Payment aborted by player",
    "STATUS_EXPIRED"   -> "Payment session expired",
    "STATUS_REJECTED"  -> "Payment rejected by bank",
    "STATUS_REJECTED1" -> "Payment rejected",
    "STATUS_FAILED"    -> "Payment failed"
  )

  /**
   * A status and rcode as the original maps them (`webhook.go:97-128`): `STATUS_SUCCESS` succeeded;
   * the five failures failed with the rcode (the status when there is none) and the reason; anything
   * else none, "wait for the next webhook".
   */
  def mapStatus(status: String, rcode: String): Option[Either[(String, String), Unit]] =
    if status == "STATUS_SUCCESS" then Some(Right(()))
    else failures.get(status).map(reason => Left((if rcode.nonEmpty then rcode else status, reason)))

  /**
   * `?transactionId=` names the session; the body's `type` names its kind: `CPI` a deposit, `ETO`/`ACH`
   * a payout (`handler/gigadat.go:56-157`). Anything else is acknowledged, as every Gigadat webhook is.
   */
  def locate(raw: Raw): Either[Reply, Located] =
    val reference = raw.query.get("transactionId").map(_.trim).filter(_.nonEmpty)
    (reference, notice(raw.body).kind) match
      case (Some(r), "CPI")                          => Right(Located.Deposit(r))
      case (Some(r), kind) if kind == "ETO" || kind == "ACH" => Right(Located.Payout(r))
      case _                                         => Left(Reply.ok)

  def unknown(raw: Raw): Reply = Reply.ok

  def locatePayout(raw: Raw): Either[Reply, Located] = locate(raw)

  def unknownPayout(raw: Raw): Reply = Reply.ok

  /**
   * The cross-check (FR-013, *new*): the body names the kind the session is, and its `amount`, when it
   * carries one, is the session's in dollars.
   */
  def crossCheck(n: Notice, kinds: Set[String], amount: Long, currency: Currency): Option[String] =
    if !kinds.contains(n.kind) then Some(s"type ${n.kind} does not match the session")
    else if n.amount.trim.isEmpty then None
    else
      Try(java.math.BigDecimal(n.amount.trim)).toOption match
        case Some(a) if a.compareTo(Render.dollars(amount, currency)) == 0 => None
        case _                                                            => Some("amount does not match the session")

  def webhook(raw: Raw, charge: Charge, credential: Credential, now: Instant): Handled =
    val n      = notice(raw.body)
    val status = raw.query.getOrElse("status", "")
    val verdict = crossCheck(n, Set("CPI"), charge.amount, charge.currency) match
      case Some(reason) => Verdict.Reject(reason)
      case None =>
        mapStatus(status, n.rcode) match
          case None                      => Verdict.Open(status)
          case Some(Right(()))           => Verdict.Settle(Outcome.Captured(), status)
          case Some(Left((code, reason))) => Verdict.Settle(Outcome.Failed(Some(code), reason), status)
    Handled(verdict, Reply.ok)

  def payoutWebhook(raw: Raw, withdrawal: Withdrawal, credential: Credential, now: Instant): Handled =
    val n      = notice(raw.body)
    val status = raw.query.getOrElse("status", "")
    val verdict = crossCheck(n, Set("ETO", "ACH"), withdrawal.amount, withdrawal.currency) match
      case Some(reason) => Verdict.Reject(reason)
      case None =>
        mapStatus(status, n.rcode) match
          case None                       => Verdict.Open(status)
          case Some(Right(()))            => Verdict.Settle(Outcome.Completed, status)
          case Some(Left((code, reason))) => Verdict.Settle(Outcome.PayoutFailed(Some(code), reason), status)
    Handled(verdict, Reply.ok)
