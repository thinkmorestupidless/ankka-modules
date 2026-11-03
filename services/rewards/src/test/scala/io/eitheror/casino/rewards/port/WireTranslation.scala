package io.eitheror.casino.rewards.port

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{ArrayNode, DecimalNode, JsonNodeFactory, ObjectNode}
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * The one documented translation between the original's reward routes and this service's (contracts/wire.md),
 * applied in one place: a recorded request, its placeholders filled, becomes this service's, and this service's
 * answer is read back in the recording's terms before the replay compares. The original speaks major units, as
 * strings (`"10.00"`) or numbers (`500`, `0.1`) route by route; this service minor units, and percentages in basis
 * points. The original names a reward `bonus-claims/<id>` and its record `restricted-funds/<id>`; this service by the
 * reward id. A request the table does not name is not this service's; one it marks not carried is answered so.
 *
 * Where wire.md names a route and not its fields, the fields compared are the ones both sides carry (each row's
 * `compared`); an unset field (absent, `null` or empty on either side) is not compared.
 */
object WireTranslation:

  /** Who sends the translated request: the player whose route it is, an operator of the brand, or `engagement`. */
  enum Caller:
    case Player, Operator, Engagement

  /** Which row of the table a request is: the answer is translated by its row. */
  enum Row:
    case Code, Preview, State, Select, Cancel, OfferCreate, OfferRead, OfferDelete, OfferOrder, SpinValues, Grant, Revoke,
      PlayerRewards, BrandRewards, Award, Target, Reward, FreeRounds

  final case class Request(row: Row, method: String, path: String, body: Option[JsonNode], caller: Caller)

  /** A recorded request: one of this service's rows, one wire.md marks not carried, or another service's. */
  enum Translated:
    case Ours(request: Request)
    case NotCarried(why: String)
    case NotOurs

  /** The kinds of id the two sides name differently. */
  enum Kind:
    case Player, Offer, Reward, Grant

  /**
   * The ids the replay has paired, the original's to this service's, by kind. A reward is `bonus-claims/<id>` in the
   * original and its rounds `free-spins-grants/<id>`; here both are the reward id, so a reward id may stand for two of
   * the original's. A pair is made once, from either side's first sight of it, and never remade.
   */
  final class Ids:
    private val toOurs  = mutable.Map.empty[String, String]
    private val toTheirs = mutable.Map.empty[(Kind, String), String]

    def pair(kind: Kind, theirs: String, ours: String): Unit =
      if theirs.nonEmpty && ours.nonEmpty && !toOurs.contains(theirs) && !toTheirs.contains(kind -> ours) then
        toOurs(theirs) = ours
        toTheirs(kind -> ours) = theirs

    /** Another of the original's names for an id already paired: the driver's own placeholder, a winnings claim. */
    def alias(theirs: String, ours: String): Unit =
      if theirs.nonEmpty && ours.nonEmpty && !toOurs.contains(theirs) then toOurs(theirs) = ours

    def ours(theirs: String): String                 = toOurs.getOrElse(theirs, theirs)
    def knows(theirs: String): Boolean               = toOurs.contains(theirs)
    def theirs(kind: Kind, ours: String): String     = toTheirs.getOrElse(kind -> ours, ours)
    def paired(kind: Kind, ours: String): Boolean    = toTheirs.contains(kind -> ours)
    def theirsOf(kind: Kind): Set[String]            = toTheirs.collect { case ((k, _), t) if k == kind => t }.toSet

  /** What a path needs that it does not carry: the brand served, the player whose route it is, the ids paired. */
  final case class Context(brand: String, player: String, ids: Ids, now: java.time.Instant = java.time.Instant.now())

  // ── amounts ──────────────────────────────────────────────────────────────

  /** The currencies the recordings speak, all with two decimal places. */
  private val Exponents = Map("EUR" -> 2, "GBP" -> 2, "ZAR" -> 2, "USD" -> 2, "CAD" -> 2)

  private def exponent(currency: String): Int =
    Exponents.getOrElse(currency.toUpperCase, throw AssertionError(s"no exponent known for $currency"))

  /** A recorded decimal as minor units; more places than the currency has is a test failure, never a rounding. */
  def minor(decimal: BigDecimal, currency: String = "EUR"): Long =
    val scaled = decimal * BigDecimal(10).pow(exponent(currency))
    if !scaled.isWhole then throw AssertionError(s"$decimal $currency has more places than the currency")
    scaled.toLongExact

  /** Minor units as the plain decimal the original wrote: no trailing zeros, never an exponent. */
  def major(minor: Long, currency: String = "EUR"): BigDecimal =
    val d = java.math.BigDecimal.valueOf(minor, exponent(currency)).stripTrailingZeros
    BigDecimal(if d.scale < 0 then d.setScale(0) else d)

  /**
   * Minor units in the form the original answered the same field in: a string of two places (`"10.00"`) where it
   * wrote a string, else a number (`500`, `0.1`).
   */
  def money(minor: Long, like: Option[JsonNode], currency: String = "EUR"): JsonNode =
    if like.exists(_.isTextual) then nodes.textNode(java.math.BigDecimal.valueOf(minor, exponent(currency)).toPlainString)
    else DecimalNode(major(minor, currency).bigDecimal)

  /** A recorded amount, string or number, as minor units. */
  private def recordedMinor(n: JsonNode, field: String, currency: String = "EUR"): Option[Long] =
    Option(n.get(field)).filterNot(_.isNull).map(_.asText.trim).filter(_.nonEmpty).map(a => minor(BigDecimal(a), currency))

  private val nodes = JsonNodeFactory.instance

  /** The fields that are amounts in minor units in this service's answers (FR-032), by the name the original gave them. */
  val AmountFields: Set[String] = Set("maxBonusValue", "minDepositAmount", "maxConvertibleAmount", "spinValue", "bonusBalance",
    "wageringRequirement", "wageredAmount", "maxWin", "clawbackAmount", "newRealBalance", "bonusAmount", "wageringRequired",
    "clawbackApplied", "faceValue", "winnings", "spin_value", "accumulated_raw_winnings", "max_convertible_amount")

  /** Every amount field of `observed`, and of the objects under it, from minor units to the form `recorded` gives it. */
  def toMajor(observed: JsonNode, recorded: Option[JsonNode]): JsonNode = observed match
    case o: ObjectNode =>
      val out = o.objectNode()
      o.properties.asScala.foreach { e =>
        val like = recorded.flatMap(r => Option(r.get(e.getKey)))
        val v    = e.getValue
        out.set[JsonNode](e.getKey,
          if AmountFields(e.getKey) && v.isIntegralNumber then money(v.asLong, like, o.text("currency").getOrElse("EUR"))
          else if e.getKey == "bonusPercentage" && v.isIntegralNumber then percent(v.asLong)
          else toMajor(v, like))
      }
      out
    case a: ArrayNode =>
      val out = a.arrayNode()
      a.elements.asScala.zipWithIndex.foreach((v, i) => out.add(toMajor(v, recorded.flatMap(r => Option(r.get(i))))))
      out
    case other => other

  /** Basis points as the original's percentage; none set is the original's `null`. */
  private def percent(bp: Long): JsonNode = if bp == 0L then nodes.nullNode() else DecimalNode(major(bp).bigDecimal)

  // ── requests ─────────────────────────────────────────────────────────────

  private val Seg        = "([^/?]+)"
  private val PlayerOne  = s"/v1/casino/bonus/$Seg/(enter-code|state|select|cancel)".r
  private val PromoCode  = s"/v1/casino/bonus/promo-code/$Seg".r
  private val Claim      = s"/v1/casino/bonus/claim/$Seg".r
  private val Offers     = s"/v1/casino/bonus/offers/$Seg".r
  private val Reorder    = s"/v1/casino/bonus/offers/$Seg/reorder".r
  private val OfferOne   = s"/v1/casino/bonus/offers/$Seg/$Seg".r
  private val SpinValues = s"/v1/casino/bonus/free-spins/spin-values/$Seg".r
  private val GrantTo    = s"/v1/casino/bonus/grant/$Seg/$Seg".r
  private val Revoke     = s"/v1/casino/$Seg/bonuses/(?:bonus-claims/)?$Seg/revoke".r
  private val PlayerList = s"/v1/casino/backoffice/$Seg/players/$Seg/bonuses".r
  private val BrandList  = s"/v1/casino/backoffice/$Seg/bonuses".r
  private val Webhook    = s"/v1/casino/customerio/webhook/$Seg".r
  private val RewardTwin = s"/twins/bonus-claims/(?:bonus-claims/)?$Seg".r
  private val RoundsTwin = s"/twins/free-spins-grants/(?:free-spins-grants/)?$Seg".r

  /** A reward named the original's way (`bonus-claims/<id>`, or the id alone) as this service's id. */
  def rewardRef(ref: String, ctx: Context): String = ctx.ids.ours(ref.stripPrefix("bonus-claims/").stripPrefix("free-spins-grants/"))

  private def query(recorded: String): Map[String, String] =
    recorded.dropWhile(_ != '?').drop(1).split('&').toVector.filter(_.contains('=')).map { kv =>
      val (k, v) = kv.splitAt(kv.indexOf('='))
      k -> URLDecoder.decode(v.drop(1), UTF_8)
    }.toMap

  private def encode(s: String) = URLEncoder.encode(s, UTF_8)

  /** The recorded request as this service takes it, one wire.md marks not carried, or none when it is not one of the table's. */
  def request(method: String, recorded: String, body: Option[JsonNode], ctx: Context): Translated =
    val b      = ctx.brand
    val path   = recorded.takeWhile(_ != '?')
    val q      = query(recorded)
    val ids    = ctx.ids
    val obj    = body.collect { case o: ObjectNode => o }.getOrElse(Json.obj())
    def rewards(rest: String) = s"/v1/casino/brands/$b/rewards/${ctx.player}$rest"
    def offers(rest: String)  = s"/v1/casino/brands/$b/offers$rest"
    def grants(rest: String)  = s"/v1/casino/brands/$b/reward-grants$rest"
    def ours(row: Row, m: String, p: String, bd: Option[JsonNode], c: Caller) = Translated.Ours(Request(row, m, p, bd, c))
    (method, path) match
      case ("POST", PlayerOne(_, "enter-code")) =>
        ours(Row.Code, "POST", rewards("/codes"), Some(Json.obj("code" -> obj.text("code"))), Caller.Player)
      case ("GET", PlayerOne(_, "state")) => ours(Row.State, "GET", rewards("/state"), None, Caller.Player)
      case ("POST", PlayerOne(_, "select")) =>
        ours(Row.Select, "POST", rewards("/select"), Some(Json.obj("offerId" -> obj.text("offerId").map(ids.ours))), Caller.Player)
      case ("DELETE", PlayerOne(_, "cancel")) =>
        // The original's cancel was a `DELETE` with a body; here a `POST` (contracts/routes.md).
        val named = Option(obj.get("grantRef")).filterNot(_.isNull).map(g => rewardRef(g.asText, ctx))
        ours(Row.Cancel, "POST", rewards("/cancel"), Some(Json.obj("grantRef" -> named)), Caller.Player)
      case ("POST", PromoCode(_)) =>
        if obj.text("action").contains("preview") then
          ours(Row.Preview, "POST", rewards("/codes/preview"), Some(Json.obj("code" -> obj.text("code"))), Caller.Player)
        else Translated.NotCarried("the /promo-code store and read are not served (plan.md Divergences)")
      case ("POST", Claim(_)) => Translated.NotCarried("/claim is not served (plan.md Divergences)")
      case ("PUT", Reorder(_)) =>
        val order = obj.strings("orderedOfferIds").map(ids.ours)
        ours(Row.OfferOrder, "PUT", offers("/order"), Some(Json.obj("orderedOfferIds" -> Json.arr(order*))), Caller.Operator)
      case ("POST", Offers(_))      => ours(Row.OfferCreate, "POST", offers(""), Some(offerBody(obj, ctx)), Caller.Operator)
      case ("GET", OfferOne(_, id)) => ours(Row.OfferRead, "GET", offers(s"/${ids.ours(id)}"), None, Caller.Operator)
      case ("DELETE", OfferOne(_, id)) => ours(Row.OfferDelete, "DELETE", offers(s"/${ids.ours(id)}"), None, Caller.Operator)
      case ("GET", SpinValues(_)) =>
        val params = Seq("gameId", "currency").flatMap(k => q.get(k).map(v => s"$k=${encode(v)}")).mkString("&")
        ours(Row.SpinValues, "GET", offers(s"/free-rounds/spin-values?$params"), None, Caller.Operator)
      case ("POST", GrantTo(_, p)) => ours(Row.Grant, "POST", grants(s"/${ids.ours(p)}"), Some(grantBody(obj, ctx)), Caller.Operator)
      case ("PATCH", Revoke(_, id)) => ours(Row.Revoke, "POST", grants(s"/${rewardRef(id, ctx)}/revoke"), Some(obj), Caller.Operator)
      case ("GET", PlayerList(_, p)) =>
        ours(Row.PlayerRewards, "GET", grants(s"?playerId=${encode(ids.ours(p))}"), None, Caller.Operator)
      case ("GET", BrandList(_)) =>
        val params = Seq("status" -> q.get("status"), "search" -> q.get("search").map(ids.ours), "page" -> q.get("page"),
          "pageSize" -> q.get("pageSize")).collect { case (k, Some(v)) => s"$k=${encode(v)}" }.mkString("&")
        ours(Row.BrandRewards, "GET", grants(if params.isEmpty then "" else s"?$params"), None, Caller.Operator)
      case ("POST", Webhook(_)) =>
        // Customer.io's webhook moves to `engagement`, which calls these two routes; its signature checks are its own.
        val p = obj.text("player_id").map(ids.ours).getOrElse(ctx.player)
        obj.text("action") match
          case Some("grant_bonus") =>
            ours(Row.Award, "POST", s"/v1/casino/brands/$b/reward-campaigns/$p/award",
              Some(Json.obj("offerId" -> obj.text("offer_id").map(ids.ours), "role" -> obj.text("role"), "currency" -> obj.text("currency"))),
              Caller.Engagement)
          case Some("target_offer") =>
            ours(Row.Target, "POST", s"/v1/casino/brands/$b/reward-campaigns/$p/target", Some(Json.obj("role" -> obj.text("role"))),
              Caller.Engagement)
          case other => Translated.NotCarried(s"the webhook's ${other.getOrElse("unnamed")} action is engagement's (spec 014)")
      case ("GET", RewardTwin(id))  => ours(Row.Reward, "GET", grants(s"/${rewardRef(id, ctx)}"), None, Caller.Operator)
      case ("GET", RoundsTwin(id))  => ours(Row.FreeRounds, "GET", grants(s"/${rewardRef(id, ctx)}"), None, Caller.Operator)
      case _                        => Translated.NotOurs

  private val ShiftedTimes = Set("validFrom", "validUntil")

  /**
   * An offer's create in this service's names: amounts in minor units, the percentage in basis points, `priorityOrder`
   * as `priority`, the offers it names by this service's ids. The validity window, recorded on the day of the
   * recording, is moved to the replay's: from an hour ago to a day from now.
   */
  def offerBody(b: ObjectNode, ctx: Context): JsonNode =
    val out = b.deepCopy()
    for f <- Seq("maxBonusValue", "minDepositAmount", "maxConvertibleAmount", "spinValue") do
      recordedMinor(b, f).foreach(out.put(f, _))
    recordedMinor(b, "bonusPercentage").foreach(out.put("bonusPercentage", _))
    Option(b.get("priorityOrder")).filterNot(_.isNull).foreach { p => out.remove("priorityOrder"); out.put("priority", p.asLong) }
    Option(b.get("spinConfigurations")).filter(_.isArray).foreach { a =>
      val configs = a.elements.asScala.toVector.map { c =>
        val currency = c.text("currency").getOrElse("EUR")
        Json.obj("currency" -> currency, "spinValue" -> recordedMinor(c, "spinValue", currency))
      }
      out.set[JsonNode]("spinConfigurations", Json.arr(configs*))
    }
    for f <- Seq("conflictsWith", "excludedOfferIds") do
      Option(b.get(f)).filter(_.isArray).foreach(a => out.set[JsonNode](f, Json.arr(a.elements.asScala.map(_.asText).map(ctx.ids.ours).toSeq*)))
    ShiftedTimes.foreach(f => if b.has(f) then out.put(f, window(f, ctx.now)))
    out

  private def window(field: String, now: java.time.Instant): String =
    (if field == "validFrom" then now.minusSeconds(3600) else now.plusSeconds(86400)).toString

  /**
   * An operator's award in this service's names: the deposit, the amount and the maximum convertible in minor units,
   * the spin value too, the multiplier (`wager`) as a whole number, the offer by this service's id.
   */
  def grantBody(b: ObjectNode, ctx: Context): JsonNode =
    val out      = b.deepCopy()
    val currency = b.text("currency").getOrElse("EUR")
    for f <- Seq("depositAmount", "bonusAmount", "maxConvertibleAmount", "spinValue") do recordedMinor(b, f, currency).foreach(out.put(f, _))
    b.text("wager").flatMap(w => BigDecimal(w).toBigIntExact.map(_.toLong)).foreach(out.put("wager", _))
    b.text("offerId").foreach(o => out.put("offerId", ctx.ids.ours(o)))
    out

  /**
   * The original's offer twin (`/twins/casino-bonus-offers/{id}`, its properties as strings) as this service's create
   * body: the twin is how the original's suites wrote an offer, and the brand's operator writes one here. Codes are
   * `code:isAffiliate` pairs; lists are comma- or bar-separated; an empty value is unset.
   */
  def offerTwin(p: ObjectNode, ctx: Context): JsonNode =
    def s(f: String): Option[String] = p.text(f).filter(_.nonEmpty)
    def list(f: String): Vector[String] = s(f).toVector.flatMap(_.split("[,|]").map(_.trim).filter(_.nonEmpty))
    def money(f: String) = s(f).map(a => minor(BigDecimal(a), s("currency").getOrElse("EUR")))
    def int(f: String) = s(f).flatMap(_.toLongOption)
    val codes = list("redemption_codes").map { c =>
      val (code, flag) = c.lastIndexOf(':') match
        case -1 => (c, "0")
        case i  => (c.take(i), c.drop(i + 1))
      Json.obj("code" -> code, "isAffiliate" -> (flag == "1" || flag.equalsIgnoreCase("true")))
    }
    Json.obj(
      "name" -> s("name"), "type" -> s("type"), "payloadType" -> s("payload_type"), "currency" -> s("currency"),
      "bonusPercentage" -> s("bonus_percentage").map(pc => minor(BigDecimal(pc))), "maxBonusValue" -> money("max_bonus_value"),
      "minDepositAmount" -> money("min_deposit_amount"), "maxConvertibleAmount" -> money("max_convertible_amount"),
      "depositNumberTarget" -> s("deposit_number_target").map(t => t.toLongOption.fold[Any](t)(identity)),
      "claimWindowDays" -> int("claim_window_days"), "wageringWindowDays" -> int("wagering_window_days"),
      "wageringMultiplier" -> int("wagering_multiplier"), "maxClaimsPerPlayer" -> int("max_claims_per_player"),
      "maxConcurrentGrants" -> int("max_concurrent_grants"), "priority" -> int("priority_order"),
      "noDepositRequired" -> s("no_deposit_required").map(_ == "true"), "role" -> s("role"),
      "vipTierFloor" -> int("vip_tier_floor"), "segmentPredicate" -> s("segment_predicate"),
      "conflictsWith" -> Json.arr(list("conflicts_with").map(ctx.ids.ours)*),
      "excludedOfferIds" -> Json.arr(list("excluded_offer_ids").map(ctx.ids.ours)*),
      "redemptionCodes" -> Json.arr(codes*),
      "validFrom" -> s("valid_from").map(_ => window("validFrom", ctx.now)),
      "validUntil" -> s("valid_until").map(_ => window("validUntil", ctx.now))
    )

  // ── answers ──────────────────────────────────────────────────────────────

  /** The fields of the original's answer each row compares; the rest are not carried (wire.md, routes.md). */
  private val Summary = Vector("offerId", "role", "source", "name", "description", "claimWindowDays", "type", "bonusPercentage",
    "maxBonusValue", "minDepositAmount", "wageringMultiplier", "wageringWindowDays", "noDepositRequired")
  private val OfferFields = Vector("offerId", "name", "description", "type", "isActive", "bonusPercentage", "maxBonusValue",
    "minDepositAmount", "maxConvertibleAmount", "depositNumberTarget", "claimWindowDays", "wageringWindowDays",
    "freeSpinsExpiryDays", "wageringMultiplier", "maxClaimsPerPlayer", "maxConcurrentGrants", "excludedOfferIds",
    "conflictsWith", "role", "noDepositRequired", "currency", "priorityOrder", "gameId", "gameName", "provider", "spinCount",
    "spinValue", "spinConfigurations", "winningsDestination", "validFrom", "validUntil", "createdAt", "updatedAt")
  private val RecordFields = Vector("grantRef", "recordId", "bonusBalance", "currency", "wageringRequirement", "wageredAmount",
    "maxWin", "offer")
  private val RowFields = Vector("grantRef", "status", "faceValue", "currency", "offerName", "isPrimary", "wageringRequirement",
    "wageredAmount", "grantedAt", "freeSpins")
  private val BrandRowFields = Vector("offerId", "offerName", "status", "faceValue", "currency", "freeSpins", "wageringRequirement",
    "wageredAmount", "grantedAt", "expiresAt", "playerRef", "type")
  /** The reward's twin: what this service's `GET /reward-grants/{id}` carries of it. */
  private val RewardTwinFields = Vector("status", "face_value", "offer_id", "payload_type", "currency", "player_ref")
  /** The rounds' twin: what the reward's free-rounds part carries of it. */
  private val RoundsTwinFields = Vector("status", "grant_id", "claim_ref", "game_id", "spin_count", "spins_used", "spin_value",
    "accumulated_raw_winnings", "provider_status", "currency", "player_id", "offer_id")

  /** A selection: the offer's summary and its clocks. */
  private val Selected = Summary :+ "selectedAt" :+ "expiresAt"
  /** An offer as a record names it. */
  private val Named = Vector("offerId", "name", "description")

  /** Fields whose `0` is the original's unset, as this service leaves them out. */
  private val ZeroIsUnset = Set("claimWindowDays", "wageringWindowDays", "priorityOrder")

  private def unset(n: JsonNode): Boolean =
    n == null || n.isNull || (n.isTextual && n.asText.isEmpty) || (n.isArray && n.size == 0)

  /** An object kept to `fields`, recursively for those named `offer` and `freeSpins`; an unset field left out. */
  private def keep(n: JsonNode, fields: Vector[String]): JsonNode = n match
    case o: ObjectNode =>
      val out = o.objectNode()
      fields.foreach { f =>
        Option(o.get(f)).filterNot(unset).filterNot(v => ZeroIsUnset(f) && v.isNumber && v.asLong == 0L).foreach { v =>
          out.set[JsonNode](f, v)
        }
      }
      out
    case other => other

  private def each(a: JsonNode)(f: JsonNode => JsonNode): JsonNode = a match
    case arr: ArrayNode => Json.arr(arr.elements.asScala.toVector.map(f)*)
    case other          => other

  /** The recording read the way its row compares it. */
  def recordedShape(row: Row, recorded: JsonNode): JsonNode = row match
    case Row.Code => keep(recorded, Summary)
    case Row.State =>
      val out = keep(recorded, Vector("state", "bonusBalance", "currency", "wageringRequirement", "wageredAmount", "maxWin"))
        .asInstanceOf[ObjectNode]
      Option(recorded.get("visibleOffers")).foreach(v => out.set[JsonNode]("visibleOffers", each(v)(keep(_, Summary))))
      Option(recorded.get("competingOffers")).foreach(v => out.set[JsonNode]("competingOffers", each(v)(keep(_, Summary))))
      Option(recorded.get("selected")).filterNot(unset).foreach(v => out.set[JsonNode]("selected", keep(v, Selected)))
      Option(recorded.get("activeBonuses")).foreach(v => out.set[JsonNode]("activeBonuses", each(v)(record)))
      Option(recorded.get("offer")).filterNot(unset).foreach(v => out.set[JsonNode]("offer", keep(v, Vector("offerId", "name", "description"))))
      out
    case Row.OfferCreate | Row.OfferRead => keep(recorded, OfferFields)
    case Row.PlayerRewards               => each(recorded)(r => freeSpinsKept(keep(r, RowFields)))
    case Row.BrandRewards =>
      val out = keep(recorded, Vector("total", "page", "pageSize")).asInstanceOf[ObjectNode]
      Option(recorded.get("items")).foreach(v => out.set[JsonNode]("items", each(v)(r => freeSpinsKept(keep(r, BrandRowFields)))))
      out
    case Row.Reward     => twin(recorded, RewardTwinFields)
    case Row.FreeRounds => twin(recorded, RoundsTwinFields)
    case _              => recorded

  private def record(r: JsonNode): JsonNode =
    val out = keep(r, RecordFields).asInstanceOf[ObjectNode]
    Option(r.get("offer")).filterNot(unset).foreach(o => out.set[JsonNode]("offer", keep(o, Vector("offerId", "name", "description"))))
    out

  private def freeSpinsKept(r: JsonNode): JsonNode = r match
    case o: ObjectNode =>
      Option(o.get("freeSpins")).filterNot(unset).foreach(f =>
        o.set[JsonNode]("freeSpins", keep(f, Vector("gameId", "spinCount", "spinsUsed", "spinValue", "winnings", "grantStatus"))))
      o
    case other => other

  /** A twin read: its properties kept to `fields`, inside `properties` where the recording wrapped them. */
  private def twin(recorded: JsonNode, fields: Vector[String]): JsonNode =
    Option(recorded.get("properties")).filter(_.isObject) match
      case Some(p) => Json.obj("properties" -> keep(p, fields))
      case None    => keep(recorded, fields)

  /**
   * This service's answer in the recording's terms, then only the fields the original answered. Ids this service
   * made are named by the original's they are paired with; a reward the original named by a reference is named so.
   */
  def answer(request: Request, status: Int, observed: Option[JsonNode], recorded: Option[JsonNode], ctx: Context): (Int, Option[JsonNode]) =
    val ok    = status / 100 == 2
    val shape = recorded.map(r => if status / 100 == 2 then recordedShape(request.row, r) else r)
    val body  =
      if !ok then observed.map(o => toMajor(o, shape))
      else observed.map(o => theirs(request.row, o, shape, ctx))
    (status, narrowed(shape, body))

  private def theirs(row: Row, o: JsonNode, recorded: Option[JsonNode], ctx: Context): JsonNode =
    val ids = ctx.ids
    def offer(id: Option[String])  = id.map(i => ids.theirs(Kind.Offer, i))
    def reward(id: String): String = s"bonus-claims/${ids.theirs(Kind.Reward, id)}"
    def summary(s: JsonNode): JsonNode = s match
      case x: ObjectNode =>
        val out = x.deepCopy()
        offer(x.text("offerId")).foreach(out.put("offerId", _))
        if !x.has("role") || x.get("role").isNull then out.put("role", ""): Unit
        out
      case other => other
    val major = (n: JsonNode) => toMajor(n, recorded)
    row match
      case Row.Code => major(keep(summary(o), Summary))
      case Row.State =>
        val out = o.deepCopy[ObjectNode]()
        Option(o.get("visibleOffers")).foreach(v => out.set[JsonNode]("visibleOffers", each(v)(s => keep(summary(s), Summary))))
        Option(o.get("competingOffers")).foreach(v => out.set[JsonNode]("competingOffers", each(v)(s => keep(summary(s), Summary))))
        Option(o.get("selected")).filterNot(unset).foreach(v => out.set[JsonNode]("selected", keep(summary(v), Selected)))
        Option(o.get("offer")).filterNot(unset).foreach(v => out.set[JsonNode]("offer", keep(summary(v), Named)))
        Option(o.get("activeBonuses")).foreach { v =>
          out.set[JsonNode]("activeBonuses", each(v) { r =>
            val c = r.deepCopy[ObjectNode]()
            r.text("grantRef").foreach(g => c.put("grantRef", reward(g)))
            r.text("recordId").foreach(g => c.put("recordId", s"restricted-funds/${ids.theirs(Kind.Reward, g)}"))
            Option(r.get("offer")).filterNot(unset).foreach(x => c.set[JsonNode]("offer", keep(summary(x), Named)))
            keep(c, RecordFields)
          })
        }
        Seq("grantRef", "recordId").foreach(out.remove)
        major(out)
      case Row.Select | Row.Cancel | Row.Revoke | Row.Target | Row.OfferDelete | Row.OfferOrder | Row.Preview => major(o)
      case Row.Grant | Row.Award =>
        val out = o.deepCopy[ObjectNode]()
        o.text("grantId").foreach(g => out.put("grantId", ids.theirs(Kind.Grant, g)))
        major(out)
      case Row.SpinValues =>
        // `values` are minor units here, decimal strings in the original (research, *Found during implementation*).
        val out = o.deepCopy[ObjectNode]()
        Option(o.get("values")).filter(_.isArray).foreach(v => out.set[JsonNode]("values",
          Json.arr(v.elements.asScala.toVector.map(x => if x.isIntegralNumber then money(x.asLong, Some(nodes.textNode(""))) else x)*)))
        out
      case Row.OfferCreate | Row.OfferRead => major(keep(offerAnswer(o, ctx), OfferFields))
      case Row.PlayerRewards =>
        val rows = o.objects("items").map { r =>
          val c = Json.obj("grantRef" -> r.text("rewardId").map(reward), "status" -> r.get("status"), "faceValue" -> r.get("faceValue"),
            "currency" -> r.get("currency"), "offerName" -> r.get("offerName"), "isPrimary" -> r.get("isPrimary"),
            "wageringRequirement" -> r.get("wageringRequirement"), "wageredAmount" -> r.get("wageredAmount"),
            "grantedAt" -> r.get("grantedAt"), "freeSpins" -> r.get("freeSpins"))
          freeSpinsKept(keep(c, RowFields))
        }
        major(Json.arr(rows*))
      case Row.BrandRewards =>
        val items = o.objects("items").map { r =>
          val c = r.deepCopy[ObjectNode]()
          r.text("playerId").foreach(p => c.put("playerRef", ids.theirs(Kind.Player, p)))
          offer(r.text("offerId")).foreach(c.put("offerId", _))
          freeSpinsKept(keep(c, BrandRowFields))
        }
        val out = keep(o, Vector("total", "page", "pageSize")).asInstanceOf[ObjectNode]
        out.set[JsonNode]("items", Json.arr(items*))
        major(out)
      case Row.Reward     => rewardTwin(o, recorded, ctx)
      case Row.FreeRounds => roundsTwin(o, recorded, ctx)

  /** This service's offer in the original's names: `offerId`, `isActive`, `priorityOrder`, the ids of the offers it names. */
  private def offerAnswer(o: JsonNode, ctx: Context): JsonNode =
    val out = o.deepCopy[ObjectNode]()
    o.text("id").foreach(i => out.put("offerId", ctx.ids.theirs(Kind.Offer, i)))
    Option(o.get("active")).foreach(a => out.set[JsonNode]("isActive", a))
    Option(o.get("priority")).foreach(p => out.set[JsonNode]("priorityOrder", p))
    for f <- Seq("conflictsWith", "excludedOfferIds") do
      Option(o.get(f)).filter(_.isArray).foreach(a =>
        out.set[JsonNode](f, Json.arr(a.elements.asScala.map(_.asText).map(i => ctx.ids.theirs(Kind.Offer, i)).toSeq*)))
    out

  /**
   * The reward as the original's grant twin read it: the status word, the face value (minor units, as the twin kept
   * it; unset until granted), the offer, the payload type and the currency, under `properties` where it was wrapped.
   */
  private def rewardTwin(o: JsonNode, recorded: Option[JsonNode], ctx: Context): JsonNode =
    val status  = o.text("status").getOrElse("")
    val granted = !Set("AVAILABLE", "PENDING").contains(status)
    val p = Json.obj("status" -> status, "face_value" -> Option.when(granted)(Option(o.get("faceValue"))).flatten,
      "offer_id" -> o.text("offerId").map(i => ctx.ids.theirs(Kind.Offer, i)), "payload_type" -> o.text("payloadType"),
      "currency" -> o.text("currency"), "player_ref" -> o.text("playerId").map(i => ctx.ids.theirs(Kind.Player, i)))
    wrapped(keep(p, RewardTwinFields), recorded)

  /** The free-rounds part as the original's rounds twin read it; `status` is the original's word for the grant (wire.md). */
  private def roundsTwin(o: JsonNode, recorded: Option[JsonNode], ctx: Context): JsonNode =
    val f        = Option(o.get("freeSpins")).filterNot(unset).getOrElse(Json.obj())
    val currency = o.text("currency").getOrElse("EUR")
    val like     = recorded.map(r => Option(r.get("properties")).getOrElse(r))
    def amount(field: String, v: Option[Long]) =
      v.map(m => money(m, like.flatMap(l => Option(l.get(field))).orElse(Some(nodes.textNode(""))), currency))
    val rid = o.text("rewardId").getOrElse("")
    val p = Json.obj(
      "status" -> f.text("grantStatus").map(s => if s == "active" then "granted" else s),
      "grant_id" -> Option.when(rid.nonEmpty)(ctx.ids.theirs(Kind.Grant, rid)),
      "claim_ref" -> Option.when(rid.nonEmpty)(s"bonus-claims/${ctx.ids.theirs(Kind.Reward, rid)}"),
      "game_id" -> f.text("gameId"), "spin_count" -> f.text("spinCount"), "spins_used" -> f.long("spinsUsed"),
      "spin_value" -> amount("spin_value", f.long("spinValue")),
      "accumulated_raw_winnings" -> amount("accumulated_raw_winnings", f.long("winnings")),
      "provider_status" -> f.text("providerStatus"), "currency" -> o.text("currency"),
      "player_id" -> o.text("playerId").map(i => ctx.ids.theirs(Kind.Player, i)),
      "offer_id" -> o.text("offerId").map(i => ctx.ids.theirs(Kind.Offer, i)))
    wrapped(keep(p, RoundsTwinFields), recorded)

  private def wrapped(p: JsonNode, recorded: Option[JsonNode]): JsonNode =
    if recorded.exists(_.has("properties")) then Json.obj("properties" -> p) else p

  /**
   * An object keeps only the fields the original answered (this service may say more, `message` beside `error` for
   * one, never less), recursively; an array element by element on the same rule.
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

  // ── pairing ids from an answer ───────────────────────────────────────────

  /** The fields that name an id, by the kind they name, as the original named them. */
  private val IdFields: Map[String, Kind] = Map("offerId" -> Kind.Offer, "offer_id" -> Kind.Offer, "grantRef" -> Kind.Reward,
    "claim_ref" -> Kind.Reward, "grantId" -> Kind.Grant, "grant_id" -> Kind.Grant, "playerRef" -> Kind.Player,
    "player_ref" -> Kind.Player, "player_id" -> Kind.Player)

  private def bare(v: String) = v.stripPrefix("bonus-claims/").stripPrefix("restricted-funds/").stripPrefix("free-spins-grants/")

  /**
   * Pairs the ids `observed` (this service's answer in the recording's terms, before narrowing) names where `recorded`
   * names another at the same place, each the first sight of both: so the original's later steps that name its own id
   * reach this service's. A reward twin's `grant_id` names the reward, a rounds twin's the grant.
   */
  def pairIds(row: Row, recorded: Option[JsonNode], observed: Option[JsonNode], ctx: Context): Unit =
    def walk(r: JsonNode, o: JsonNode): Unit = (r, o) match
      case (ro: ObjectNode, oo: ObjectNode) =>
        ro.fieldNames.asScala.foreach { f =>
          val (rv, ov) = (ro.get(f), oo.get(f))
          if ov != null then
            IdFields.get(f).map(k => if f == "grant_id" && row == Row.Reward then Kind.Reward else k) match
              case Some(kind) if rv.isTextual && ov.isTextual =>
                val (theirs, ours) = (bare(rv.asText), bare(ov.asText))
                if theirs != ours && !ctx.ids.knows(theirs) && !ctx.ids.paired(kind, ours) then ctx.ids.pair(kind, theirs, ours)
              case _ => walk(rv, ov)
        }
      case (ra: ArrayNode, oa: ArrayNode) => (0 until math.min(ra.size, oa.size)).foreach(i => walk(ra.get(i), oa.get(i)))
      case _                              => ()
    for r <- recorded; o <- observed do walk(r, o)
