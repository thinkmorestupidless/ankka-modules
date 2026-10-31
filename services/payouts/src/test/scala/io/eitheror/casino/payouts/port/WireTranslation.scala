package io.eitheror.casino.payouts.port

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{ArrayNode, DecimalNode, ObjectNode}
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*

import scala.jdk.CollectionConverters.*

/**
 * The one documented translation between the original's payout routes and this service's
 * (contracts/wire.md), applied in one place: a recorded request, its placeholders filled, becomes this
 * service's, and this service's answer is read back in the recording's terms before the replay compares.
 * The original speaks decimals of major units; this service minor units. A request the table does not name
 * is not this service's, and is answered as recorded.
 */
object WireTranslation:

  /** Who sends the translated request: whoever the recording's step was, or `payments`/`psp-gateway` for an outcome. */
  enum Caller:
    case AsRecorded, Gateway

  /** Which row of the table a request is: the answer is translated by its row. */
  enum Row:
    case Request, Requests, Cancel, Terminals, Destinations, SaveDestination, SetDefault, DeleteDestination, Queue, Decision,
      History, Config, Outcome

  /**
   * What a path needs that it does not carry: the brand served, the wallet's currency (for a request that
   * names none), the ids this service returned for the original's, and a fresh key per request.
   */
  final case class Context(brand: String, walletCurrency: String = "EUR", ids: Map[String, String] = Map.empty,
      key: () => String = () => java.util.UUID.randomUUID().toString):
    def id(original: String): String = ids.getOrElse(original, original)

  final case class Request(row: Row, method: String, path: String, body: Option[JsonNode], caller: Caller = Caller.AsRecorded)

  // ── amounts ──────────────────────────────────────────────────────────────

  /** The currencies the original pays in, all with two decimal places. */
  private val Exponents = Map("EUR" -> 2, "GBP" -> 2, "CAD" -> 2, "USD" -> 2)

  private def exponent(currency: String): Int =
    Exponents.getOrElse(currency.toUpperCase, throw AssertionError(s"no exponent known for $currency"))

  /** A recorded decimal as minor units; more places than the currency has is a test failure, never a rounding. */
  def minor(decimal: BigDecimal, currency: String): Long =
    val scaled = decimal * BigDecimal(10).pow(exponent(currency))
    if !scaled.isWhole then throw AssertionError(s"$decimal $currency has more places than the currency")
    scaled.toLongExact

  /** Minor units as the plain decimal the original wrote: no trailing zeros, never an exponent. */
  def major(minor: Long, currency: String): BigDecimal =
    val d = java.math.BigDecimal.valueOf(minor, exponent(currency)).stripTrailingZeros
    BigDecimal(if d.scale < 0 then d.setScale(0) else d)

  private def decimalNode(minor: Long, currency: String): JsonNode = DecimalNode(major(minor, currency).bigDecimal)

  /** The answer fields that are amounts (wire.md, *Amounts*). */
  val AmountFields: Set[String] =
    Set("amount", "forfeitedAmount", "clawbackAmount", "maxWithdrawable", "totalForfeit", "bonusBalance") ++
      (for p <- Seq("weekly", "monthly"); f <- Seq("Limit", "Used", "Remaining") yield p + f)

  /** Every amount field of an object, and of the objects under it, from minor units back to decimals. */
  def toDecimals(node: JsonNode, currency: String): JsonNode = node match
    case o: ObjectNode =>
      val c   = o.text("currency").getOrElse(currency)
      val out = o.objectNode()
      o.properties.asScala.foreach { e =>
        val v = e.getValue
        out.set[JsonNode](e.getKey,
          if AmountFields(e.getKey) && v.isIntegralNumber then decimalNode(v.asLong, c)
          else toDecimals(v, c))
      }
      out
    case a: ArrayNode =>
      val out = a.arrayNode()
      a.elements.asScala.foreach(v => out.add(toDecimals(v, currency)))
      out
    case other => other

  private def decimal(n: JsonNode, field: String): Option[BigDecimal] =
    Option(n.get(field)).filterNot(_.isNull).map(v => BigDecimal(v.asText))

  // ── requests ─────────────────────────────────────────────────────────────

  private val Seg        = "([^/?]+)"
  private val PlayerPath = s"/v1/casino/brands/$Seg/players/$Seg/(.+)".r
  private val Queue      = s"/v1/casino/brands/$Seg/withdrawal-requests".r
  private val Decide     = s"/v1/casino/brands/$Seg/withdrawal-requests/$Seg/(approve|reject|confirm-payout|mark-payout-failed)".r
  private val ConfigPath = s"/v1/casino/brands/$Seg/withdrawal-config".r
  private val OutcomeAt  = s"/internal/v1/casino/withdrawal-requests/$Seg/outcome".r
  private val Requests   = "withdrawal-requests"
  private val CancelOne  = s"withdrawal-requests/$Seg".r
  private val Dests      = "payout-destinations"
  private val SetDefault = s"payout-destinations/$Seg/set-default".r
  private val DestOne    = s"payout-destinations/$Seg".r

  /** The recorded request as this service takes it, or none when it is not one of the table's routes. */
  def request(method: String, recorded: String, body: Option[JsonNode], ctx: Context): Option[Request] =
    val b     = ctx.brand
    val path  = recorded.takeWhile(_ != '?')
    val query = Option(recorded.dropWhile(_ != '?').drop(1)).filter(_.nonEmpty)
    def players(p: String, rest: String) = s"/v1/casino/brands/$b/players/$p/$rest"
    (method, path) match
      case ("POST", PlayerPath(_, p, Requests)) => Some(Request(Row.Request, "POST", players(p, Requests), body.map(requestBody(_, ctx))))
      case ("GET", PlayerPath(_, p, Requests))  => Some(Request(Row.Requests, "GET", players(p, Requests), None))
      case ("DELETE", PlayerPath(_, p, CancelOne(r))) =>
        Some(Request(Row.Cancel, "DELETE", players(p, s"$Requests/${ctx.id(r)}"), None))
      case ("GET", PlayerPath(_, p, "withdrawal-terminals")) =>
        val currency = query.flatMap(q => q.split('&').collectFirst { case s"currency=$c" => c }).getOrElse(ctx.walletCurrency)
        Some(Request(Row.Terminals, "GET", players(p, s"withdrawal-terminals?currency=$currency"), None))
      case ("GET", PlayerPath(_, p, Dests))  => Some(Request(Row.Destinations, "GET", players(p, Dests), None))
      case ("POST", PlayerPath(_, p, Dests)) => Some(Request(Row.SaveDestination, "POST", players(p, Dests), body))
      case ("PUT", PlayerPath(_, p, SetDefault(d))) =>
        Some(Request(Row.SetDefault, "PUT", players(p, s"$Dests/${ctx.id(d)}"), Some(Json.obj("setAsDefault" -> true))))
      case ("DELETE", PlayerPath(_, p, DestOne(d))) => Some(Request(Row.DeleteDestination, "DELETE", players(p, s"$Dests/${ctx.id(d)}"), None))
      case ("GET", PlayerPath(_, p, "withdrawal-history")) => Some(Request(Row.History, "GET", players(p, "withdrawal-history"), None))
      case ("GET", Queue(_)) => Some(Request(Row.Queue, "GET", s"/v1/casino/brands/$b/withdrawal-requests" + query.fold("")("?" + _), None))
      case ("POST", Decide(_, r, action)) =>
        Some(Request(Row.Decision, "POST", s"/v1/casino/brands/$b/withdrawal-requests/${ctx.id(r)}/$action", body))
      case ("GET", ConfigPath(_)) => Some(Request(Row.Config, "GET", s"/v1/casino/brands/$b/withdrawal-config", None))
      case ("PUT", ConfigPath(_)) => Some(Request(Row.Config, "PUT", s"/v1/casino/brands/$b/withdrawal-config", body.map(limits)))
      case ("POST", OutcomeAt(r)) =>
        Some(Request(Row.Outcome, "POST", s"/v1/casino/brands/$b/withdrawals/${ctx.id(r)}/outcome", body, Caller.Gateway))
      case _ => None

  /** The original's names kept; the amount in minor units, the wallet's currency when none, and a fresh key. */
  private def requestBody(body: JsonNode, ctx: Context): JsonNode =
    val out      = body.deepCopy[JsonNode]().asInstanceOf[ObjectNode]
    val currency = body.text("currency").getOrElse(ctx.walletCurrency)
    out.put("currency", currency)
    decimal(body, "amount").foreach(a => out.put("amount", minor(a, currency)))
    out.put("key", ctx.key())
    out

  /** The original's EUR limits as this service's, per currency in minor units; the turnover fields dropped. */
  private def limits(body: JsonNode): JsonNode =
    val pair = Json.obj("weekly" -> decimal(body, "weeklyWithdrawalLimitEur").map(minor(_, "EUR")),
      "monthly" -> decimal(body, "monthlyWithdrawalLimitEur").map(minor(_, "EUR")))
    Json.obj("limits" -> Json.obj("EUR" -> pair))

  // ── answers ──────────────────────────────────────────────────────────────

  /** The id this service returned in a request's answer, for the replay to capture as `<id>` beside the original's `<r>`. */
  def captured(request: Request, observed: JsonNode): Option[String] = request.row match
    case Row.Request         => observed.text("requestId")
    case Row.SaveDestination => observed.text("id")
    case _                   => None

  /**
   * This service's answer in the recording's terms (wire.md, *Answer translated*), then only the fields the
   * original answered. `originalId` is the recording's own id for the request or destination this answer names.
   */
  def answer(request: Request, status: Int, observed: Option[JsonNode], recorded: Option[JsonNode],
      originalId: Option[String] = None): (Int, Option[JsonNode]) =
    val ok        = status / 100 == 2
    val decimals  = observed.map(toDecimals(_, "EUR"))
    val (s, body) =
      if !ok then (status, decimals)
      else request.row match
        case Row.Request =>
          (status, decimals.map(o => withField(o, "requestId", originalId.orElse(o.text("requestId")))))
        case Row.Requests          => (status, decimals.map(pendingOnly))
        case Row.Terminals         => (status, decimals.map(o => Json.obj("terminals" -> Json.arr(o.objects("terminals").map(t => Json.obj("id" -> t.text("id")))*))))
        case Row.Destinations      => (status, decimals.map(o => Json.obj("destinations" -> Json.arr(o.objects("destinations").map(lastFour)*))))
        case Row.SaveDestination   => (status, decimals.map(lastFour))
        case Row.DeleteDestination => (200, recorded)
        case Row.Queue             => (status, decimals.map(o => Json.obj("requests" -> Json.arr(o.objects("requests").map(queueRow)*))))
        case Row.Config            => (status, observed.map(eurLimits))
        case Row.Cancel | Row.Decision | Row.History | Row.Outcome => (status, decimals)
    (s, narrowed(recorded.map(recordedShape(request.row, _)), body))

  /** The recording read the way its row compares it: only pending rows, terminals by id, an IBAN by its last four, queue rows by id, status and amount. */
  def recordedShape(row: Row, recorded: JsonNode): JsonNode = row match
    case Row.Requests     => pendingOnly(recorded)
    case Row.Terminals    => Json.obj("terminals" -> Json.arr(recorded.objects("terminals").map(t => Json.obj("id" -> t.text("id")))*))
    case Row.Destinations => Json.obj("destinations" -> Json.arr(recorded.objects("destinations").map(lastFour)*))
    case Row.SaveDestination => lastFour(recorded)
    case Row.Queue        => Json.obj("requests" -> Json.arr(recorded.objects("requests").map(queueRow)*))
    case _                => recorded

  private def withField(o: JsonNode, field: String, value: Option[String]): JsonNode =
    val out = o.deepCopy[JsonNode]().asInstanceOf[ObjectNode]
    value.foreach(out.put(field, _))
    out

  private def pendingOnly(o: JsonNode): JsonNode =
    Json.obj("requests" -> Json.arr(o.objects("requests").filter(_.text("status").contains("PENDING_REVIEW"))*))

  /** A destination with its IBAN as the last four, whichever side said it in full. */
  private def lastFour(d: JsonNode): JsonNode =
    val out  = d.deepCopy[JsonNode]().asInstanceOf[ObjectNode]
    val four = d.text("ibanLastFour").orElse(d.text("iban").map(_.replace(" ", "").takeRight(4)))
    out.remove("ibanLastFour"): Unit
    four.foreach(out.put("iban", _))
    out

  private def queueRow(r: JsonNode): JsonNode =
    Json.obj("requestId" -> r.text("requestId").orElse(r.text("id")), "status" -> r.text("status"), "amount" -> Option(r.get("amount")))

  /** This service's EUR limits in the original's names, in decimals. */
  private def eurLimits(o: JsonNode): JsonNode =
    val eur = Option(o.get("limits")).flatMap(l => Option(l.get("EUR"))).filter(_.isObject)
    def of(f: String) = eur.flatMap(e => Option(e.get(f))).filter(_.isIntegralNumber).map(v => decimalNode(v.asLong, "EUR"))
    Json.obj("weeklyWithdrawalLimitEur" -> of("weekly"), "monthlyWithdrawalLimitEur" -> of("monthly"))

  /**
   * An object keeps only the fields the original answered (this service may say more, `message` beside
   * `error` for one, never less), recursively; an array element by element on the same rule.
   */
  def narrowed(recorded: Option[JsonNode], observed: Option[JsonNode]): Option[JsonNode] =
    (recorded, observed) match
      case (Some(r), Some(o)) => Some(narrow(r, o))
      case _                  => observed

  private def narrow(recorded: JsonNode, observed: JsonNode): JsonNode =
    (recorded, observed) match
      case (r: ObjectNode, o: ObjectNode) =>
        val out = o.objectNode()
        r.fieldNames.asScala.foreach(f => Option(o.get(f)).foreach(v => out.set[JsonNode](f, narrow(r.get(f), v))))
        out
      case (r: ArrayNode, o: ArrayNode) =>
        val out = o.arrayNode()
        o.elements.asScala.zipWithIndex.foreach((v, i) => out.add(if i < r.size then narrow(r.get(i), v) else v))
        out
      case (_, o) => o
