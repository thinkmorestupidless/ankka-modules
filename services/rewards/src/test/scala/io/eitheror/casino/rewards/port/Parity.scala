package io.eitheror.casino.rewards.port

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.port.{Recording, Replay}
import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.consumers.Detection
import io.eitheror.casino.rewards.it.{Answer, Kit, Reply, SharedKit}
import io.eitheror.casino.rewards.player.PlayerRewards
import io.eitheror.casino.rewards.port.WireTranslation.{Caller, Ids, Kind, Row, Translated}

import java.nio.file.{Files, Path, Paths}
import java.time.{Duration as JDuration, Instant}
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Replays one recording of tests/port/rewards against the shared kit (ankka-port, section 7). A step on one of the
 * reward routes (contracts/wire.md) is translated by [[WireTranslation]], sent as the player, the brand's operator or
 * `engagement`, and its answer read back in the recording's terms; a step wire.md marks not carried is answered as
 * recorded and named. Every other step is another service's, answered as recorded; the ones that change what this
 * service knows are first made the way that service tells this one (the stand-ins below):
 *
 *  - the player's registration, sign-in and twin (`players`): the facts on `casino.players`;
 *  - the realm (Keycloak) and the brand's twin: nothing, answered as recorded;
 *  - an offer's twin: the operator's offer route, as the original's suites wrote an offer through its twin;
 *  - a deposit's twin or the wallet's deposit route: `deposit_confirmed` on `money.transactions`, and the balance;
 *  - the wallet's own routes and its records' twins: the wallet's facts on `money.transactions` once its record shows
 *    the end, and the kit's `FakeWallet` for what it answers this service;
 *  - fake-st8's launch and rounds, through `game-provider`: `round_settled` on `casino.rounds`;
 *  - the brand's duplicate-detection switch (Unleash): `FakePlayers`, which `players`' configuration read answers;
 *  - the substrate's lists and its other twins: answered as recorded, and read for the ids they pair.
 */
