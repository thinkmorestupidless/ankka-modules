package io.eitheror.casino.programme.port

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.port.{JsonPath, Recording}

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * contracts/recording.md's translation: what a step the original answered becomes against the programme.
 * A route of the original is replayed on the programme's, the brand a filter and the reshaped routes
 * translated, its money in minor units; a notification step becomes the fact spec 012 emits for it; the
 * twins the original seeded become kit setup; a revenue twin becomes the portal's revenue read, and a
 * facts twin the ledger's GGR for the round. A player id becomes its pseudonym. Pure: `Parity` runs it.
 */
object Translate:
  private val mapper = ObjectMapper()
  private val nodes  = JsonNodeFactory.instance

  /** Who a replayed request is sent as. */
  enum Auth:
    case Nobody
    case Manager
    /** An affiliate the recording names by placeholder, `{<who>Token}` for the account `{<who>}`. */
    case Affiliate(who: String)
    /** An affiliate by account id, already filled: a read of the account a recording's twin named. */
    case AffiliateId(id: String)

  /** A request the programme answers, with the original's answer translated to compare against; `compare` false for a setup. */
  final case class Request(name: String, method: String, path: String, body: Option[String], auth: Auth, status: Int,
      expected: Option[JsonNode], capture: Map[String, String], vary: Set[String], compare: Boolean = true)

  sealed trait Action
  final case class Replay(request: Request) extends Action
  final case class Publish(fact: AttributionFact) extends Action
  final case class SetupPlan(brand: String, plan: String, name: String, planType: String, cpaAmount: Long, currency: String,
      revShareBp: Int, minimumDeposit: Long) extends Action
  final case class SetupAffiliate(brand: String, affiliate: String, plan: String) extends Action
  final case class SetupLink(brand: String, link: String, affiliate: String) extends Action
  /** The ledger's `ggr` facts for these fact ids sum to `amount` at (affiliate, brand, currency). */
  final case class ExpectGgr(from: String, affiliate: String, brand: String, currency: String, factIds: Vector[String], amount: Long) extends Action
  final case class Skip(step: String, why: String) extends Action

  /** The driver's currency, and a GGR notification's when it names none. */
  val DefaultCurrency = "GBP"

  /** Major units in a recorded string to minor units by the currency's exponent, exactly, or why not. */
  def minorUnits(major: String, currency: String): Either[String, Long] =
    for
      exponent <- Try(java.util.Currency.getInstance(currency).getDefaultFractionDigits).toOption
                    .filter(_ >= 0).toRight(s"'$currency' is not a currency with minor units")
      value    <- Try(BigDecimal(major.trim)).toOption.toRight(s"'$major' is not an amount")
      minor    <- Try(value.bigDecimal.movePointRight(exponent).longValueExact()).toOption
                    .toRight(s"'$major' $currency is not a whole number of minor units")
    yield minor

  /** The money fields of the original's bodies and answers, major-unit strings there, minor units here. */
  val MoneyFields: Set[String] = Set(
    "amount", "cpaAmount", "minimumDeposit", "minimumPayout", "overrideCommission", "adjustments", "adminFees", "bonusCosts",
    "chargebacks", "cpaCommission", "excludedGgr", "ggr", "ngr", "paymentFees", "providerFees", "revShareCommission",
    "totalCommission", "totalDepositAmount", "total", "cpa", "revShare", "override", "earnings", "commission", "balance",
    "carryover", "pending", "paid", "netRevenue", "deposits", "withdrawals", "minimumDepositThreshold",
    "minimumPayoutThreshold", "minimumPayoutAmount", "totalPayable")

  /** Whole numbers the original's bodies carry as strings, numbers here. */
  val CountFields: Set[String] = Set("bonusCostThreshold", "chargebackThreshold", "payoutCycleDay", "payoutCycleDays")

  /** The original's words in upper case, the programme's in lower case (contracts/routes.md). */
  val WordFields: Set[String] = Set("status", "planType")

  /** A recorded path into a twin answer, as the path into its properties (`translated` unwraps twins). */
  def unwrapped(path: String): String = path.replace(".properties.", ".").stripSuffix(".properties")

  /** Whether an answer's field agrees with the recorded one: the original's words and codes in any case. */
  def same(at: String, expected: String, observed: String): Boolean =
    val field = at.split("[.\\[]").last
    expected == observed || ((WordFields(field) || field == "code") && expected.equalsIgnoreCase(observed))

  /** Flags the original's bodies carry as `"true"` or `"false"`, booleans here. */
  val FlagFields: Set[String] = Set("negativeCarryover")

  // ── routes ──────────────────────────────────────────────────────────────────────────────────────

  private val NetworkAction = """/v1/affiliates/([^/]+)/accounts/[^/]+/network/([^/]+)/([a-z]+)""".r
  private val CommissionView = """/v1/affiliates/([^/]+)/accounts/([^/]+)/view/commission/([^/?]+)""".r
  private val Portal = """/v1/my/.*""".r

  /** A path of the original as the programme serves it (routes.md, research R12): `brand` the recording's. */
  def route(path: String, brand: String): String =
    val (bare, query) = path.indexOf('?') match
      case -1 => (path, "")
      case at => (path.take(at), path.drop(at + 1))
    def withQuery(p: String, extra: String*) =
      val all = (query.split('&').filter(_.nonEmpty).toVector ++ extra).distinct
      if all.isEmpty then p else s"$p?${all.mkString("&")}"
    bare match
      case NetworkAction(b, sub, action)  => withQuery(s"/v1/affiliates/$b/network/$sub/$action")
      case CommissionView(b, aff, period) => withQuery(s"/v1/affiliates/$b/accounts/$aff/view/commission", s"period=$period")
      case Portal() if !query.split('&').exists(_.startsWith("brand=")) => withQuery(bare, s"brand=$brand")
      case _ => withQuery(bare)

  /** Who the original's request was sent as, by its recorded bearer. */
  def authOf(headers: Map[String, String]): Auth =
    val Named = """Bearer \{(\w+)Token\}""".r
    headers.collectFirst { case (k, v) if k.equalsIgnoreCase("authorization") => v } match
      case Some("Bearer {staffToken}") => Auth.Manager
      case Some(Named(who))            => Auth.Affiliate(who)
      case _                           => Auth.Nobody

  // ── bodies and answers ──────────────────────────────────────────────────────────────────────────

  /** The figure fields of a revenue answer, which the programme lists per brand and currency (contracts/routes.md). */
  private val Top = Set("affiliateId", "period")

  /**
   * A recorded revenue answer, one flat object, as the programme answers it: `affiliateId` and `period`
   * at the top and the rest the one figure, in the recording's currency.
   */
  def asFigures(flat: JsonNode, currency: String, brand: String): JsonNode =
    val out    = nodes.objectNode()
    val figure = nodes.objectNode()
    flat.properties().asScala.foreach { e =>
      if Top(e.getKey) then out.set(e.getKey, e.getValue): Unit else figure.set(e.getKey, e.getValue): Unit
    }
    figure.put("currency", currency): Unit
    figure.put("brandId", brand): Unit // the recording's brand is the replay's
    out.set("figures", nodes.arrayNode().add(figure)): Unit
    out

  /** A network answer with each node's `revenueForPeriod`, one flat object in the original's, the programme's figure list. */
  def nodeFigures(node: JsonNode, currency: String, brand: String): JsonNode = node match
    case o: com.fasterxml.jackson.databind.node.ObjectNode =>
      val out = o.deepCopy()
      o.properties().asScala.foreach { e =>
        val v = e.getValue
        if e.getKey == "revenueForPeriod" && v.isObject then
          val f = v.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]()
          f.put("currency", currency).put("brandId", brand): Unit
          out.set(e.getKey, nodes.arrayNode().add(f)): Unit
        else out.set(e.getKey, nodeFigures(v, currency, brand)): Unit
      }
      out
    case a: com.fasterxml.jackson.databind.node.ArrayNode =>
      val out = nodes.arrayNode()
      a.elements().asScala.foreach(e => out.add(nodeFigures(e, currency, brand)))
      out
    case other => other

  /** A recorded `vary` path in a revenue answer, moved to where `asFigures` puts its field. */
  def figurePath(path: String): String =
    path.stripPrefix("$.").takeWhile(c => c != '.' && c != '[') match
      case f if Top(f) || path == "$" => path
      case _                          => "$.figures[0]." + path.stripPrefix("$.")

  /** A body or an answer with its money fields in minor units and every `playerId` its pseudonym. */
  def translated(node: JsonNode, currency: String, pseudonym: String => String): JsonNode =
    def go(n: JsonNode, cur: String): JsonNode =
      // The original answers a twin, `{properties, twinId}`; the programme answers the properties.
      if n.isObject && n.has("twinId") && Option(n.get("properties")).exists(_.isObject) then go(n.get("properties"), cur)
      else if n.isObject && n.has("primaryType") then
        // The twin's type is the original's storage, not its answer.
        val o = n.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode](); o.remove("primaryType"); go(o, cur)
      else if n.isObject then
        val here = Option(n.get("currency")).filter(_.isTextual).map(_.asText).getOrElse(cur)
        val out  = nodes.objectNode()
        n.properties().asScala.foreach { e =>
          val (k, v) = (e.getKey, e.getValue)
          if k == "playerId" && v.isTextual then out.put("pseudonym", pseudonym(v.asText)): Unit
          else if MoneyFields(k) && v.isTextual && minorUnits(v.asText, here).isRight then
            out.put(k, minorUnits(v.asText, here).toOption.get): Unit
          else if WordFields(k) && v.isTextual then out.put(k, v.asText.toLowerCase): Unit
          else if CountFields(k) && v.isTextual && v.asText.trim.toIntOption.isDefined then out.put(k, v.asText.trim.toInt): Unit
          else if FlagFields(k) && v.isTextual && Set("true", "false")(v.asText) then out.put(k, v.asText.toBoolean): Unit
          else out.set(k, go(v, here)): Unit
        }
        out
      else if n.isArray then
        val a = nodes.arrayNode()
        n.elements().asScala.foreach(e => a.add(go(e, cur)))
        a
      else n
    go(node, currency)

  def scalar(n: JsonNode): String = if n.isTextual then n.asText else n.toString

  /** The kind as the topic's family names it. */
  def kindOf(fact: AttributionFact): String = io.eitheror.casino.programme.it.Facts.kindOf(fact)

  // ── the translation ─────────────────────────────────────────────────────────────────────────────

  /**
   * The recording one step at a time, for a replay whose later steps fill from earlier answers. `brand` is
   * the replay's brand; `fill` gives each `{placeholder}` the replay's value; `pseudonym` derives a player's;
   * `now` stands for an instant a notification does not carry.
   */
  def stepwise(brand: String, fill: String => String, pseudonym: String => String, now: () => Instant): Recording.Step => Vector[Action] =
    State(brand, fill, pseudonym, now).step

  def apply(recording: Recording, brand: String, fill: String => String, pseudonym: String => String, now: Instant): Vector[Action] =
    val s = State(brand, fill, pseudonym, () => now)
    recording.steps.flatMap(s.step)

  /** Every instant a recording's notifications and rounds carry. */
  def instants(recording: Recording): Vector[Instant] =
    val fields = Seq("registeredAt", "occurredAt", "settled_at")
    def of(node: Option[JsonNode]): Vector[Instant] = node.toVector.flatMap(n =>
      (n +: Option(n.get("properties")).toVector).flatMap(m => fields.flatMap(f => Option(m.get(f)).filter(_.isTextual).map(_.asText))))
      .flatMap(t => Try(Instant.parse(t)).toOption)
    recording.steps.flatMap(s => of(s.request.body) ++ (if s.request.path.startsWith("/twins/casino-rounds-") then of(s.response.body) else Vector.empty))

  private val Notify     = """/internal/v1/affiliates/[^/]+/notify/([a-z-]+)""".r
  private val PlanTwin   = """/twins/affiliate-plans-[^/]+/([^/?]+)""".r
  private val AccountTwin = """/twins/affiliates-[^/]+/([^/?]+)""".r
  private val LinkTwin   = """/twins/affiliate-links(?:-[^/]+)?/([^/?]+)""".r
  private val RevenueRoute = """/v1/(my/[^/]+|affiliates/[^/]+/accounts/[^/]+)/revenue""".r
  private val Revenue    = """/twins/affiliate-revenue-(.+)-[^-/]+/([^/?]+)""".r
  private val FactsTwin  = """/twins/affiliate-facts-[^/]+-[^/?]+""".r
  private val Round      = """/twins/casino-rounds-[^/]+/([^/?]+)""".r

  private final case class Binding(affiliate: String, link: String)

  private final case class State(brand: String, fill: String => String, pseudonym: String => String, now: () => Instant):
    private var links      = Map.empty[String, String]       // btag → affiliate
    private var bindings   = Map.empty[String, Binding]      // player → binding
    private var deposited  = Set.empty[String]               // players with a first deposit
    private var currencies = Map.empty[String, String]       // player → last currency
    private var days       = Map.empty[(String, String), String] // (player, day) → its ggr-daily's fact id
    private var ggrFacts   = Map.empty[String, Vector[String]] // player → the GGR fact ids published
    private var withdrawals = Map.empty[String, (Long, String)]
    private var serial     = 0
    private var literals   = Map.empty[String, String]       // a value a recorded answer holds → its placeholder
    private var aliases    = Map.empty[String, String]       // a placeholder a capture named → the value it captured

    /** A recorded text with each captured placeholder its captured value, then filled. */
    private def resolve(text: String): String =
      fill("""\{(\w+)\}""".r.replaceAllIn(text, m => java.util.regex.Matcher.quoteReplacement(aliases.getOrElse(m.group(1), m.matched))))

    private def local(value: String): String = literals.get(value).map(n => fill(s"{$n}")).getOrElse(fill(value))

    /** A fact id of this replay's own: `{run}` takes a fresh value per replay, since the inbox outlives replays. */
    private def next(prefix: String): String =
      serial += 1
      s"$prefix-${fill("{run}")}-$serial"

    def step(s: Recording.Step): Vector[Action] =
      val out = translatedStep(s)
      s.capture.foreach((name, path) => s.response.body.flatMap(b => at(b, path)).foreach { v => literals += v -> name; aliases += name -> v })
      out

    private def at(node: JsonNode, path: String): Option[String] =
      JsonPath.get(node, path).filterNot(_.isNull).map(scalar)

    private def text(body: Option[JsonNode], field: String): Option[String] =
      body.flatMap(b => Option(b.get(field))).filterNot(_.isNull).map(n => fill(scalar(n)))

    private def need(s: Recording.Step, field: String): String =
      text(s.request.body, field).getOrElse(throw AssertionError(s"${s.name}: the step has no '$field'"))

    private def money(s: Recording.Step, field: String, currency: String): Long =
      minorUnits(need(s, field), currency).fold(e => throw AssertionError(s"${s.name}: $e"), identity)

    private def instant(s: Recording.Step, field: String): Instant =
      text(s.request.body, field).flatMap(t => Try(Instant.parse(t)).toOption).getOrElse(now())

    private def translatedStep(s: Recording.Step): Vector[Action] = s.request.path.takeWhile(_ != '?') match
      case Notify(kind)                                        => notification(kind, s)
      case PlanTwin(id) if s.request.method == "POST"          => Vector(plan(s, fill(id)))
      case AccountTwin(aff) if s.request.method == "POST"      =>
        Vector(SetupAffiliate(brand, fill(aff), text(s.request.body, "planId").getOrElse("")))
      case LinkTwin(btag) if s.request.method == "POST" =>
        val affiliate = need(s, "affiliateId")
        val tag = fill(btag)
        if links.get(tag).contains(affiliate) then Vector.empty
        else
          links += tag -> affiliate
          Vector(SetupLink(brand, tag, affiliate))
      case Revenue(aff, _) if s.request.method == "GET" => Vector(revenue(s, resolve(aff)))
      case Round(_) if s.request.method == "GET"        => round(s)
      case FactsTwin() if s.request.method == "GET"     => facts(s)
      case p if p.startsWith("/twins/")                 => Vector(Skip(s.name, "a twin the programme does not keep"))
      case p if p.startsWith("/realms/") || p.startsWith("/admin/realms/") => Vector(Skip(s.name, "the realm's, not the programme's"))
      case p if p.startsWith("/v1/affiliates/") || p.startsWith("/v1/my/") || p.startsWith("/v1/join/") => Vector(Replay(request(s)))
      case _ => Vector(Skip(s.name, "not the programme's"))

    private def request(s: Recording.Step): Request =
      val currency = text(s.request.body, "currency").getOrElse(DefaultCurrency)
      // An adjustment is in a currency here (change 6); the original's were in the brand's one.
      val withCurrency = s.request.body.map {
        case o: com.fasterxml.jackson.databind.node.ObjectNode if s.request.path.endsWith("/adjustments") && !o.has("currency") =>
          o.deepCopy().put("currency", currency)
        // A plan's CPA is in its own currency here (change 11); the driver's deposits were in the default one.
        case o: com.fasterxml.jackson.databind.node.ObjectNode if s.request.path.endsWith("/commission-plans") && !o.has("cpaCurrency") =>
          o.deepCopy().put("cpaCurrency", currency)
        case b => b
      }
      // An account's threshold is per currency here (change 6); the original's was one figure.
      def perCurrency(b: JsonNode): JsonNode = b match
        case o: com.fasterxml.jackson.databind.node.ObjectNode if o.has("minimumPayoutThreshold") =>
          val out = o.deepCopy()
          val threshold = out.remove("minimumPayoutThreshold")
          out.set("minimumPayout", nodes.objectNode().set(currency, threshold)): Unit
          out
        case other => other
      val body     = withCurrency.map(b => mapper.writeValueAsString(perCurrency(translated(b, currency, p => pseudonym(local(p))))))
      val setup    = s.name.startsWith("setup:")
      // A revenue read answers a figure per brand and currency (contracts/routes.md); the original's, one flat object.
      val revenue  = s.request.method == "GET" && RevenueRoute.matches(s.request.path.takeWhile(_ != '?'))
      val expected = s.response.body.map(translated(_, DefaultCurrency, p => pseudonym(local(p))))
        .map(b => if revenue && b.isObject then asFigures(b, DefaultCurrency, brand) else nodeFigures(b, DefaultCurrency, brand))
      Request(s.name, s.request.method, route(s.request.path, brand), body, authOf(s.request.headers), s.response.status,
        expected, s.capture.map((k, v) => k -> unwrapped(v)),
        s.vary.keySet.map(unwrapped).map(p => if revenue then Translate.figurePath(p) else p), compare = !setup)

    private def plan(s: Recording.Step, id: String): SetupPlan =
      val currency = text(s.request.body, "cpaCurrency").getOrElse(DefaultCurrency)
      val cpa = text(s.request.body, "cpaAmount").map(_ => money(s, "cpaAmount", currency)).getOrElse(0L)
      val bp  = text(s.request.body, "revSharePercent").flatMap(p => Try((BigDecimal(p) * 100).toIntExact).toOption).getOrElse(0)
      val min = text(s.request.body, "minimumDeposit").map(_ => money(s, "minimumDeposit", currency)).getOrElse(0L)
      SetupPlan(brand, id, text(s.request.body, "planName").getOrElse(id), text(s.request.body, "planType").getOrElse("CPA"), cpa, currency, bp, min)

    private def bindingOf(s: Recording.Step, player: String): Binding =
      bindings.getOrElse(player, throw AssertionError(s"${s.name}: no registration of $player before it"))

    private def facts(b: Binding, player: String) = Facts(brand, b, pseudonym(player))

    private def notification(kind: String, s: Recording.Step): Vector[Action] =
      val player = local(need(s, "playerId"))
      kind match
        case "registration" =>
          val tag = need(s, "btag")
          val affiliate = links.get(tag).orElse(s.response.body.flatMap(b => at(b, "$.affiliateId")).map(local))
          affiliate match
            case None => Vector(Skip(s.name, "a registration under a tag the recording never made"))
            case Some(a) =>
              val b = Binding(a, tag)
              if !bindings.contains(player) then bindings += player -> b
              val at = instant(s, "registeredAt")
              Vector(Publish(facts(bindings(player), player).registration(next("reg"), at, text(s.request.body, "country"))))
        case "deposit" =>
          val b        = bindingOf(s, player)
          val currency = need(s, "currency")
          val amount   = money(s, "amount", currency)
          val txn      = need(s, "transactionId")
          val f        = facts(b, player)
          currencies += player -> currency
          // spec 012: a first captured deposit is a first-deposit and a deposit; a later one a deposit only.
          val first = Option.when(!deposited(player))(Publish(f.firstDeposit(s"fd-$txn", now(), currency, amount, txn)))
          deposited += player
          first.toVector :+ Publish(f.deposit(s"dep-$txn", now(), currency, amount, txn))
        case "ggr" =>
          val b        = bindingOf(s, player)
          val currency = text(s.request.body, "currency").orElse(currencies.get(player)).getOrElse(DefaultCurrency)
          val ggr      = money(s, "ggr", currency)
          val at       = instant(s, "occurredAt")
          Vector(ggrFact(b, player, currency, ggr, math.max(ggr, 0L), math.max(-ggr, 0L), at,
            text(s.request.body, "eventId").getOrElse(next("round"))))
        case "bonus-cost" =>
          val b        = bindingOf(s, player)
          val currency = need(s, "currency")
          val id       = text(s.request.body, "transactionId").orElse(text(s.request.body, "claimId")).getOrElse(next("bonus"))
          Vector(Publish(facts(b, player).bonusCost(s"bonus-$id", now(), currency, money(s, "amount", currency), id)))
        case "chargeback" =>
          val b        = bindingOf(s, player)
          val currency = need(s, "currency")
          val id       = need(s, "transactionId")
          Vector(Publish(facts(b, player).chargeback(s"cb-$id", now(), currency, money(s, "amount", currency), id)))
        case "withdrawal" =>
          val b        = bindingOf(s, player)
          val currency = need(s, "currency")
          val amount   = money(s, "amount", currency)
          val request  = need(s, "requestId")
          withdrawals += request -> (amount -> currency)
          val f = facts(b, player)
          Vector(Publish(f.withdrawal("withdrawal-requested", s"wr-$request", now(), currency, amount, request)),
            Publish(f.withdrawal("withdrawal-completed", s"wc-$request", now(), currency, amount, request)))
        case "withdrawal-reversal" =>
          val b       = bindingOf(s, player)
          val request = need(s, "requestId")
          val (amount, currency) = withdrawals.getOrElse(request, throw AssertionError(s"${s.name}: a reversal of a withdrawal the recording never made"))
          Vector(Publish(facts(b, player).withdrawal("withdrawal-reversed", s"wv-$request", now(), currency, amount, request)))
        case other => Vector(Skip(s.name, s"a notification '$other' spec 012 emits no fact for"))

    /** A day's first GGR is its `ggr-daily`; a later one that day a `ggr-adjustment`, as spec 012 emits a late change. */
    private def ggrFact(b: Binding, player: String, currency: String, ggr: Long, stake: Long, win: Long, at: Instant, round: String): Action =
      val day = LocalDate.ofInstant(at, ZoneOffset.UTC).toString
      val f   = facts(b, player)
      val fact = days.get(player -> day) match
        case None =>
          val id = s"ggr-$round"
          days += (player -> day) -> id
          f.ggrDaily(id, at, currency, day, stake, win, round)
        case Some(_) => f.ggrAdjustment(s"ggr-adj-$round", at, currency, day, ggr, round)
      ggrFacts += player -> (ggrFacts.getOrElse(player, Vector.empty) :+ fact.factId)
      Publish(fact)

    /** The revenue twin, read: the portal's revenue for the twin's period, compared field by field. */
    private def revenue(s: Recording.Step, affiliate: String): Action =
      val period = s.response.body.flatMap(b => at(b, "$.period"))
      val path   = s"/v1/my/$affiliate/revenue" + period.fold("")(p => s"?period=$p")
      Replay(Request(s.name, "GET", route(path, brand), None, Auth.AffiliateId(affiliate),
        s.response.status, s.response.body.map { b =>
          val currency = currencies.values.headOption.getOrElse(DefaultCurrency)
          asFigures(translated(b, currency, p => pseudonym(local(p))), currency, brand)
        }, Map.empty, s.vary.keySet.map(Translate.figurePath)))

    private var lastRound = Option.empty[(String, String)] // (player, round)

    /**
     * The casino's settled round (scenario 15), as attribution reports it: the GGR of its deposit-origin
     * stake and win (spec 025's deposit parts) as the day's GGR; a round of restricted funds only moves nothing.
     */
    private def round(s: Recording.Step): Vector[Action] =
      val props = s.response.body.flatMap(b => Option(b.get("properties"))).getOrElse(throw AssertionError(s"${s.name}: no round"))
      def field(f: String) = Option(props.get(f)).map(_.asText).getOrElse(throw AssertionError(s"${s.name}: the round has no '$f'"))
      val player   = local(field("player_ref"))
      val currency = field("currency")
      def minor(f: String) = minorUnits(field(f), currency).fold(e => throw AssertionError(s"${s.name}: $e"), identity)
      def deposit(f: String, real: String) = Option(props.get(f)).fold(minor(real))(_ => minor(f))
      val (stake, win) = (deposit("deposit_stake", "real_stake"), deposit("deposit_win", "real_win"))
      val round = local(field("st8_round_id"))
      lastRound = Some(player -> round)
      currencies += player -> currency
      if stake == 0 && win == 0 then Vector.empty
      else bindings.get(player).toVector.map(b => ggrFact(b, player, currency, stake - win, stake, win, Instant.parse(field("settled_at")), round))

    /** The original's GGR facts for the round just read: the ledger's GGR for the round's player sums the same. */
    private def facts(s: Recording.Step): Vector[Action] =
      val twins = s.response.body.toVector.flatMap(b => Option(b.get("twins")).toVector.flatMap(_.elements().asScala.toVector))
        .flatMap(t => Option(t.get("properties")))
      val amount = twins.map(t => Option(t.get("amountCents")).map(_.asText.toLong).getOrElse(0L)).sum
      lastRound.flatMap((player, _) => bindings.get(player).map(player -> _)).toVector.map { (player, b) =>
        ExpectGgr(s.name, b.affiliate, brand, currencies.getOrElse(player, DefaultCurrency), ggrFacts.getOrElse(player, Vector.empty), amount)
      }

  /** A brand's facts about one bound player, as its `attribution` would publish them. */
  private final case class Facts(brand: String, b: Binding, pseudonym: String):
    private val network = "programme"
    private def c(code: String) = Currency.unsafe(code)
    def registration(id: String, at: Instant, country: Option[String]): Registration =
      Registration(1, id, at, brand, network, b.affiliate, b.link, None, Vector.empty, None, pseudonym, country)
    def firstDeposit(id: String, at: Instant, currency: String, amount: Long, ref: String): FirstDeposit =
      FirstDeposit(1, id, at, brand, network, b.affiliate, b.link, None, Vector.empty, None, pseudonym, c(currency), MinorUnits.unsafe(amount), ref)
    def deposit(id: String, at: Instant, currency: String, amount: Long, ref: String): Deposit =
      Deposit(1, id, at, brand, network, b.affiliate, b.link, None, Vector.empty, None, pseudonym, c(currency), MinorUnits.unsafe(amount), ref)
    def ggrDaily(id: String, at: Instant, currency: String, day: String, stake: Long, win: Long, ref: String): GgrDaily =
      GgrDaily(1, id, at, brand, network, b.affiliate, b.link, None, Vector.empty, None, pseudonym, c(currency), day,
        MinorUnits.unsafe(stake), MinorUnits.unsafe(win), SignedMinorUnits(stake - win), ref)
    def ggrAdjustment(id: String, at: Instant, currency: String, day: String, ggr: Long, ref: String): GgrAdjustment =
      GgrAdjustment(1, id, at, brand, network, b.affiliate, b.link, None, Vector.empty, None, pseudonym, c(currency), day, SignedMinorUnits(ggr), ref)
    def bonusCost(id: String, at: Instant, currency: String, amount: Long, ref: String): BonusCost =
      BonusCost(1, id, at, brand, network, b.affiliate, b.link, None, Vector.empty, None, pseudonym, c(currency), SignedMinorUnits(amount), ref)
    def chargeback(id: String, at: Instant, currency: String, amount: Long, ref: String): Chargeback =
      Chargeback(1, id, at, brand, network, b.affiliate, b.link, None, Vector.empty, None, pseudonym, c(currency), MinorUnits.unsafe(amount), ref)
    def withdrawal(kind: String, id: String, at: Instant, currency: String, amount: Long, ref: String): AttributionFact =
      val (cur, m) = (c(currency), MinorUnits.unsafe(amount))
      kind match
        case "withdrawal-requested" => AttributedWithdrawalRequested(1, id, at, brand, network, b.affiliate, b.link, None, Vector.empty, None, pseudonym, cur, m, ref)
        case "withdrawal-completed" => AttributedWithdrawalCompleted(1, id, at, brand, network, b.affiliate, b.link, None, Vector.empty, None, pseudonym, cur, m, ref)
        case _                      => AttributedWithdrawalReversed(1, id, at, brand, network, b.affiliate, b.link, None, Vector.empty, None, pseudonym, cur, m, ref)
