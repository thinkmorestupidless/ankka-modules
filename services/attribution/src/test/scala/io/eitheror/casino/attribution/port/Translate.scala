package io.eitheror.casino.attribution.port

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.money.Days
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.port.Recording

import java.time.{Instant, ZoneId}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * contracts/recording.md's translation: what a step the original answered becomes against the port. A
 * public click is replayed; a notification becomes the brand fact that now carries it; a twin read becomes
 * the attribution facts expected on the topic; a link's seed becomes a legacy tag. Everything else is not
 * this service's and is skipped, naming why.
 */
object Translate:

  sealed trait Action
  /** Sent to the service as recorded, its answer compared with the original's. */
  final case class Replay(step: Recording.Step) extends Action
  /** Published on the kit's broker, as its owner now would. */
  final case class Publish[F <: Fact](topic: Topic[F], fact: F) extends Action
  /**
   * A fact of `kind` expected on `affiliates.attribution` for the player, by pseudonym, amount and reference;
   * `from` names the recorded step it stands for, where a divergence is looked for.
   */
  final case class Expect(kind: String, playerId: String, amount: Option[Long] = None, reference: Option[String] = None)(
      val from: String = "") extends Action
  /** No fact of `kind` expected for the player on the reporting day `day`: the original made none. */
  final case class ExpectNone(kind: String, playerId: String, day: String)(val from: String = "") extends Action
  /** A link's bare UUID, put in the network's legacy tag document. */
  final case class Seed(legacyTag: String, affiliate: String, link: String) extends Action
  final case class Skip(step: String, why: String) extends Action

  val Provider = "recorded"

  /** Major units in a recorded string to minor units by the currency's exponent, exactly, or why not. */
  def minorUnits(major: String, currency: String): Either[String, Long] =
    for
      exponent <- Try(java.util.Currency.getInstance(currency).getDefaultFractionDigits).toOption
                    .filter(_ >= 0).toRight(s"'$currency' is not a currency with minor units")
      value    <- Try(BigDecimal(major.trim)).toOption.toRight(s"'$major' is not an amount")
      minor    <- Try(value.bigDecimal.movePointRight(exponent).longValueExact()).toOption
                    .toRight(s"'$major' $currency is not a whole number of minor units")
    yield minor

  private val Notify   = """/internal/v1/affiliates/[^/]+/notify/([a-z-]+)""".r
  private val Twin     = """/twins/(affiliate-[a-z]+)-([^/]+)/([^/?]+)""".r
  /** The casino's settled round, as scenario 15 reads it: the real-money legs the wallet now reports. */
  private val Round     = """/twins/casino-rounds-([^/]+)/([^/?]+)""".r
  /** The original's tag index, with no brand in its name: scenario 17 seeds its link only there. */
  private val LinkIndex = """/twins/affiliate-links/([^/?]+)""".r
  /** The affiliate's facts listed, filtered by round: scenario 15's read of the GGR the original wrote. */
  private val FactsList = """/twins/affiliate-facts-([^/]+)""".r
  private val Click    = "/v1/track/click"

  /**
   * The recording's steps, in order. `fill` gives each `{placeholder}` the value this replay uses; `zone`
   * is the reporting zone the daily GGR expectation is counted in; `now` stands for an instant a
   * notification does not carry.
   */
  def apply(recording: Recording, zone: ZoneId, fill: String => String, now: Instant): Vector[Action] =
    State(zone, fill, now, identity).translate(recording.steps)

  /**
   * The same translation one step at a time, for a replay whose later steps fill from earlier answers;
   * `shift` moves every instant the recording carries, so each replay has a reporting day of its own.
   */
  def stepwise(zone: ZoneId, fill: String => String, now: Instant, shift: Instant => Instant): Recording.Step => Vector[Action] =
    State(zone, fill, now, shift).step

  /** Every instant a recording's notifications and rounds carry, in the order they were made. */
  def instants(recording: Recording): Vector[Instant] =
    val fields = Seq("registeredAt", "occurredAt", "settled_at")
    def of(node: Option[JsonNode]): Vector[Instant] = node.toVector.flatMap(n =>
      (n +: Option(n.get("properties")).toVector).flatMap(m => fields.flatMap(f => Option(m.get(f)).map(_.asText))))
      .flatMap(t => Try(Instant.parse(t)).toOption)
    recording.steps.flatMap(s => of(s.request.body) ++ (if s.request.path.startsWith("/twins/casino-rounds-") then of(s.response.body) else Vector.empty))

  private final case class State(zone: ZoneId, fill: String => String, now: Instant, shift: Instant => Instant):
    private var realBalance  = Map.empty[(String, String), Long]
    private var withdrawals  = Map.empty[String, (Long, String)]
    private var deposits     = Map.empty[String, Vector[(Long, String)]]
    private var rounds       = Map.empty[String, Vector[(String, Long, String)]]
    private var serial       = 0
    // A recorded answer keeps the first run's ids; each step's captures say which placeholder each stood for.
    private var literals     = Map.empty[String, String]
    private var lastRound    = Option.empty[(String, String)]

    /** A value a recorded answer holds, as this replay knows it. */
    private def local(value: String): String = literals.get(value).map(n => fill(s"{$n}")).getOrElse(value)

    private def remember(s: Recording.Step): Unit =
      s.capture.foreach((name, path) => s.response.body.flatMap(b => scalarAt(b, path)).foreach(v => literals += v -> name))

    private def scalarAt(node: JsonNode, path: String): Option[String] =
      def go(n: JsonNode, rest: List[String]): Option[JsonNode] = rest match
        case Nil => Option(n)
        case key :: more =>
          val Index = """(.*)\[(\d+)\]""".r
          key match
            case Index(k, i) => Option(if k.isEmpty then n else n.get(k)).flatMap(a => Option(a.get(i.toInt))).flatMap(go(_, more))
            case k           => Option(n.get(k)).flatMap(go(_, more))
      go(node, path.stripPrefix("$").split('.').toList.filter(_.nonEmpty)).filterNot(_.isNull).map(Json.scalar)

    def translate(steps: Vector[Recording.Step]): Vector[Action] = steps.flatMap(step)

    private def text(body: Option[JsonNode], field: String): Option[String] =
      body.flatMap(b => Option(b.get(field))).filterNot(_.isNull).map(n => fill(Json.scalar(n)))

    private def need(step: Recording.Step, field: String): String =
      text(step.request.body, field).getOrElse(throw AssertionError(s"${step.name}: the notification has no '$field'"))

    private def money(step: Recording.Step, field: String, currency: String): Long =
      minorUnits(need(step, field), currency).fold(e => throw AssertionError(s"${step.name}: $e"), identity)

    private def instant(step: Recording.Step, field: String): Instant =
      text(step.request.body, field).flatMap(t => Try(Instant.parse(t)).toOption).map(shift).getOrElse(now)

    private def next(prefix: String): String =
      serial += 1
      s"$prefix-$serial"

    def step(s: Recording.Step): Vector[Action] =
      val actions = translated(s)
      remember(s)
      actions

    private def translated(s: Recording.Step): Vector[Action] = s.request.path.takeWhile(_ != '?') match
      case Click if s.request.method == "POST" => Vector(Replay(s))
      case Round(_, _) if s.request.method == "GET" => round(s)
      case FactsList(_) if s.request.method == "GET" => facts(s)
      case Notify(kind)                        => notification(kind, s)
      case Twin(kind, _, _) if s.request.method == "GET" => read(kind, s)
      case Twin("affiliate-links", _, _) | LinkIndex(_) =>
        Vector(Seed(fill(need(s, "btag")), fill(need(s, "affiliateId")), fill(need(s, "btag"))))
      case Twin(_, _, _) => Vector(Skip(s.name, "a seed of the commission's, which leaves the brand"))
      case _             => Vector(Skip(s.name, "not this service's: answered as recorded"))

    private def notification(kind: String, s: Recording.Step): Vector[Action] =
      val player = need(s, "playerId")
      kind match
        case "registration" =>
          val at = instant(s, "registeredAt")
          Vector(Publish(CasinoPlayers, PlayerRegistered(player, at, text(s.request.body, "btag"), text(s.request.body, "clickId"),
            text(s.request.body, "bonusCode"), text(s.request.body, "country"), None, None, at)))
        case "deposit" =>
          val currency = need(s, "currency")
          val amount   = money(s, "amount", currency)
          val txn      = need(s, "transactionId")
          deposits = deposits.updated(player, deposits.getOrElse(player, Vector.empty) :+ (amount -> txn))
          Vector(Publish(PaymentsPayments, PaymentCaptured(txn, player, Provider, MinorUnits.unsafe(amount), Currency.unsafe(currency),
            now)))
        case "withdrawal" =>
          val currency = need(s, "currency")
          val amount   = money(s, "amount", currency)
          val request  = need(s, "requestId")
          withdrawals = withdrawals.updated(request, amount -> currency)
          Vector(
            Publish(CasinoWithdrawals, WithdrawalRequested(player, request, MinorUnits.unsafe(amount), Currency.unsafe(currency), "held", now)),
            Publish(CasinoWithdrawals, WithdrawalCompleted(player, request, MinorUnits.unsafe(amount), Currency.unsafe(currency), now))
          )
        case "withdrawal-reversal" =>
          val request = need(s, "requestId")
          val (amount, currency) = withdrawals.getOrElse(request,
            text(s.request.body, "currency").map(c => money(s, "amount", c) -> c)
              .getOrElse(throw AssertionError(s"${s.name}: a reversal of a withdrawal the recording never made")))
          Vector(Publish(CasinoWithdrawals, WithdrawalReversed(player, request, MinorUnits.unsafe(amount), Currency.unsafe(currency),
            "reversed", now)))
        case "ggr" =>
          val currency = need(s, "currency")
          val ggr      = money(s, "ggr", currency)
          // The wallet's real movements: the recorded real stake and win when the original sent them,
          // otherwise a stake or a win of the GGR itself.
          val stake = text(s.request.body, "real_stake_value").map(v => money(s, "real_stake_value", currency)).getOrElse(math.max(ggr, 0L))
          val win   = text(s.request.body, "real_win_value").map(v => money(s, "real_win_value", currency)).getOrElse(math.max(-ggr, 0L))
          val at    = text(s.request.body, "occurredAt").flatMap(t => Try(Instant.parse(t)).toOption).map(shift).getOrElse(now)
          val round = text(s.request.body, "eventId").getOrElse(next("round"))
          val key   = player -> currency
          val afterBet = realBalance.getOrElse(key, 0L) - stake
          val afterWin = afterBet + win
          realBalance = realBalance.updated(key, afterWin)
          rounds = rounds.updated(player, rounds.getOrElse(player, Vector.empty) :+ ((Days.dayOf(at, zone), stake - win, currency)))
          val c = Currency.unsafe(currency)
          Vector(
            Publish(MoneyTransactions, BetPlaced(player, c, s"$round-bet", round, "recorded", MinorUnits.unsafe(stake), "real",
              SignedMinorUnits(afterBet), MinorUnits.unsafe(0), at)),
            Publish(MoneyTransactions, WinCredited(player, c, s"$round-win", round, "recorded", MinorUnits.unsafe(win), "real",
              SignedMinorUnits(afterWin), MinorUnits.unsafe(0), at))
          )
        case "bonus-cost" =>
          val currency = need(s, "currency")
          val amount   = money(s, "amount", currency)
          val claim    = need(s, "claimId")
          Vector(Publish(MoneyTransactions, RestrictedFundsAwarded(player, Currency.unsafe(currency), claim, claim,
            MinorUnits.unsafe(amount), MinorUnits.unsafe(0), SignedMinorUnits(realBalance.getOrElse(player -> currency, 0L)),
            MinorUnits.unsafe(amount), now)))
        case "chargeback" =>
          val currency = need(s, "currency")
          Vector(Publish(PaymentsPayments, PaymentDisputeOpened(need(s, "transactionId"), player, Provider,
            MinorUnits.unsafe(money(s, "amount", currency)), Currency.unsafe(currency), "chargeback", now)))
        case other => Vector(Skip(s.name, s"a notification '$other' the port has no input for"))

    /**
     * The casino's settled round (scenario 15): its real-money stake and win, the legs the wallet now reports
     * as `bet_placed` and `win_credited` of the real wallet. A round of restricted funds only moves nothing.
     */
    private def round(s: Recording.Step): Vector[Action] =
      val props = s.response.body.flatMap(b => Option(b.get("properties"))).getOrElse(throw AssertionError(s"${s.name}: no round"))
      def field(f: String) = Option(props.get(f)).map(_.asText).getOrElse(throw AssertionError(s"${s.name}: the round has no '$f'"))
      val player   = local(field("player_ref"))
      val currency = field("currency")
      def minor(f: String) = minorUnits(field(f), currency).fold(e => throw AssertionError(s"${s.name}: $e"), identity)
      val (stake, win) = (minor("real_stake"), minor("real_win"))
      val at    = shift(Instant.parse(field("settled_at")))
      val round = local(field("st8_round_id"))
      lastRound = Some(player -> Days.dayOf(at, zone))
      if stake == 0 && win == 0 then Vector.empty
      else
        val key      = player -> currency
        val afterBet = realBalance.getOrElse(key, 0L) - stake
        val afterWin = afterBet + win
        realBalance = realBalance.updated(key, afterWin)
        val c = Currency.unsafe(currency)
        Vector(Publish(MoneyTransactions, BetPlaced(player, c, s"$round-bet", round, field("game_id"), MinorUnits.unsafe(stake), "real",
          SignedMinorUnits(afterBet), MinorUnits.unsafe(0), at))) ++
          Option.when(win > 0)(Publish(MoneyTransactions, WinCredited(player, c, s"$round-win", round, field("game_id"),
            MinorUnits.unsafe(win), "real", SignedMinorUnits(afterWin), MinorUnits.unsafe(0), at)))

    /** A twin the original wrote, read: the facts the port emits instead, for the player the twin names. */
    private def read(kind: String, s: Recording.Step): Vector[Action] =
      lazy val player = text(s.response.body, "playerId").map(local).orElse(s.request.path.split('/').lastOption.map(fill))
        .getOrElse(throw AssertionError(s"${s.name}: no player"))
      kind match
        case "affiliate-registration" => Vector(Expect("registration", player)(s.name))
        case "affiliate-revenue" =>
          // The revenue twin names the affiliate: its first deposit is the first the recording made.
          deposits.toVector.sortBy(_._1).flatMap((p, ds) => ds.headOption.map((amount, txn) =>
            Expect("first-deposit", p, Some(amount), Some(txn))(s.name)))
        case "affiliate-facts" if s.response.body.exists(_.has("twins")) => facts(s)
        case "affiliate-facts" =>
          rounds.getOrElse(player, Vector.empty).groupBy(_._1).toVector.sortBy(_._1).map { (day, rs) =>
            Expect("ggr-daily", player, Some(rs.map(_._2).sum), Some(day))(s.name)
          }
        case other => Vector(Skip(s.name, s"a twin '$other' the brand no longer keeps"))

    /**
     * The GGR facts the original wrote for the round just read (scenario 15), its commissionable GGR in
     * cents: a day's sum is the `ggr-daily` expected; none written, none expected for the round's player.
     */
    private def facts(s: Recording.Step): Vector[Action] =
      val twins = s.response.body.toVector.flatMap(b => b.get("twins").elements().asScala.toVector).flatMap(t => Option(t.get("properties")))
      def field(n: JsonNode, f: String) = Option(n.get(f)).map(_.asText).getOrElse(throw AssertionError(s"${s.name}: a fact has no '$f'"))
      if twins.isEmpty then
        lastRound.toVector.map((player, day) => ExpectNone("ggr-daily", player, day)(s.name))
      else
        twins.groupBy(t => (local(field(t, "playerId")), Days.dayOf(shift(Instant.parse(field(t, "occurredAt"))), zone))).toVector.sortBy(_._1)
          .map { case ((player, day), ts) => Expect("ggr-daily", player, Some(ts.map(t => field(t, "amountCents").toLong).sum), Some(day))(s.name) }