object Parity:
  val root: Path      = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize
  val directory: Path = root.resolve("tests/port/rewards")

  def recordings(directory: Path = directory): Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.filter(p => p.toString.endsWith(".json") && !p.getFileName.toString.startsWith("suite-result")).toVector.sorted

  /**
   * Where the service answers differently from the original on purpose: a row of plan.md's *Divergences* (named by its
   * first words in `row`), the answer's fields it covers, and the steps where it holds, by their index: the Go suite
   * names a step by its request, which repeats.
   */
  final case class Divergence(row: String, fields: Seq[String], why: String, holds: (Recording, Int) => Boolean):
    def reached(recording: Recording): Boolean = recording.steps.indices.exists(holds(recording, _))
    def covers(field: String): Boolean         = fields.exists(f => field == f || field.startsWith(f + ".") || field.startsWith(f + "["))

  /** Steps of the recordings whose scenario starts with one of `scenarios`, named by the start of their own names. */
  private def at(scenarios: String*)(steps: String*): (Recording, Int) => Boolean =
    (r, i) => scenarios.exists(r.scenario.startsWith) && r.steps.lift(i).exists(s => steps.exists(s.name.startsWith))

  /** Steps of one recording by their index. */
  private def steps(scenario: String)(indices: Int*): (Recording, Int) => Boolean =
    (r, i) => r.scenario.startsWith(scenario) && indices.contains(i)

  /** A read of the original's winnings claim: a reward of its own there, the reward's wallet record here. */
  private def winningsClaim(r: Recording, i: Int): Boolean =
    r.steps.lift(i).flatMap(_.response.body).flatMap(b => Option(b.get("properties"))).exists(p =>
      p.text("source").contains("system") && p.text("free_spins_grant_ref").isDefined)

  private val S16 = "TestScenario16_RewardGrant_TargetSelectActivateForfeit"
  private val Dup = "TestScenario21_SuspectedDuplicate_RefusedOnEveryPath"

  val divergences: Vector[Divergence] = Vector(
    Divergence("A matched code selects its offer", Seq(".body.state", ".body.visibleOffers", ".body.selected.source",
      ".body.properties.status", ".status", ".body.error"),
      "a matched code selects the offer, where the original showed it available and waited for a select",
      (r, i) => steps(S16)(10, 13, 15)(r, i) || steps(Dup)(12)(r, i)),
    Divergence("A code is live once its offer is written", Seq(".status", ".body"),
      "a code is live as soon as its offer is written; the original's code index caught up moments later",
      steps(Dup)(9, 10)),
    Divergence("Free-rounds winnings to the bonus balance are a reward of their own", Seq(".status", ".body"),
      "free-rounds winnings to the bonus balance are a reward of their own, under the free-rounds reward as its offer; the free-rounds reward's face value is what the rounds won; the player's cancel of them claws back nothing where the original clawed back the winnings since activation (scenario 244 step 32, a known gap)",
      (r, i) => winningsClaim(r, i) ||
        steps("TestScenario244_CancelWithBaselineClawsBackOnlyWinnings")(32)(r, i) ||
        steps("TestScenarioFreeSpinsPlay_BonusBalanceDestination")(23)(r, i) || steps("TestScenarioFreeSpinsPlay_CustomerIOGrant")(28)(r, i) ||
        steps("TestScenarioFreeSpinsPlay_RealBalanceDestination")(20)(r, i) || steps("TestScenarioFreeSpinsPlay_StacksOnActiveBonus")(24)(r, i)),
    Divergence("Segments and the tier floor are dormant", Seq(".status", ".body"),
      "no fact carries a player's segments or tier rank, so neither rule refuses",
      at("a claim that fails one rule is refused with that rule's reason [a player below",
        "a claim that fails one rule is refused with that rule's reason [a player outside")("claim the offer by its code")),
    Divergence("The award's `bonusAmount` is the amount awarded", Seq(".body.bonusAmount"),
      "the award answers the amount awarded; the original answered the most it could convert",
      steps("TestScenarioFreeSpinsPlay_StacksOnActiveBonus")(11)),
    Divergence("The preview names the offer's currency", Seq(".body.preview"), "the preview names the offer's currency, not £",
      at("a code entered as a preview")("preview the code")),
    Divergence("The brand's order is its offer book's", Seq(".body.priorityOrder"),
      "a reorder orders the brand's book; an offer keeps its own priority", at("offers reordered by an operator")("read the first offer")),
    Divergence("The player's own forfeit at the wallet is not carried", Seq(".body.properties.status", ".body.reason"),
      "the player's forfeit at the wallet is not a route of the ported wallet, so the reward stays active",
      (r, i) => steps(S16)(26)(r, i) || steps("TestScenario21_MaxClaimsAndExclusion")(21)(r, i)),
    Divergence("The brand's list carries each reward's wagering requirement", Seq(".body.items"),
      "the brand's list answers each reward's wagering requirement, where the original's answered none",
      at("an operator reading the brand's rewards")("read the brand's active rewards"))
  )

  def diverging(recording: Recording): Vector[Divergence] = divergences.filter(_.reached(recording))

  private val StepField = """\$\.steps\[(\d+)\]\.response(.*)""".r

  def divergence(recording: Recording)(d: Replay.Difference): Option[Divergence] = d.at match
    case StepField(i, field) =>
      val step = recording.steps.lift(i.toInt)
      divergences.find(v => v.covers(field) && step.isDefined && v.holds(recording, i.toInt))
    case _ => None

  private def translated(r: Recording.Request): Translated =
    scala.util.Try(WireTranslation.request(r.method, r.path, r.body, WireTranslation.Context("b", "p", Ids()))).getOrElse(Translated.NotOurs)

  /** Whether a recorded step is one of the original's reward routes this service serves (contracts/wire.md). */
  def ours(r: Recording.Request): Boolean = translated(r).isInstanceOf[Translated.Ours]

  /** The recording's steps wire.md marks not carried, with why: answered as recorded, named in the test. */
  def notCarried(recording: Recording): Vector[(Int, String)] =
    recording.steps.zipWithIndex.collect(Function.unlift((s, i) => translated(s.request) match
      case Translated.NotCarried(why) => Some(i -> why)
      case _                          => None))

  // ── the recording, read the way each row compares it ─────────────────────

  /**
   * The recording with each step on one of this service's routes read the way its row compares it
   * ([[WireTranslation.recordedShape]]), and its `vary` paths kept to the fields the shaped answer still holds.
   */
  def scoped(recording: Recording): Recording =
    recording.copy(steps = recording.steps.map(shaped))

  /**
   * The row a recorded step is: by its path, or for a twin read named by a capture (`/twins/{c5}`) by the concept
   * the original's answer names, a reward's or its rounds'.
   */
  def rowOf(s: Recording.Step): Option[WireTranslation.Row] = translated(s.request) match
    case Translated.Ours(r) => Some(r.row)
    case _ if s.request.method == "GET" && s.request.path.startsWith("/twins/{") =>
      val concepts = s.response.body.toVector.flatMap(_.strings("concepts"))
      if concepts.contains("reward-grant") then Some(WireTranslation.Row.Reward)
      else if concepts.contains("free-spins-grant") then Some(WireTranslation.Row.FreeRounds)
      else None
    case _ => None

  private def shaped(s: Recording.Step): Recording.Step =
    val row = rowOf(s)
    (row, s.response.body) match
      case (Some(r), Some(b)) if s.response.status / 100 == 2 =>
        val body    = WireTranslation.recordedShape(r, b)
        val general = s.vary.map((path, why) => Index.replaceAllIn(path, "[]") -> why)
        val vary    = leaves(body, "$").flatMap(path => general.get(Index.replaceAllIn(path, "[]")).map(path -> _)).toMap
        s.copy(response = s.response.copy(body = Some(body)), vary = vary)
      case _ => s

  private val Index = """\[\d+\]""".r

  /** Every path of a node, as the replay names them. */
  private def leaves(n: JsonNode, path: String): Vector[String] =
    path +: (n match
      case o: ObjectNode => o.properties.asScala.toVector.flatMap(e => leaves(e.getValue, s"$path.${e.getKey}"))
      case a: ArrayNode  => (0 until a.size).toVector.flatMap(i => leaves(a.get(i), s"$path[$i]"))
      case _             => Vector.empty)

  // ── the replay ───────────────────────────────────────────────────────────

  private val Placeholder   = """\{([A-Za-z0-9]+)\}""".r
  private val Register      = """/v1/casino/players/[^/]+/register""".r
  private val Login         = """/v1/casino/players/[^/]+/login""".r
  private val PlayerTwin    = """/twins/casino-(?!bonus-offers|payments)[^/]+/([^/?]+)""".r
  private val OfferTwin     = """/twins/casino-bonus-offers/([^/?]+)""".r
  private val PaymentTwin   = """/twins/casino-payments/([^/?]+)""".r
  private val RewardWrite   = """/twins/bonus-claims/(?:bonus-claims/)?([^/?]+)""".r
  private val RoundsWrite   = """/twins/free-spins-grants/(?:free-spins-grants/)?([^/?]+)""".r
  private val FundsTwin     = """/twins/restricted-funds/([^/?]+)""".r
  private val BonusStatus   = """/v1/wallet/[^/]+/([^/]+)/[A-Z]{3}/bonus/status""".r
  private val Deposit       = """(?:/internal)?/v1/wallet/[^/]+/([^/]+)/([A-Z]{3})/deposit""".r
  private val WalletMove    = """/v1/wallet/[^/]+/([^/]+)/([A-Z]{3})/(bet|win)""".r
  private val Adjust        = """/v1/casino/[^/]+/wallets/([^/]+)/adjust""".r
  private val Launch        = """/v1/casino/sessions/[^/]+/launch""".r
  private val KillSwitch    = """/api/admin/projects/[^/]+/features/casino-disable-multi-account-detection.*""".r


  private final class Replaying(kit: Kit, recording: Recording):
    private var step     = -1 // Replay.run sends the steps in order
    private val minted   = mutable.LinkedHashMap.empty[String, String]
    private val ids      = Ids()
    private val brand    = recording.original.get("brand").filter(_.nonEmpty).getOrElse("spinvibe")
    private val players  = mutable.LinkedHashSet.empty[String]
    private val byEmail  = mutable.Map.empty[String, String]
    private val currency = mutable.Map.empty[String, String]
    private val ordinals = mutable.Map.empty[String, Int]
    private val tokens   = mutable.Map.empty[String, String] // a launch's token, to its player
    private val flags    = mutable.Map.empty[(String, String), Boolean]
    private val rounds   = mutable.Map.empty[String, Int] // the rounds settled, by reward
    private val claims   = mutable.Map.empty[String, JsonNode] // the original's claims seen, by id
    private var current  = Option.empty[String]
    private var cause    = "loss" // what last moved a record's balance: a round or an adjustment

    private def fresh = UUID.randomUUID().toString.take(8)

    /**
     * A value for a placeholder no answer captured: an address, a code, an id or a validity bound minted per replay
     * (entities outlive a test); a registration time from the step that writes it, minutes ago as its name says.
     */
    private def value(name: String): String =
      minted.getOrElseUpdate(name, {
        val stepName = recording.steps.lift(step).map(_.name).getOrElse("")
        if name == "registeredAt" then
          val minutes = if stepName.contains("eleven minutes") then 11 else if stepName.contains("five minutes") then 5 else 0
          kit.now().minus(JDuration.ofMinutes(minutes)).toString
        else if name.endsWith("ValidFrom") then kit.now().minusSeconds(3600).toString
        else if name.endsWith("ValidUntil") then kit.now().plusSeconds(86400).toString
        else if name.toLowerCase.contains("email") then s"${name.toLowerCase}-$fresh@example.test"
        else if name == "code" then s"RD-${fresh.toUpperCase}"
        else s"$name-$fresh"
      })

    private def fill(text: String): String =
      Placeholder.replaceAllIn(text, m => java.util.regex.Matcher.quoteReplacement(value(m.group(1))))

    private def answered(r: Reply): Recording.Response =
      Recording.Response(r.status, Option.when(r.text.trim.nonEmpty)(Json.parse(r.text).getOrElse(Json.mapper.getNodeFactory.textNode(r.text))))

    private def ctx = WireTranslation.Context(kit.slug, current.getOrElse(""), ids, kit.now())

    private def properties(n: JsonNode): JsonNode = Option(n.get("properties")).filter(_.isObject).getOrElse(n)

    private def truth(n: JsonNode, field: String): Option[Boolean] =
      Option(n.get(field)).filterNot(_.isNull).map(_.asText).filter(_.nonEmpty).map(_.equalsIgnoreCase("true"))

    private def record(p: String) = kit.components.forEventSourcedEntity(EntityId(p)).call(PlayerRewards.get).invoke()

    private def awaitRecord(p: String, what: String)(f: io.eitheror.casino.rewards.player.PlayerRewardsState => Boolean): Unit =
      kit.eventually(s"$p: $what", 10.seconds)(Some(record(p)).filter(f)): Unit

    private def playersFact[F <: CasinoPlayersFact](p: String, fact: F): Unit = kit.publish(CasinoPlayers, p, fact)

    // ── the stand-ins ──────────────────────────────────────────────────────

    /** The player's duplicate check started (at `registeredAt`) and, unless still running, ended clear. */
    private def check(p: String, registeredAt: Instant, ended: Boolean): Unit =
      playersFact(p, PlayerDuplicateCheckStarted(p, registeredAt, kit.now()))
      awaitRecord(p, "check in progress")(_.checkInProgress.isDefined)
      if ended then
        playersFact(p, PlayerDuplicateCheckEnded(p, "clear", kit.now()))
        awaitRecord(p, "check ended")(_.checkInProgress.isEmpty)

    /** The player's twin's flags as `players` tells them: a suspected duplicate, a weak signal's block. */
    private def flagsOf(p: String, t: JsonNode): Unit =
      truth(t, "suspected_duplicate").filter(v => flags.getOrElse(p -> "dup", false) != v).foreach { v =>
        flags(p -> "dup") = v
        if v then playersFact(p, PlayerFraudFlagged(p, s"case-$fresh", "multi_account", kit.now()))
        else playersFact(p, PlayerFraudCleared(p, s"case-$fresh", kit.now()))
        awaitRecord(p, s"suspected duplicate $v")(_.suspectedDuplicate == v)
      }
      truth(t, "no_deposit_offer_blocked").filter(v => flags.getOrElse(p -> "block", false) != v).foreach { v =>
        flags(p -> "block") = v
        if v then playersFact(p, PlayerNoDepositBlocked(p, kit.now())) else playersFact(p, PlayerNoDepositBlockCleared(p, "op-1", kit.now()))
        awaitRecord(p, s"no-deposit block $v")(_.noDepositBlocked == v)
      }

    /** A confirmed deposit, as the wallet tells this service of one, and the real balance it raises. */
    private def deposit(p: String, cur: String, amount: Long): Unit =
      val n = ordinals.getOrElse(p, 0) + 1
      ordinals(p) = n
      kit.wallet.realFor(p, cur, kit.wallet.realOf(p, cur) + amount)
      kit.publish(MoneyTransactions, p, DepositConfirmed(p, Currency.unsafe(cur), s"txn-$fresh", MinorUnits.unsafe(amount), s"pay-$fresh",
        n, SignedMinorUnits(kit.wallet.realOf(p, cur)), MinorUnits.unsafe(0L), kit.now()))
      awaitRecord(p, s"deposit $n")(_.lastDeposit.isDefined)

    /** The player's free-rounds reward the provider's rounds are of: the newest open one. */
    private def roundsOf(p: String): Option[(String, Long)] =
      scala.util.Try(kit.eventually(s"$p's free rounds", 10.seconds)(
        grants(p).find(r => r.text("type").contains("FREE_SPINS") && r.text("status").contains("ACTIVE"))
          .flatMap(r => r.text("rewardId").map(_ -> Option(r.get("freeSpins")).flatMap(_.long("spinCount")).getOrElse(0L))))).toOption

    /** A round of the player's free rounds settled at `game-provider`, as `casino.rounds` tells this service. */
    private def round(p: String, rewardId: String, win: Long): Unit =
      val n = rounds.getOrElse(rewardId, 0) + 1
      rounds(rewardId) = n
      val id = s"rd-$fresh"
      kit.publish(CasinoRounds, s"$p/${currency.getOrElse(p, "EUR")}", RoundSettled(p, Currency.unsafe(currency.getOrElse(p, "EUR")), id, "st8",
        s"p-$id", "book-of-ra", s"s-$fresh", MinorUnits.unsafe(0), MinorUnits.unsafe(win), SignedMinorUnits(win),
        if win > 0 then RoundSettled.Outcome.Win else RoundSettled.Outcome.Loss, Vector.empty, Vector.empty, Vector.empty, Some(rewardId),
        kit.now()))
      kit.eventually(s"$rewardId round $n", 10.seconds)(
        Some(grant(rewardId)).filter(g => Option(g.get("freeSpins")).flatMap(_.long("spinsUsed")).exists(_ >= n))): Unit

    /** The player's rewards as this service lists them for the operator. */
    private def grants(p: String): Vector[JsonNode] =
      Json.parse(kit.request("GET", kit.grantsPath(s"?playerId=${java.net.URLEncoder.encode(p, "UTF-8")}"), None, kit.asOperator()).text)
        .map(_.objects("items")).getOrElse(Vector.empty)

    private def grant(rewardId: String): JsonNode =
      Json.parse(kit.request("GET", kit.grantsPath(s"/$rewardId"), None, kit.asOperator()).text).getOrElse(Json.obj())

    /**
     * The wallet's end of a record, once the original's record twin shows it: a conversion, a forfeit by a loss or an
     * adjustment, a close at its cap. The kit's wallet ends the record too, so this service's reads of it agree.
     */
    private def recordEnded(rewardId: String, p: String, status: String, amount: Long): Unit =
      val cur  = Currency.unsafe(currency.getOrElse(p, "EUR"))
      val real = SignedMinorUnits(kit.wallet.realOf(p, cur.code))
      val fact: Option[MoneyTransactionsFact] = status.toLowerCase match
        case "converted" | "converted_pending_cap" | "completed" =>
          Some(RestrictedFundsConverted(p, cur, s"txn-$fresh", rewardId, MinorUnits.unsafe(amount), real, MinorUnits.unsafe(0), kit.now()))
        case "forfeited" | "exhausted" =>
          Some(RestrictedFundsForfeited(p, cur, s"txn-$fresh", rewardId, MinorUnits.unsafe(amount), cause, MinorUnits.unsafe(0), real,
            MinorUnits.unsafe(0), kit.now()))
        case "closed" => Some(RestrictedFundsClosed(p, cur, s"txn-$fresh", rewardId, "real_balance_zero", real, MinorUnits.unsafe(0), kit.now()))
        case _        => None
      fact.foreach { f =>
        f match
          case _: RestrictedFundsConverted => kit.wallet.pendingCap(p, cur.code, rewardId)
          case _                           => kit.wallet.end(p, cur.code, rewardId)
        kit.publish(MoneyTransactions, p, f)
        kit.eventually(s"$rewardId moved by the wallet", 10.seconds)(
          Some(grant(rewardId)).filter(g => !g.text("status").contains("ACTIVE"))): Unit
      }

    private val ended = mutable.Set.empty[String]

    /** A record of the original's claim shown by the wallet (`f`, its status and balance): its end, the first time shown. */
    private def recordShown(claim: String, p: String, f: JsonNode): Unit =
      // A claim no list has shown yet is the player's: the wallet names whose record it is.
      if !claims.contains(claim) && p.nonEmpty then claims(claim) = Json.obj("player_ref" -> ids.theirs(Kind.Player, p))
      resolve(claim)
      val status = f.textOr("status", "")
      if ids.knows(claim) && !ids.ours(claim).endsWith("/winnings") && !status.equalsIgnoreCase("active") && !ended(claim) then
        ended += claim
        val amount = Option(f.get("balance")).filterNot(_.isNull).map { b =>
          if b.isIntegralNumber then b.asLong else WireTranslation.minor(BigDecimal(b.asText))
        }.getOrElse(0L)
        recordEnded(ids.ours(claim), p, status, amount)

    /**
     * The original's claims, from any answer that lists them, by id: what pairs a claim this replay has not seen
     * named with the reward this service holds for the same player and offer.
     */
    private def learn(n: JsonNode): Unit = n match
      case o: ObjectNode =>
        val p = properties(o)
        p.text("grant_id").filter(_.startsWith("bonus-claims/")).foreach(g => claims(g.stripPrefix("bonus-claims/")) = p)
        o.properties.asScala.foreach(e => learn(e.getValue))
      case a: ArrayNode => a.elements.asScala.foreach(learn)
      case _            => ()

    /**
     * Pairs an original claim with this service's reward before a step names it: its winnings claim with the free
     * rounds' wallet record (`<reward>/winnings`), any other with the player's reward of the same offer not yet paired,
     * the oldest first.
     */
    private def resolve(claim: String): Unit =
      if !ids.knows(claim) then
        claims.get(claim).foreach { c =>
          val p = c.text("player_ref").map(ids.ours).getOrElse("")
          if c.text("source").contains("system") && c.text("free_spins_grant_ref").isDefined then
            ids.alias(claim, s"${ids.ours(c.text("free_spins_grant_ref").get.stripPrefix("free-spins-grants/"))}/winnings")
          else
            val offer = c.text("offer_id").map(ids.ours)
            def candidate = grants(p).reverse.filter(r => r.text("rewardId").exists(id => !ids.paired(Kind.Reward, id)))
            val found = scala.util.Try(kit.eventually(s"a reward of $p for $claim", 5.seconds)(candidate.headOption.map(_ => candidate)))
              .getOrElse(Vector.empty)
            found.find(r => offer.exists(o => r.text("offerId").contains(o))).orElse(found.headOption)
              .flatMap(_.text("rewardId")).foreach(ids.pair(Kind.Reward, claim, _))
        }

    private val ClaimRef = """(?:bonus-claims/)([0-9a-f-]{36})""".r
    private val BareId   = """/twins/bonus-claims/([0-9a-f-]{36})""".r

    /**
     * A step of another service, made the way that service tells this one. The answer is the recording's but for a
     * registration and a sign-in, which answer this replay's player.
     */
    private def standIn(r: Recording.Request, s: Recording.Step): Option[Recording.Response] =
      val body = r.body.getOrElse(Json.obj())
      val path = r.path.takeWhile(_ != '?')
      (r.method, path) match
        case ("POST", Register()) =>
          val p   = s"rp-${UUID.randomUUID()}"
          val cur = body.textOr("currency", "EUR")
          players += p
          current = Some(p)
          currency(p) = cur
          s.response.body.flatMap(_.text("playerId")).foreach(ids.pair(Kind.Player, _, p))
          body.text("email").foreach(byEmail(_) = p)
          val at = kit.now()
          playersFact(p, PlayerRegistered(p, at, None, None, None, Some(body.textOr("country", "GB")), Some(cur), None, at))
          awaitRecord(p, "registered")(_.registeredAt.isDefined)
          val out = s.response.body.collect { case o: ObjectNode => o.deepCopy() }.getOrElse(Json.obj())
          out.put("playerId", p)
          Some(Recording.Response(s.response.status, Some(out)))
        case ("POST", Login()) =>
          body.text("email").flatMap(byEmail.get).map { p =>
            current = Some(p)
            val out = s.response.body.collect { case o: ObjectNode => o.deepCopy() }.getOrElse(Json.obj())
            out.put("playerId", p)
            Recording.Response(s.response.status, Some(out))
          }
        case ("GET", PlayerTwin(p)) if players(p) =>
          if s.name.startsWith("observe: the player's duplicate check has ended") then check(p, kit.now(), ended = true)
          s.response.body.foreach(b => flagsOf(p, properties(b)))
          Some(s.response)
        case ("POST", PlayerTwin(p)) if players(p) =>
          // A check in progress since the time written; its end, when the twin says it is no longer pending.
          truth(body, "pending_duplicate_check").foreach { pending =>
            val at = body.text("registered_at").flatMap(t => scala.util.Try(Instant.parse(t)).toOption).getOrElse(kit.now())
            // The registration as the twin now dates it: the check's ten minutes run from it.
            if body.has("registered_at") then
              playersFact(p, PlayerRegistered(p, at, None, None, None, Some("GB"), Some(currency.getOrElse(p, "EUR")), None, at))
              awaitRecord(p, "registered")(_.registeredAt.exists(r => JDuration.between(r, at).abs.toMillis < 1000))
            if pending then check(p, at, ended = false)
            else if record(p).checkInProgress.isDefined then
              playersFact(p, PlayerDuplicateCheckEnded(p, "clear", kit.now()))
              awaitRecord(p, "check ended")(_.checkInProgress.isEmpty)
          }
          flagsOf(p, body)
          // The original kept the activation baseline on the player; the wallet keeps it on each record (W4).
          if body.text("bonus_activation_real_balance").isEmpty && body.has("bonus_activation_real_balance") then
            val cur = currency.getOrElse(p, "EUR")
            kit.wallet.open(p, cur).foreach(rec => kit.wallet.noBaseline(p, cur, rec.grantRef))
          Some(s.response)
        case ("POST", OfferTwin(id)) if body.has("name") =>
          val twin    = WireTranslation.offerTwin(body.asInstanceOf[ObjectNode], ctx)
          val created = kit.request("POST", kit.offersPath(""), Some(Json.render(twin)), kit.asOperator())
          val ours    = Json.parse(created.text).flatMap(_.text("id"))
          if ours.isEmpty then throw AssertionError(s"the offer twin $id was not written here (${created.status}): ${created.text}")
          s.response.body.flatMap(_.text("twinId")).map(_.stripPrefix("casino-bonus-offers/")).foreach(ids.pair(Kind.Offer, _, ours.get))
          ids.alias(id, ours.get)
          Some(s.response)
        case ("POST", OfferTwin(id)) if body.text("is_active").contains("false") =>
          kit.request("DELETE", kit.offersPath(s"/${ids.ours(id)}"), None, kit.asOperator()): Unit
          Some(s.response)
        case ("POST", PaymentTwin(_)) if body.text("payment_type").contains("deposit") =>
          val p = ids.ours(body.textOr("customer_ref", "").split(':').last)
          val cur = body.textOr("currency", "EUR")
          deposit(p, cur, WireTranslation.minor(BigDecimal(body.textOr("amount", "0")), cur))
          Some(s.response)
        case ("POST", Deposit(p, cur)) if players(ids.ours(p)) =>
          deposit(ids.ours(p), cur, WireTranslation.minor(BigDecimal(body.textOr("amount", "0")), cur))
          Some(s.response)
        case ("POST", RewardWrite(id)) if body.has("activation_real_balance") =>
          resolve(id)
          val rewardId = ids.ours(id)
          for (p, cur) <- currency; rec <- kit.wallet.open(p, cur) if rec.grantRef == rewardId do kit.wallet.noBaseline(p, cur, rewardId)
          Some(s.response)
        case ("POST", RoundsWrite(id)) if body.text("status").contains("used") =>
          // The rounds played out at the provider: each left settles with nothing won.
          val rewardId = ids.ours(id)
          val g        = grant(rewardId)
          val p        = g.textOr("playerId", "")
          val count    = Option(g.get("freeSpins")).flatMap(_.long("spinCount")).getOrElse(0L)
          (rounds.getOrElse(rewardId, 0) until count.toInt).foreach(_ => round(p, rewardId, 0L))
          Some(s.response)
        case ("POST", Launch()) =>
          for t <- s.response.body.flatMap(_.text("launchToken")); p <- body.text("playerId") do tokens(t) = ids.ours(p)
          Some(s.response)
        case ("POST", "/test/free-spin") =>
          // A round the provider played; one it refused (no rounds left) settles nothing.
          if s.response.status == 200 then
            for p <- body.text("launchToken").flatMap(tokens.get); (rewardId, _) <- roundsOf(p) do
              round(p, rewardId, WireTranslation.minor(BigDecimal(body.textOr("winAmount", "0")), currency.getOrElse(p, "EUR")))
          Some(s.response)
        case ("POST", WalletMove(_, _, _)) | ("POST", "/debit") | ("POST", "/credit") | ("POST", "/payout") =>
          cause = "loss"
          Some(s.response)
        case ("POST", Adjust(_)) =>
          cause = "adjustment"
          Some(s.response)
        case ("GET", FundsTwin(id)) =>
          // The wallet's record of a reward: its end, when it shows one, is the wallet's fact.
          s.response.body.map(properties).foreach { f =>
            val p = f.text("player_id").orElse(f.text("player_ref")).map(ids.ours).getOrElse(current.getOrElse(""))
            recordShown(id, p, f)
          }
          Some(s.response)
        case ("GET", BonusStatus(p)) =>
          // The wallet's bonus status lists the player's records, each with its status.
          s.response.body.toVector.flatMap(_.objects("restrictedFunds")).foreach { f =>
            f.text("grantRef").foreach(g => recordShown(g.stripPrefix("bonus-claims/"), ids.ours(p), f))
          }
          Some(s.response)
        case (_, KillSwitch()) =>
          if s.name.startsWith("setup: turn detection off") || s.name.startsWith("setup: enable the kill switch") then
            kit.players.multiAccountDetection = false
          Some(s.response)
        case _ => None

    // ── this service's routes ──────────────────────────────────────────────

    private def as(request: WireTranslation.Request): Seq[(String, String)] = request.caller match
      case Caller.Player     => kit.asPlayer(current.getOrElse(""))
      case Caller.Operator   => kit.asOperator()
      case Caller.Engagement => kit.asService("engagement")

    /** This service's answer in the recording's terms: the brand's slug as the recording's, players by the original's ids. */
    private def back(n: JsonNode): JsonNode =
      val named = players.toVector.foldLeft(Json.render(n).replace(kit.slug, brand))((t, p) => t.replace(p, ids.theirs(Kind.Player, p)))
      Json.parse(named).getOrElse(n)

    /** The answer read back, its ids paired with the recording's where it is their first sight. */
    private def translate(request: WireTranslation.Request, r: Recording.Response, s: Recording.Step): Recording.Response =
      val (_, first) = WireTranslation.answer(request, r.status, r.body, s.response.body, ctx)
      if r.status / 100 == 2 then WireTranslation.pairIds(request.row, s.response.body, first, ctx)
      val (status, body) = WireTranslation.answer(request, r.status, r.body, s.response.body, ctx)
      Recording.Response(status, body.map(back))

    /**
     * A read asked again while it differs from the recording: this service's reads are views and workflows, which
     * follow a write by moments where the original read its twin at once. An `observe:` step is asked until it shows
     * what the original's did, for up to twenty seconds; any other read, a `setup:` one too (a later step names what
     * it answers), for up to five. A difference is judged at the step's own place in the recording.
     */
    private def settled(s: Recording.Step, request: WireTranslation.Request, ask: => Recording.Response): Recording.Response =
      val single  = recording.copy(steps = Vector(s.copy(name = s.name.stripPrefix("observe: ").stripPrefix("setup: "), capture = Map.empty)))
      val until   = System.nanoTime + (if s.name.startsWith("observe:") then 20 else 5) * 1_000_000_000L
      val towards = divergences.exists(_.holds(recording, step))
      val here    = step
      var reply   = ask
      def unsettled(r: Recording.Response) =
        val (covered, other) = Replay.run(single, _ => translate(request, r, s))
          .map(d => d.copy(at = d.at.replace("$.steps[0]", s"$$.steps[$here]"))).partition(divergence(recording)(_).isDefined)
        other.nonEmpty || (towards && covered.isEmpty)
      while unsettled(reply) && System.nanoTime < until do
        Thread.sleep(200)
        reply = ask
      reply

    /**
     * The reward read, once the player's record knows it as read: the evaluator reads the record, which follows the
     * reward by moments (`IndexConsumer`), where the original's next claim read the twin the read had shown.
     */
    private def indexed(reward: JsonNode): Unit =
      for id <- reward.text("rewardId"); p <- reward.text("playerId"); status <- reward.text("status") do
        scala.util.Try(kit.eventually(s"$p's record knows $id $status", 5.seconds)(
          Some(record(p)).filter(_.rewards.get(id).exists(h => !h.claiming && h.status.wire == status)))): Unit

    def send(r: Recording.Request): Recording.Response =
      step += 1
      val s      = recording.steps(step)
      val filled = r.copy(path = fill(r.path), body = r.body.map(b => Json.parse(fill(Json.render(b))).getOrElse(b)))
      if filled.method == "GET" && filled.path.startsWith("/v1/casino/bonus/free-spins/spin-values") && s.response.status == 502 then
        kit.provider.spinValuesAnswer = Some(Answer(502, """{"error":"provider_unavailable"}"""))
      val reply = standIn(filled, s).getOrElse(sendOurs(filled, s))
      s.response.body.foreach(learn)
      reply

    private def sendOurs(filled: Recording.Request, s: Recording.Step): Recording.Response =
      (ClaimRef.findAllMatchIn(filled.path + filled.body.fold("")(Json.render)).map(_.group(1)) ++
        BareId.findAllMatchIn(filled.path).map(_.group(1))).foreach(resolve)
      WireTranslation.request(filled.method, filled.path, filled.body, ctx) match
        case Translated.Ours(request) =>
          def ask() = answered(kit.request(request.method, request.path, request.body.map(Json.render), as(request)))
          val reply = if request.method == "GET" && !s.name.startsWith("time:") then settled(s, request, ask()) else ask()
          if (request.row == Row.Reward || request.row == Row.FreeRounds) && reply.status == 200 then reply.body.foreach(indexed)
          translate(request, reply, s)
        case _ => s.response

  def replay(recording: Recording): Vector[Replay.Difference] =
    val kit    = SharedKit.kit
    val scoped = Parity.scoped(recording)
    kit.reset()
    Detection.forget()
    seed(kit)
    // Every step is sent, in order; a status that differs carries the answer observed, whose refusal code says why.
    val answers   = Vector.newBuilder[Recording.Response]
    val replaying = Replaying(kit, scoped)
    try
      val differences = Replay.run(scoped, r => { val o = replaying.send(r); answers += o; o })
      val observed    = answers.result()
      differences.map { d =>
        StatusAt.findFirstMatchIn(d.at).flatMap(m => observed.lift(m.group(1).toInt)).flatMap(_.body)
          .fold(d)(b => d.copy(observed = s"${d.observed} $b"))
      }
    finally
      kit.reset()
      Detection.forget()

  /** The provider's games and spin values the recordings' free rounds use (fake-st8's catalogue). */
  private def seed(kit: Kit): Unit =
    kit.provider.game("book-of-ra", "free_spins")
    kit.provider.spinValues("EUR", "0.10")
    kit.provider.spinValues("ZAR", "2.00")
    kit.provider.spinValues("GBP", "0.10")

  private val StatusAt = """^\$\.steps\[(\d+)\]\.response\.status$""".r
