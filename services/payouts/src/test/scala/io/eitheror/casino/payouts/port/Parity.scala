package io.eitheror.casino.payouts.port

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.contract.port.{JsonPath, Recording, Replay}
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.clients.Gateway
import io.eitheror.casino.payouts.it.{FakeGateway, FakeWallet, Kit, Reply, SharedKit}
import io.eitheror.casino.payouts.player.*
import io.eitheror.casino.payouts.request.{PayoutStatus, WithdrawalRequest}
import io.eitheror.casino.payouts.terminals.{Refresh, TerminalCatalogue}

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Replays one recording of tests/port/payouts against the shared kit (ankka-port, section 7). A step on
 * one of this service's routes is translated by [[WireTranslation]], sent as whoever the original's step was
 * signed in as, and its answer read back in the recording's terms. A step of another service that changes
 * what this one knows is made the way that service tells this one (contracts/wire.md is the route table;
 * the stand-ins are below), and answered as recorded; every other step is answered as recorded.
 */
object Parity:
  val root: Path      = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize
  val directory: Path = root.resolve("tests/port/payouts")

  def recordings(directory: Path = directory): Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.filter(p => p.toString.endsWith(".json") && !p.getFileName.toString.startsWith("suite-result")).toVector.sorted

  /**
   * Where the service answers differently from the original on purpose: a row of plan.md's *Divergences*
   * (named by its first words in `row`), the answer's fields it covers, and the steps where it holds.
   */
  final case class Divergence(row: String, fields: Seq[String], why: String, holds: (Recording, Recording.Step) => Boolean):
    def reached(recording: Recording): Boolean = recording.steps.exists(holds(recording, _))
    def covers(field: String): Boolean         = fields.exists(f => field == f || field.startsWith(f + ".") || field.startsWith(f + "["))

  /** Steps of the recordings whose scenario starts with one of `scenarios`, named by the start of their own names. */
  private def at(scenarios: String*)(steps: String*): (Recording, Recording.Step) => Boolean =
    (r, s) => scenarios.exists(r.scenario.startsWith) && steps.exists(s.name.startsWith)

  val divergences: Vector[Divergence] = Vector(
    Divergence("A Gigadat request missing its method fields", Seq(".status", ".body"),
      "refused at the request; the original parked it for manual payout at approval",
      at("a Gigadat request missing its method fields")("request a withdrawal naming no method", "approve")),
    Divergence("A provider's rejection at initiation", Seq(".body.status", ".body.payoutStatus"),
      "the request fails and the hold returns; the original parked it awaiting manual payout",
      at("a request rejected at initiation")("approve", "an operator reads the request")),
    Divergence("A caller the route's ACL refuses", Seq(".body"), "ankka's refusal body, not the original's sentence",
      at("an operator whose roles name another brand")("an operator of another brand opens the queue")),
    Divergence("The turnover refusal names no amount remaining", Seq(".body.totalRemaining"), "the wallet's preview names none",
      at("a request while deposit turnover is pending")("request a withdrawal")),
    Divergence("The queue's player search is a player id", Seq(".body.requests"),
      "the driver searched by its run's tag, which only the players' emails held",
      at("a queue filtered by")("filter the queue by")),
    Divergence("A destination listing shows which destination is the default", Seq(".body.destinations"),
      "the original's listing read every destination as not the default",
      at("a destination is saved only in a name that matches the player's [A.", "setting a second destination as default")("list the player's destinations"))
  )

  def diverging(recording: Recording): Vector[Divergence] = divergences.filter(_.reached(recording))

  private val StepField = """\$\.steps\[(\d+)\]\.response(.*)""".r

  def divergence(recording: Recording)(d: Replay.Difference): Option[Divergence] = d.at match
    case StepField(i, field) =>
      val step = recording.steps.lift(i.toInt)
      divergences.find(v => v.covers(field) && step.exists(v.holds(recording, _)))
    case _ => None

  /** Whether a recorded step is one of the original's payout routes this service serves (contracts/wire.md). */
  def ours(r: Recording.Request): Boolean =
    scala.util.Try(WireTranslation.request(r.method, r.path, None, WireTranslation.Context("b")).isDefined).getOrElse(false)

  // ── the recording, scoped to what it wrote ───────────────────────────────

  private val TerminalTwin = """/twins/eitheror-terminal/([^/?]+)""".r

  /**
   * The terminals a recording wrote, by the id its listings name them with: the driver's by its twin's uuid
   * (the answer to its write), scenario 251's by the id in its body; each to the id the replay gives it.
   */
  private def written(recording: Recording): Map[String, String] =
    recording.steps.flatMap { s =>
      s.request.path match
        case TerminalTwin(_) if s.request.method == "POST" && s.request.body.exists(_.has("gateway")) =>
          val id = s.request.body.flatMap(_.text("id")).getOrElse("")
          Vector(id -> id) ++ s.response.body.flatMap(_.text("twinId")).map(_.stripPrefix("eitheror-terminal/") -> id)
        case _ => Vector.empty
    }.toMap

  /**
   * The recording with each terminal listing narrowed to the terminals it wrote, named as the replay names
   * them. The original listed every terminal in the brand's index, which held other runs' terminals
   * (scenario 251 leaves its own, and the brand's seeded one); those are no part of the scenario.
   */
  def scoped(recording: Recording): Recording =
    val own = written(recording)
    recording.copy(steps = recording.steps.map(shaped).map { s =>
      if s.request.method != "GET" || !s.request.path.contains("/withdrawal-terminals") then s
      else
        val body = s.response.body.collect { case o: ObjectNode =>
          val out  = o.deepCopy()
          val kept = o.objects("terminals").filter(t => t.text("terminalId").exists(own.contains)).map { t =>
            val c = t.deepCopy[ObjectNode](); c.put("terminalId", own(t.text("terminalId").get)); c
          }
          out.set[JsonNode]("terminals", Json.arr(kept*)); out
        }
        s.copy(response = s.response.copy(body = body.orElse(s.response.body)), vary = s.vary.filterNot(_._1.startsWith("$.terminals")))
    })

  /**
   * A step on one of this service's routes with its answer read the way its row compares it
   * ([[WireTranslation.recordedShape]]), and its `vary` paths moved to where the shaped answer holds the same
   * fields: a listing narrowed to its pending rows holds them at other indices.
   */
  private def shaped(s: Recording.Step): Recording.Step =
    val row = scala.util.Try(WireTranslation.request(s.request.method, s.request.path, s.request.body, WireTranslation.Context("b")))
      .toOption.flatten.map(_.row)
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

  private val Placeholder = """\{([A-Za-z0-9]+)\}""".r
  private val Bearer      = """Bearer \{([A-Za-z0-9]+)\}""".r
  private val Register    = """/v1/casino/players/[^/]+/register""".r
  private val Login       = """/v1/casino/players/[^/]+/login""".r
  private val PlayerTwin  = """/twins/casino-[^/]+/([^/?]+)""".r
  private val WalletTwin  = """/twins/wallet/([^/?]+)""".r
  private val Deposit     = """/internal/v1/wallet/[^/]+/([^/]+)/([A-Z]{3})/deposit""".r
  private val RequestTwin = """/twins/withdrawal-request/([^/?]+)""".r
  private val Grant       = """/v1/casino/bonus/grant/[^/]+/([^/]+)""".r
  private val BonusStatus = """/v1/wallet/[^/]+/([^/]+)/[A-Z]{3}/bonus/status""".r
  private val PlayerRoute = """/v1/casino/brands/[^/]+/players/([^/]+)/.*""".r

  /** The currencies a terminal is listed in: the driver's players are EUR, Gigadat's terminals CAD's. */
  private val Currencies = Vector("EUR", "GBP", "CAD")

  private final class Replaying(kit: Kit, recording: Recording):
    private var step     = -1 // Replay.run sends the steps in order
    private val minted   = scala.collection.mutable.LinkedHashMap.empty[String, String]
    private val captured = scala.collection.mutable.Map.empty[String, String] // the answers' captures, by the recording's names
    private val byEmail  = scala.collection.mutable.Map.empty[String, String]
    private val players  = scala.collection.mutable.LinkedHashSet.empty[String]
    private val twins    = scala.collection.mutable.LinkedHashMap.empty[String, ObjectNode] // the terminal twins written, by id
    private val brand    = recording.original.get("brand").filter(_.nonEmpty).getOrElse("spinvibe")

    private def fresh = java.util.UUID.randomUUID().toString.take(8)

    /**
     * A value for a placeholder no answer captured: an address or a terminal id minted per replay (entities
     * outlive a test), and a wallet twin named by its player's id, as the original derives one from the other.
     */
    private def value(name: String): String =
      if name.endsWith("alletTwin") then
        val owner = name.stripSuffix("WalletTwin").stripSuffix("walletTwin")
        captured.getOrElse(if owner.isEmpty then "playerId" else owner + "PlayerId", s"{$name}")
      else
        // An address holds the run's tag, as the original's driver's did, so the queue's search finds it.
        val email = name.toLowerCase.contains("email")
        val tag   = if email then value("tag") else ""
        minted.getOrElseUpdate(name, if email then s"${name.toLowerCase}-$tag-$fresh@example.test" else s"$name-$fresh")

    private def fill(text: String): String =
      Placeholder.replaceAllIn(text, m => java.util.regex.Matcher.quoteReplacement(value(m.group(1))))

    private def answered(r: Reply): Recording.Response =
      Recording.Response(r.status, Option.when(r.text.trim.nonEmpty)(Json.parse(r.text).getOrElse(Json.mapper.getNodeFactory.textNode(r.text))))

    private def record(p: String) = kit.components.forEventSourcedEntity(EntityId(p))

    /** A recorded decimal amount in minor units: the original's request bodies speak major units. */
    private def minor(n: JsonNode, field: String, currency: String = "EUR"): Long =
      n.text(field).map(a => WireTranslation.minor(BigDecimal(a), currency)).getOrElse(0L)

    // ── the stand-ins ──────────────────────────────────────────────────────

    /** The terminal as the gateway lists it, from the original's terminal twin. */
    private def row(id: String, t: ObjectNode): ObjectNode =
      val gateway = t.textOr("gateway", "")
      FakeGateway.terminal(id, gateway, name = t.textOr("name", id), scheme = t.textOr("scheme", ""),
        active = t.text("status").forall(_ == "active"), destination = t.bool("requiresPayoutDestination").getOrElse(false),
        coins = t.text("supportedCurrencies").toVector.flatMap(_.split(',').map(_.trim).filter(_.nonEmpty)),
        hasStatus = gateway == "unityfinance", withdrawals = t.bool("supportsWithdrawal").getOrElse(false))

    /** The gateway's listing and the brand's catalogue, as psp-gateway's would read once the twin is written. */
    private def list(): Unit =
      Currencies.foreach { c =>
        val rows = twins.toVector.map(row)
        kit.gateway.terminals(c, rows*)
        kit.components.forKeyValueEntity(EntityId(kit.slug)).call(TerminalCatalogue.refresh)
          .invoke(Refresh(c, rows.flatMap(Gateway.terminal), kit.now())): Unit
      }

    /** The wallet's preview while a bonus is active: a forfeit to confirm, and no more than the real balance. */
    private def bonus(p: String, amount: Long): Unit =
      kit.wallet.previewBy(p) { asked =>
        if kit.wallet.balance(p).exists(_ < asked) then FakeWallet.preview(refusal = Some("INSUFFICIENT_FUNDS"))
        else FakeWallet.preview(confirmationRequired = true, bonusBalance = amount, totalForfeit = amount)
      }

    /**
     * A step of another service, made the way that service tells this one: a registration, a sign-in, an
     * identity decision, a deposit, a terminal, pending turnover, a bonus and the provider's silence. The
     * answer is the recording's but for a registration and a sign-in, which answer this replay's player.
     */
    private def standIn(r: Recording.Request, s: Recording.Step): Option[Recording.Response] =
      val body = r.body.getOrElse(Json.obj())
      (r.method, r.path.takeWhile(_ != '?')) match
        case ("POST", Register()) =>
          val p = s"pp-${java.util.UUID.randomUUID()}"
          players += p
          body.text("email").foreach { e =>
            byEmail(e) = p
            record(p).call(PlayerPayouts.seeEmail).invoke(SeeEmail(Personal(e), kit.now())): Unit
          }
          for first <- body.text("firstName"); family <- body.text("lastName") do
            record(p).call(PlayerPayouts.seeNames).invoke(SeeNames(Personal(first), Personal(family), kit.now())): Unit
          body.text("currency").foreach(c => record(p).call(PlayerPayouts.seeCurrency).invoke(SeeCurrency(c, kit.now())): Unit)
          Some(Recording.Response(201, Some(Json.obj("playerId" -> p, "status" -> "pending_email_verification"))))
        case ("POST", Login()) =>
          // The recorded answer naming this replay's player; a token the steps after it sign in with.
          body.text("email").flatMap(byEmail.get).map { p =>
            val out = s.response.body.collect { case o: ObjectNode => o.deepCopy() }.getOrElse(Json.obj())
            out.put("playerId", p)
            if s.capture.nonEmpty then out.put("token", s"player|$p"): Unit
            Recording.Response(s.response.status, Some(out))
          }
        case ("POST", PlayerTwin(p)) if players(p) && body.has("kyc_status") =>
          Kyc.from(body.textOr("kyc_status", "")).foreach(k => record(p).call(PlayerPayouts.seeKyc).invoke(SeeKyc(k, kit.now())): Unit)
          Some(s.response)
        case ("POST", Deposit(p, currency)) if players(p) =>
          kit.wallet.balanceFor(p, kit.wallet.balance(p).getOrElse(0L) + minor(body, "amount", currency))
          record(p).call(PlayerPayouts.seeDeposit).invoke(SeeDeposit(body.textOr("pspId", ""), body.text("scheme"), kit.now())): Unit
          Some(s.response)
        case ("POST", WalletTwin(p)) if players(p) && body.has("deposit_turnover_pending") =>
          if body.long("deposit_turnover_pending").exists(_ > 0) then
            kit.wallet.previewFor(p, FakeWallet.preview(refusal = Some("WITHDRAWAL_BLOCKED_TURNOVER")))
          else kit.wallet.clearPreview(p)
          Some(s.response)
        case ("POST", TerminalTwin(key)) =>
          val id     = body.text("id").getOrElse(key)
          val merged = twins.getOrElseUpdate(id, Json.obj())
          body.properties.asScala.foreach(e => merged.set[JsonNode](e.getKey, e.getValue))
          // A provider without the terminal's withdrawal credentials refuses its payouts at initiation.
          body.text("properties").flatMap(Json.parse).filter(_.has("withdrawalApiKey"))
            .filter(_.text("withdrawalApiKey").isEmpty).foreach(_ => kit.gateway.rejectAt(id, "missing_credentials"))
          list()
          Some(s.response)
        case ("POST", RequestTwin(id)) if body.text("payout_status").contains("NEEDS_REVIEW") =>
          // The original's driver marks the payout; here the provider has no record of it when asked.
          kit.gateway.statusFor(id, FakeGateway.answer(id, "no_record"))
          scala.util.Try(kit.eventually(s"$id marked needs review", 20.seconds)(
            Some(kit.components.forEventSourcedEntity(EntityId(id)).call(WithdrawalRequest.get).invoke())
              .filter(_.payoutStatus == PayoutStatus.NeedsReview))): Unit
          Some(s.response)
        case ("POST", Grant(p)) if players(p) =>
          bonus(p, minor(body, "bonusAmount", body.textOr("currency", "EUR")))
          kit.wallet.bonusFor(p, s"bonus-$fresh")
          Some(s.response)
        case ("GET", BonusStatus(p)) if players(p) =>
          // Once the wallet reports the bonus no longer active (converted, say), nothing is left to forfeit.
          if !s.response.body.flatMap(_.text("bonusStatus")).forall(_ == "ACTIVE") then
            kit.wallet.clearPreview(p)
            kit.wallet.clearBonus(p)
          Some(s.response)
        case _ => None

    // ── this service's routes ──────────────────────────────────────────────

    /** Who the original's step was, by the token its `Authorization` named; with none, whose route it is. */
    private def as(recorded: Recording.Request, request: WireTranslation.Request): Seq[(String, String)] =
      request.caller match
        case WireTranslation.Caller.Gateway => kit.asGateway
        case WireTranslation.Caller.AsRecorded =>
          recorded.headers.collectFirst { case (k, v) if k.equalsIgnoreCase("authorization") => v } match
            // The driver's operator administered the brand: withdrawal-config asks for brand_admin.
            case Some(Bearer("operatorToken")) => kit.asOperator(roles = s"brand_${kit.slug},brand_admin,brand_finance")
            case Some(Bearer("outsiderToken")) => kit.asOperator("outsider", "brand_elsewhere,brand_finance")
            case Some(Bearer(name)) if captured.get(name).exists(_.startsWith("player|")) =>
              kit.asPlayer(captured(name).stripPrefix("player|"))
            case _ =>
              request.path match
                case PlayerRoute(p) => kit.asPlayer(p)
                case _              => kit.asOperator()

    /** This service's answer in the recording's terms: the brand's slug as the recording's, minted values as their placeholders. */
    private def back(n: JsonNode): JsonNode =
      val text = minted.toVector.sortBy(-_._2.length).foldLeft(Json.render(n).replace(kit.slug, brand))((t, kv) => t.replace(kv._2, s"{${kv._1}}"))
      Json.parse(text).getOrElse(n)

    /**
     * A read asked again while it differs from the recording: this service's reads are views and workflows,
     * which follow a write by moments where the original read its twin at once. An `observe:` step is asked
     * until it shows what the original's did, for up to twenty seconds (a poll may come between); any other
     * read for up to five.
     */
    private def settled(s: Recording.Step, request: WireTranslation.Request, ask: => Recording.Response): Recording.Response =
      val single  = recording.copy(steps = Vector(s.copy(name = s.name.stripPrefix("observe: "), capture = Map.empty)))
      val until   = System.nanoTime + (if s.name.startsWith("observe:") then 20 else 5) * 1_000_000_000L
      val towards = divergences.exists(_.holds(recording, s))
      def translated(r: Recording.Response) =
        val (status, body) = WireTranslation.answer(request, r.status, r.body, s.response.body)
        Recording.Response(status, body.map(back))
      var reply = ask
      def unsettled(r: Recording.Response) =
        val (covered, other) = Replay.run(single, _ => translated(r)).partition(divergence(single)(_).isDefined)
        other.nonEmpty || (towards && covered.isEmpty)
      while unsettled(reply) && System.nanoTime < until do
        Thread.sleep(200)
        reply = ask
      reply

    def send(r: Recording.Request): Recording.Response =
      step += 1
      val s      = recording.steps(step)
      val filled = r.copy(path = fill(r.path), body = r.body.map(b => Json.parse(fill(Json.render(b))).getOrElse(b)))
      val reply  = standIn(filled, s).getOrElse(sendOurs(filled, s))
      s.capture.foreach((name, at) => reply.body.flatMap(JsonPath.get(_, at)).foreach(v => captured(name) = v.asText()))
      reply

    private def sendOurs(filled: Recording.Request, s: Recording.Step): Recording.Response =
      val ctx = WireTranslation.Context(kit.slug)
      if !ours(filled) then s.response
      else
        val request = WireTranslation.request(filled.method, filled.path, filled.body, ctx).get
        def ask()   = answered(kit.request(request.method, request.path, request.body.map(Json.render), as(filled, request)))
        val reply   = if request.method == "GET" && (!Replay.arranged(s) || s.name.startsWith("observe:")) then settled(s, request, ask()) else ask()
        val (status, body) = WireTranslation.answer(request, reply.status, reply.body, s.response.body)
        Recording.Response(status, body.map(back))

  def replay(recording: Recording): Vector[Replay.Difference] =
    val kit    = SharedKit.kit
    val scoped = Parity.scoped(recording)
    kit.reset()
    try Replay.run(scoped, Replaying(kit, scoped).send)
    finally kit.reset()
