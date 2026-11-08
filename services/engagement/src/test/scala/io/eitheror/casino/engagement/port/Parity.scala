package io.eitheror.casino.engagement.port

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.port.{JsonPath, Recording, Replay}
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.api.Signature
import io.eitheror.casino.engagement.config.{Secrets, Seed}
import io.eitheror.casino.engagement.events.{Classes, SendClass}
import io.eitheror.casino.engagement.it.{Answer, FakeCrm, Kit, SseReader}
import io.eitheror.casino.engagement.port.WireTranslation.{Caller, StandIn, Step}
import io.eitheror.casino.engagement.profile.Suppression
import io.eitheror.casino.engagement.timers.DepositDayReset

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Replays one recording of tests/port/engagement against the shared kit (ankka-port, section 7; contracts/wire.md).
 * Engagement's own routes are sent and their answers compared; the original's player ids are kept as this service's,
 * so a step's captures and the CRM calls name the same people. Every other service's step is answered as recorded,
 * the ones that change what engagement knows first made as the facts that service publishes (the stand-ins):
 *
 *  - registration, verification, sign-in, the tier, the backoffice's profile edit and inbox (`players`): `casino.players`,
 *    and the verification email through the send route, as `players` asks for it;
 *  - the RG limits, cooling-off and self-exclusion (`responsible-gambling`): `casino.players`;
 *  - the KYC documents and status (`kyc`): `casino.players`;
 *  - a deposit notified (`wallet`): `deposit_confirmed` on `money.transactions`;
 *  - the realm, the substrate's twins, the wallet's and rewards' reads, the game provider: nothing.
 *
 * Then the CRM calls are compared, per player: the identifies by attribute names and types (the original identifies
 * on every twin change, on two paths, so their number is not the behaviour; plan.md's first divergence), the tracks by
 * event name and data keys in order, and the transactional emails by message id and data keys in order.
 */
object Parity:
  val root: Path      = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize
  val directory: Path = root.resolve("tests/port/engagement")

  def recordings(directory: Path = directory): Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.filter(_.toString.endsWith(".json")).toVector.sorted

  /**
   * Where the service answers differently on purpose: the row of plan.md's *Divergences* (by its first words), what
   * it covers (a difference's place), and the recordings it must still hold in.
   */
  final case class Divergence(row: String, why: String, covers: Replay.Difference => Boolean, holds: Recording => Boolean,
      steps: Recording.Step => Boolean = _ => false)

  private def under(prefix: String*)(d: Replay.Difference) = prefix.exists(d.at.startsWith)
  private def named(scenario: String)(r: Recording)        = r.scenario.startsWith(scenario)
  /** The CRM calls a recording holds: its `outbound` (spec 001's contracts/recording.md), which `Recording` does not read. */
  def outbound(r: Recording): Vector[JsonNode] =
    val text   = Files.readString(r.path)
    // A run's people are the ones its registrations and sign-ins answered; a list's answer names other runs' too.
    val steps = Json.parse(text).toVector.flatMap(_.path("steps").elements.asScala)
      .filter(s => s.path("request").path("path").asText.matches(".*/(register|login)$"))
      .map(s => Json.render(s.path("response").path("body"))).mkString
    val bodies = Json.parse(text).toVector.flatMap(_.path("outbound").elements.asScala).map(_.path("request").path("body"))
    // The calls of both runs are attached, by the people they name; the recorded steps are one run's.
    val people = bodies.map(_.path("identifiers").path("id").asText).filter(_.nonEmpty).distinct
      .filter(p => steps.contains(p) || p.startsWith("_seed_")).toSet
    val emails = bodies.filter(b => people(b.path("identifiers").path("id").asText))
      .flatMap(b => Option(b.path("attributes").get("email")).map(_.asText)).toSet
    bodies.filter(b => people(b.path("identifiers").path("id").asText) || b.has("transactional_message_id") && emails(b.path("to").asText))

  /** How many runs the recording holds; the seed person is the same in each, so its calls are every run's. */
  def runs(r: Recording): Int = Json.parse(Files.readString(r.path)).map(_.path("runs").asInt(1)).filter(_ > 0).getOrElse(1)

  private def identifies(attribute: String)(r: Recording) =
    outbound(r).exists(b => b.path("action").asText == "identify" && b.path("attributes").has(attribute) &&
      !b.path("identifiers").path("id").asText.startsWith("_seed_"))

  private val Absent = Vector("affiliate_id", "last_device_os", "language")
  private val New    = Vector("marketing_suppressed", "suppression_reason")
  private val Seen   = Vector("kyc_status", "real_money", "bonus_money", "total_balance")

  private def marketing(event: String) = Classes.of(event).contains(SendClass.Marketing)
  private val eventOf: Map[String, String] = Seed.events.map((t, e) => s"stub-$t" -> e).toMap

  /**
   * The original's calls a fail-closed service does not make (FR-011): a marketing track or email for a player whose
   * last identify before it said `email_subscribed` false. The original never enforced it.
   */
  def suppressed(bodies: Vector[JsonNode]): Vector[Int] =
    val subscribed = mutable.Map.empty[String, Boolean]
    val emails     = mutable.Map.empty[String, String]
    bodies.zipWithIndex.flatMap { (b, i) =>
      val person = b.path("identifiers").path("id").asText
      if person.startsWith("_seed_") then None
      else if b.has("transactional_message_id") then
        val to = emails.get(b.path("to").asText)
        Option.when(eventOf.get(b.path("transactional_message_id").asText).exists(marketing) && to.exists(p => !subscribed.getOrElse(p, false)))(i)
      else b.path("action").asText match
        case "identify" =>
          subscribed(person) = b.path("attributes").path("email_subscribed").asText == "true"
          Option(b.path("attributes").get("email")).foreach(e => emails(e.asText) = person)
          None
        case "event" => Option.when(marketing(b.path("name").asText) && !subscribed.getOrElse(person, false))(i)
        case _       => None
    }

  private def suppressedIn(r: Recording) = suppressed(outbound(r)).nonEmpty

  private def following(bodies: Vector[JsonNode], i: Int, same: JsonNode => Boolean): Option[JsonNode] =
    bodies.drop(i + 1).find(same)

  /**
   * The original's `cooling_off_set` before a self-exclusion's `self_exclusion_activated` (its twin set the cooling-off
   * flag on the way): `cooling_off_set` comes from a cooling-off case alone (FR-006's table), and responsible-gambling
   * opens a self-exclusion case for a self-exclusion.
   */
  def selfExcluded(bodies: Vector[JsonNode]): Vector[Int] =
    bodies.zipWithIndex.collect {
      case (b, i) if b.path("action").asText == "event" && b.path("name").asText == "cooling_off_set" &&
          following(bodies, i, n => n.path("action").asText == "event" && n.path("identifiers").path("id") == b.path("identifiers").path("id"))
            .exists(_.path("name").asText == "self_exclusion_activated") => i
      case (b, i) if b.path("transactional_message_id").asText == "stub-CoolingOffSet" &&
          following(bodies, i, n => n.has("transactional_message_id") && n.path("to") == b.path("to"))
            .exists(_.path("transactional_message_id").asText == "stub-SelfExclusionActivated") => i
    }

  /** The original's `kyc_rejected` for a document rejected while kyc's status stayed short of rejected (spec 009). */
  def documentRejected(r: Recording)(bodies: Vector[JsonNode]): Vector[Int] =
    val rejected = r.steps.exists(_.response.body.exists(b => Option(b.get("kycStatus")).exists(_.asText == "rejected")))
    if rejected then Vector.empty
    else bodies.zipWithIndex.collect {
      case (b, i) if !b.path("identifiers").path("id").asText.startsWith("_seed_") &&
          (b.path("name").asText == "kyc_rejected" || b.path("transactional_message_id").asText == "stub-KycRejected") => i
    }

  /** Each kind of the original's call the port leaves out on purpose, under the place its marks take. */
  def omitted(r: Recording): Vector[(String, Vector[Int])] =
    val all = outbound(r)
    Vector("suppressed" -> suppressed(all), "selfExcluded" -> selfExcluded(all), "documentRejected" -> documentRejected(r)(all))

  private val OperatorLimit = """/v1/casino/spinvibe/players/[^/]+/rg/limits/.*""".r
  private def operatorLimit(s: Recording.Step) =
    OperatorLimit.matches(s.request.path) && s.request.method != "GET" && s.response.status / 100 == 2
  private val limitEvents = Vector("deposit_limit_set", "loss_limit_set", "session_limit_set", "rg_limit_loosened",
    "DepositLimitSet", "LossLimitSet", "SessionLimitSet", "RgLimitLoosened")

  private def stream(s: Recording.Step) =
    s.request.method == "GET" && (s.request.path.startsWith("/v1/stream") || s.request.path.contains("/agent-stream?"))

  private def configPut(s: Recording.Step) =
    s.request.method == "PUT" && s.request.path.startsWith("/v1/casino/crm-config/") && !Replay.arranged(s)

  val divergences: Vector[Divergence] =
    Vector(Divergence("A durable send record per event", "one track and one email per event; the original's two paths made two",
      under("$.outbound.repeated"), _ => false)) ++
    Absent.map(a => Divergence("`player_name` without a display name; no `language`", s"no `$a` (R9)",
      under(s"$$.outbound.attributes.$a"), identifies(a))) ++
    New.map(a => Divergence("Marketing suppression enforced outbound", s"`$a` is new (clarify 2026-11-03)",
      under(s"$$.outbound.attributes.$a"), outbound(_).exists(b => b.path("action").asText == "identify" &&
        !b.path("identifiers").path("id").asText.startsWith("_seed_")))) ++
    Seen.map(a => Divergence("`kyc_status` and the balances once a fact carries them", s"`$a` only once seen (FR-008)",
      d => d.at == s"$$.outbound.attributes.$a" && d.observed == "absent", _ => false)) ++
    Vector(Divergence("`kyc_status` and the balances once a fact carries them", "the three balances together once a fact carries them (FR-008)",
      d => Seen.drop(1).exists(a => d.at == s"$$.outbound.attributes.$a") && d.expected == "absent", _ => false)) ++
    Vector(Divergence("Marketing suppression enforced outbound", "marketing not sent to a player without consent (clarify 2026-11-03)",
      under("$.outbound.suppressed"), r => suppressedIn(r))) ++
    Vector(
      Divergence("`cooling_off_set` from a cooling-off case alone", "no `cooling_off_set` before a self-exclusion (FR-006)",
        under("$.outbound.selfExcluded"), r => selfExcluded(outbound(r)).nonEmpty),
      Divergence("`kyc_rejected` when kyc's status becomes rejected", "no `kyc_rejected` for a document rejected (spec 009)",
        under("$.outbound.documentRejected"), r => documentRejected(r)(outbound(r)).nonEmpty),
      Divergence("An operator's change to a player's RG limit is told to the player", "an operator's limit change is sent (T052)",
        d => (d.at.startsWith("$.outbound.tracks") || d.at.startsWith("$.outbound.sends")) && d.expected == "absent" &&
          limitEvents.exists(e => d.observed.contains(s"$e,")),
        _.steps.exists(operatorLimit)),
      Divergence("The configuration's `PUT` answers the masked configuration", "the `PUT` answers the configuration, not `{ok: true}`",
        _ => false, _.steps.exists(configPut), configPut),
      Divergence("A stream refused before it opens answers ankka's problem", "the refusal's body carries its `status` (ankka's SSE)",
        d => d.at.endsWith(".body.status") && d.expected == "absent",
        // where the status itself is the same; the sign-in naming no player differs in it (the next row)
        r => r.steps.exists(s => stream(s) && s.response.status / 100 == 4) && !named("an operator's or a part's sign-in")(r)),
      Divergence("A sign-in naming no player is 403 `subject_unresolved`", "403 `subject_unresolved`, not 401 (contracts/routes.md)",
        _ => false, named("an operator's or a part's sign-in without a player id"),
        s => s.request.path.startsWith("/v1/stream") && s.response.status == 401)
    ) ++
    Vector(
      Divergence("`payment_failed` without the crypto and `expires_at` keys", "`payment_failed`'s crypto keys (R8)",
        d => d.at.startsWith("$.outbound.tracks") && d.expected.contains("payment_failed"), _ => false),
      Divergence("The streams' heartbeat and ping are named events", "named heartbeats, no `id:` (ankka's SSE)",
        d => d.at.contains(".frames") && (d.observed.contains("heartbeat") || d.observed.contains("ping") || d.at.endsWith(".id")),
        _.steps.exists(s => s.request.path.contains("stream") && s.response.body.exists(_.toString.contains("\"id\"")))),
      Divergence("Presence cluster-wide with `since`", "push-gateway refuses an operator's token 401 (research, T051)",
        d => d.recording.getFileName.toString.startsWith("an operator reading presence") && d.at.startsWith("$.steps"),
        named("an operator reading presence")),
      Divergence("Thirty-two triggers in the table and the seed", "the seed's thirty-two triggers; the original's commit has thirty (research)",
        d => d.at.contains("seeded") || d.at.contains("triggers.KycAwaitingDeposit") || d.at.contains("triggers.BankAccountAdded") ||
          d.at.contains(".tracks") && (d.observed.contains("kyc_awaiting_deposit") || d.observed.contains("bank_account_added")),
        r => r.steps.exists(s => !Replay.arranged(s) && (s.request.path.endsWith("/seed") ||
          s.request.method == "GET" && s.request.path.startsWith("/v1/casino/crm-config/") && s.response.body.exists(_.has("triggers")))))
    )

  def diverging(recording: Recording): Vector[Divergence] = divergences.filter(_.holds(recording))

  private val StepAt = """^\$\.steps\[(\d+)\].*""".r

  def divergence(recording: Recording)(d: Replay.Difference): Option[Divergence] =
    val step = d.at match
      case StepAt(i) => recording.steps.lift(i.toInt)
      case _         => None
    divergences.find(v => v.covers(d) || step.exists(v.steps))

  /** Replays `recording` on `kit` and answers every difference from the original. */
  def replay(kit: Kit, recording: Recording): Vector[Replay.Difference] = Runner(kit, recording).run()

  /** One recording's replay; kept apart so each recording starts from a clean CRM and its own state. */
  private final class Runner(kit: Kit, recording: Recording):
    private val captured = mutable.Map.empty[String, String]
    private val streams  = mutable.ArrayBuffer.empty[(Int, Recording.Step, SseReader)]
    private val kyc      = mutable.Map.empty[String, String]
    private val ordinals = mutable.Map.empty[String, Int]
    private val limits   = mutable.Map.empty[(String, String), Long]
    private val payments = mutable.Map.empty[String, (String, Long, String)]
    private var current  = Option.empty[String]
    private val path     = recording.path

    /**
     * The brand's keys as long as the original's and starting as they did, from the recording's first read of the
     * configuration, so the masked answers read alike; the keys themselves were never recorded.
     */
    private def keys(): Unit =
      val shown = recording.steps.find(s => s.request.method == "GET" && s.request.path.startsWith("/v1/casino/crm-config/"))
        .flatMap(_.response.body)
      for b <- shown; (field, name) <- Vector("trackApiKey" -> Secrets.track(kit.slug), "appApiKey" -> Secrets.app(kit.slug),
          "webhookSigningKey" -> Secrets.signing(kit.slug)) do
        Option(b.get(field)).map(_.asText).filter(_.nonEmpty).foreach(m => kit.secrets.put(name, m.takeWhile(_ != '*') + "k" * m.count(_ == '*')))
      io.eitheror.casino.engagement.EngagementRuntime.secrets.forget()

    private def fill(t: String) = captured.foldLeft(t)((acc, kv) => acc.replace(s"{${kv._1}}", kv._2))
    private def now()           = kit.now()

    def run(): Vector[Replay.Difference] =
      kit.crm.reset()
      kit.crm.expect(FakeCrm.SiteId, FakeCrm.TrackKey, FakeCrm.AppKey)
      kit.players.reset()
      kit.rewards.reset()
      val from = recording.steps.find(s => s.request.method == "GET" && s.request.path.startsWith("/v1/casino/crm-config/"))
        .flatMap(_.response.body).flatMap(b => Option(b.get("fromEmail"))).map(_.asText).getOrElse("")
      kit.configure(Seed.triggers.map(t => t -> s"stub-$t").toMap, fromEmail = from): Unit
      keys()
      try
        val answered = recording.steps.zipWithIndex.flatMap((s, i) => step(s, i))
        quiet()
        answered ++ streamed() ++ outbound()
      finally streams.foreach(_._3.close())

    private def step(recorded: Recording.Step, i: Int): Vector[Replay.Difference] =
      val step = recorded.copy(request = recorded.request.copy(path = fill(recorded.request.path),
        body = recorded.request.body.map(b => Json.parse(fill(Json.render(b))).get)))
      val at = s"$$.steps[$i].response"
      WireTranslation.of(step, kit.slug, current) match
        case Step.Ours(method, p, caller) =>
          compare(step, at, answer(kit.request(method, p, step.request.body.map(Json.render), as(caller))))
        case Step.Webhook(p) =>
          compare(step, at, webhook(step, p))
        case Step.Stream(p, caller) =>
          val reader = SseReader(kit.baseUrl + p, as(caller).toMap)
          streams += ((i, step, reader))
          // A stream that opens with `ready` is awaited by it; the agent's opens with none, its first frame a later step's.
          if step.response.status == 200 then
            if firstEvent(step) == "ready" then reader.next("ready", 10.seconds): Unit else reader.status: Unit
          Vector.empty
        case Step.StandFor(player, standIn) =>
          player.foreach(p => current = Some(p))
          standIn match
            case StandIn.Captured(t) => payments.remove(t).foreach((p, c, cur) => standFor(p, StandIn.Deposit(s"tx-${UUID.randomUUID()}", c, cur)))
            case StandIn.Payment(t, c, cur, p) => payments(t) = (p, c, cur)
            case StandIn.SigningKey(k) =>
              kit.secrets.put(Secrets.signing(kit.slug), k)
              io.eitheror.casino.engagement.EngagementRuntime.secrets.forget()
            case s => current.foreach(standFor(_, s))
          take(step, step.response)
          Vector.empty
        case Step.Reset =>
          kit.fireTimer(DepositDayReset.timerName(kit.slug))
          take(step, step.response)
          Vector.empty
        case Step.Answered =>
          take(step, step.response)
          Vector.empty

    private def as(caller: Caller): Seq[(String, String)] = caller match
      case Caller.Nobody   => kit.bearer("x").take(1)
      case Caller.Operator => kit.asOperator()
      case Caller.Player   => kit.bearer(s"player|${current.getOrElse("")}")

    private def answer(r: io.eitheror.casino.engagement.it.Reply): Recording.Response =
      Recording.Response(r.status, Json.parse(WireTranslation.back(r.text, kit.slug)).orElse(
        Option.when(r.text.nonEmpty)(com.fasterxml.jackson.databind.node.TextNode(r.text))))

    private def take(step: Recording.Step, observed: Recording.Response): Unit =
      step.capture.foreach((name, p) => observed.body.flatMap(JsonPath.get(_, p)).foreach(v => captured(name) = v.asText()))

    private def compare(step: Recording.Step, at: String, observed: Recording.Response): Vector[Replay.Difference] =
      take(step, observed)
      if Replay.arranged(step) then Vector.empty
      else
        val single = recording.copy(steps = Vector(step.copy(capture = Map.empty)))
        Replay.run(single, _ => observed).map(d => d.copy(at = d.at.replace("$.steps[0].response", at))).filterNot(masked)

    /** A key the configuration shows masked: the original's key was never recorded, so its mask is compared by shape. */
    private def masked(d: Replay.Difference): Boolean =
      val shape = "\"[^*\"]{0,3}\\*+\""
      Seq("trackApiKey", "appApiKey", "webhookSigningKey").exists(k => d.at.endsWith(s".$k")) && d.expected.matches(shape) &&
        d.observed.matches(shape)

    /** The webhook signed as the step's name says, rewards and players answering as the original's did. */
    private def webhook(step: Recording.Step, p: String): Recording.Response =
      val text = step.request.body.map(Json.render).getOrElse("")
      val ts   = now().getEpochSecond.toString
      val name = step.name.toLowerCase
      val player = step.request.body.flatMap(b => Option(b.get("player_id"))).map(_.asText).getOrElse("")
      step.request.body.map(_.path("action").asText) match
        case Some("grant_bonus")  => kit.rewards.answer(player, "award", Answer(step.response.status, step.response.body.map(Json.render).getOrElse("{}")))
        case Some("target_offer") => kit.rewards.answer(player, "target", Answer(step.response.status, step.response.body.map(Json.render).getOrElse("{}")))
        // players' inbox route answers as the original's did: a repeat, a suppressed player, one it does not hold.
        case Some("send_message") => kit.players.answer(player, Answer(step.response.status, step.response.body.map(Json.render).getOrElse("{}")))
        case _                    => ()
      val signing = kit.secrets.get(Secrets.signing(kit.slug))
      val signature =
        if name.contains("malformed") then Some("not-a-signature")
        else if name.contains("another key") then Some(Signature.sign("another-key", ts, text.getBytes(UTF_8)))
        else if name.contains("no signature") then None
        else Some(Signature.sign(signing.getOrElse(Kit.SigningKey), ts, text.getBytes(UTF_8)))
      val unkeyed = step.response.body.exists(_.toString.contains("webhook_not_configured"))
      if unkeyed then kit.secrets.delete(Secrets.signing(kit.slug))
      io.eitheror.casino.engagement.EngagementRuntime.secrets.forget()
      try answer(kit.request("POST", p, Some(text), Seq("X-CIO-Timestamp" -> ts) ++ signature.map("X-CIO-Signature" -> _)))
      finally if unkeyed then
        signing.foreach(kit.secrets.put(Secrets.signing(kit.slug), _))
        io.eitheror.casino.engagement.EngagementRuntime.secrets.forget()

    // The stand-ins.

    private def players(fact: f.CasinoPlayersFact): Unit = kit.publish(f.CasinoPlayers, fact.playerId, fact)

    private def standFor(p: String, s: StandIn): Unit = s match
      case StandIn.Registered(email, country, currency, dob, first, family) =>
        val at = now()
        // The driver's address is its own placeholder, never captured; the original's identify of the player holds it.
        val recorded = Parity.outbound(recording).find(_.path("identifiers").path("id").asText == p)
          .flatMap(b => Option(b.path("attributes").get("email"))).map(_.asText)
        val to = email.filterNot(_.startsWith("{")).orElse(recorded).getOrElse(s"$p@example.com")
        players(f.PlayerRegistered(p, at, None, None, None, country, currency, None, at, givenName = first.map(Personal(_)),
          familyName = family.map(Personal(_)), dateOfBirth = dob.map(Personal(_)), email = Some(Personal(to))))
        // players publishes the consents given at registration beside it (spec 008 FR-028); the suite's gave none.
        Vector("email", "sms").foreach(c => players(f.PlayerConsentChanged(p, c, false, "registration", at)))
        // players asks for the verification email when the player registers (FR-014).
        kit.json("POST", kit.engagementPath("/sends"), Json.obj("key" -> s"verify-$p", "playerId" -> p,
          "trigger" -> "EmailAddressVerification", "to" -> to, "link" -> s"https://${kit.slug}.example/verify?t=$p"),
          kit.asService("players")): Unit
      case StandIn.Verified => ()
      case StandIn.LoggedIn => players(f.PlayerLoggedIn(p, UUID.randomUUID().toString, now()))
      case StandIn.Limit(tpe, value, pending) =>
        val had = limits.contains((p, tpe))
        if pending then players(f.RgLimitLooseningRequested(p, tpe, None, value, None, now().plusSeconds(86400), now()))
        else
          value.fold(limits.remove((p, tpe)))(v => limits.put((p, tpe), v)): Unit
          players(f.RgLimitApplied(p, tpe, None, value, if value.isEmpty then "removed" else if had then "lowered" else "set", now()))
      case StandIn.CoolingOff(until) =>
        players(f.RgCaseOpened(p, s"case-${UUID.randomUUID()}", Suppression.CoolOffCase, "player",
          until.flatMap(u => scala.util.Try(Instant.parse(u)).toOption), now()))
      case StandIn.SelfExcluded(permanent) =>
        players(f.RgCaseOpened(p, s"case-${UUID.randomUUID()}", Suppression.SelfExclusionCase, "player", None, now(), Some(permanent)))
        players(f.PlayerSelfExcluded(p, "active", "self_excluded", "player", None, None, now(), None, now()))
      case StandIn.DocumentSubmitted(key) => players(f.PlayerKycSubmitted(p, key, category(key), now()))
      case StandIn.DocumentReviewed(key, decision, reason, status) =>
        players(f.PlayerKycDocumentReviewed(p, key, category(key), decision, reason, "op-1", now()))
        status.foreach(kycStatus(p, _))
      case StandIn.KycStatus(status) => kycStatus(p, status)
      case StandIn.KycShown(status)  => kycStatus(p, status)
      case StandIn.Duplicate(suspected) =>
        players(f.PlayerDuplicateCheckEnded(p, if suspected then "strong" else "clear", now()))
      case _: StandIn.Payment | _: StandIn.Captured | _: StandIn.SigningKey => ()
      case StandIn.Deposit(tx, cents, cur) =>
        val n = ordinals.updateWith(p)(o => Some(o.getOrElse(0) + 1)).get
        val d = f.DepositConfirmed(p, Currency.unsafe(cur), tx, MinorUnits.unsafe(cents), s"ref-$tx", n,
          SignedMinorUnits(cents * n), MinorUnits.unsafe(0L), now())
        kit.publish(f.MoneyTransactions, d.key, d)
      case StandIn.Tier(tier)              => players(f.VipTierChanged(p, None, tier, "op-1", now()))
      case StandIn.Profile(fields)         =>
        players(f.PlayerProfileUpdated(p, fields, None, None, None, None, None, None, None, None, now()))
      case StandIn.MessageCreated(id)      => players(f.PlayerMessageCreated(p, id, "service", now()))
      case StandIn.MessageRead(id)         => players(f.PlayerMessageRead(p, id, now()))

    private def category(key: String) = key.split('/').lastOption.map(_.takeWhile(c => c != '-')).getOrElse("other")

    /** A KYC status the answer reports, made a fact only when it changed. */
    private def kycStatus(p: String, status: String): Unit =
      val before = kyc.getOrElse(p, "unverified")
      if status != before then
        kyc(p) = status
        status match
          case "verified" => players(f.PlayerKycVerified(p, before, status, "operator", Some("op-1"), None, now()))
          case "rejected" => players(f.PlayerKycRejected(p, before, status, "operator", Some("op-1"), None, now()))
          case "pending"  => players(f.PlayerKycPending(p, before, status, now()))
          case _          => ()

    /** Until the CRM has had no call for two seconds: every send the replay caused has ended. */
    private def quiet(): Unit =
      var seen  = -1
      var still = 0
      val until = System.nanoTime + 60.seconds.toNanos
      while still < 4 && System.nanoTime < until do
        Thread.sleep(500)
        val n = kit.crm.calls.size
        if n == seen then still += 1 else { still = 0; seen = n }

    // The streams, compared once the recording has run: a frame the original sent later is a later step's.

    private def frames(n: Option[JsonNode]): Vector[JsonNode] = n.toVector.flatMap(_.elements.asScala)

    private def firstEvent(step: Recording.Step) = frames(step.response.body).headOption.map(_.path("event").asText).getOrElse("ready")

    private def streamed(): Vector[Replay.Difference] = streams.toVector.flatMap { (i, step, reader) =>
      val at       = s"$$.steps[$i].response"
      // The original's agent stream also heard the other suites' players sign in on the shared installation.
      val ours     = recording.steps.filter(s => s.request.path.matches(".*/(register|login)$"))
        .flatMap(_.response.body).flatMap(b => Option(b.get("playerId"))).map(_.asText).toSet
      val expected = frames(step.response.body).filterNot(_.has("comment"))
        .filterNot(n => Set("heartbeat", "ping")(n.path("event").asText))
        .filter(n => n.path("event").asText != "player_online" || ours(n.path("data").path("player_id").asText))
      val got      = () => reader.frames.filterNot((e, _) => e == "heartbeat" || e == "ping")
      if step.response.status == 200 then
        kit.testKit.eventually(s"step $i's frames", 10.seconds)(Option.when(got().size >= expected.size)(())): Unit
      if reader.status != step.response.status then
        Vector(Replay.Difference(path, s"$at.status", step.response.status.toString, reader.status.toString))
      else if step.response.status != 200 then
        // A refusal is an answer, not a stream: its body compared as any other.
        val single = recording.copy(steps = Vector(step.copy(capture = Map.empty)))
        Replay.run(single, _ => Recording.Response(reader.status, Json.parse(reader.body)))
          .map(d => d.copy(at = d.at.replace("$.steps[0].response", at)))
      else
        val observed = got().take(expected.size max got().size)
        (0 until (expected.size max observed.size)).toVector.flatMap { k =>
          val e = expected.lift(k)
          val o = observed.lift(k).map((ev, data) => (ev, Json.parse(data)))
          val eShape = e.map(n => (n.path("event").asText, keys(n.path("data"))))
          val oShape = o.map((ev, d) => (ev, d.map(keys).getOrElse(Vector.empty)))
          val id = e.filter(_.has("id")).map(_ => Replay.Difference(path, s"$at.frames[$k].id", "present", "absent")).toVector
          (if eShape == oShape then Vector.empty
           else Vector(Replay.Difference(path, s"$at.frames[$k]", eShape.toString, oShape.toString))) ++ id
        }
    }

    // The CRM calls.

    private def keys(n: JsonNode): Vector[String] = if n.isObject then n.fieldNames.asScala.toVector.sorted else Vector.empty

    private final case class Calls(identifies: Map[String, Map[String, String]], tracks: Map[String, Vector[(String, Vector[String])]],
        sends: Map[String, Vector[(String, Vector[String])]])

    private def person(b: JsonNode) = WireTranslation.back(b.path("identifiers").path("id").asText, kit.slug)

    private def calls(bodies: Vector[JsonNode], app: JsonNode => Boolean): Calls =
      val track   = bodies.filterNot(app)
      val profile = track.filter(_.path("action").asText == "identify").groupBy(person).view.mapValues { ids =>
        ids.flatMap(b => b.path("attributes").properties.asScala.map(e => e.getKey -> e.getValue.getNodeType.toString)).toMap
      }.toMap
      val known  = profile.values.flatMap(_.keys).toSet
      def data(n: JsonNode) = keys(n).filterNot(known)
      val events = track.filter(_.path("action").asText == "event").groupBy(person).view
        .mapValues(_.map(b => (b.path("name").asText, data(b.path("attributes"))))).toMap
      val emails = bodies.filter(app).groupBy(b => WireTranslation.back(b.path("to").asText, kit.slug)).view
        .mapValues(_.map(b => (b.path("transactional_message_id").asText, data(b.path("message_data"))))).toMap
      Calls(profile, events, emails)

    private def outbound(): Vector[Replay.Difference] =
      val all      = Parity.outbound(recording)
      val omitted  = Parity.omitted(recording)
      val dropped  = omitted.flatMap(_._2).toSet
      val expected = calls(all.zipWithIndex.collect { case (b, i) if !dropped(i) => b }, b => b.has("transactional_message_id"))
      val observed = calls(kit.crm.calls.flatMap(_.body), b => b.has("transactional_message_id"))
      val people   = expected.identifies.keySet ++ expected.tracks.keySet
      val profiles = people.toVector.sorted.flatMap { p =>
        val e = expected.identifies.getOrElse(p, Map.empty)
        val o = observed.identifies.getOrElse(p, Map.empty)
        (e.keySet ++ o.keySet).toVector.sorted.flatMap { a =>
          if e.get(a) == o.get(a) then None
          else Some(Replay.Difference(path, s"$$.outbound.attributes.$a", e.getOrElse(a, "absent"), o.getOrElse(a, "absent")))
        }.distinctBy(_.at)
      }.distinctBy(_.at)
      // The seed's tracks are in the order of a Go map's iteration, which is no order: one run's, compared as a multiset.
      val runs = Parity.runs(recording)
      def counted(v: Vector[(String, Vector[String])]) = v.groupMapReduce(identity)(_ => 1)(_ + _)
      val tracks = people.toVector.sorted.flatMap { p =>
        val (e, o) = (expected.tracks.getOrElse(p, Vector.empty), observed.tracks.getOrElse(p, Vector.empty))
        if p.startsWith("_seed_") then
          val (ec, oc) = (counted(e.take(e.size / runs)), counted(o))
          (ec.keySet ++ oc.keySet).toVector.sortBy(_.toString).flatMap { c =>
            Option.when(ec.get(c) != oc.get(c))(Replay.Difference(path, s"$$.outbound.tracks[${c._1}]",
              ec.get(c).fold("absent")(n => s"$c x$n"), oc.get(c).fold("absent")(n => s"$c x$n")))
          }
        else sequence(s"$$.outbound.tracks", e, o)
      }
      val marked = omitted.flatMap((place, is) => is.sorted.zipWithIndex.map((i, k) =>
        Replay.Difference(path, s"$$.outbound.$place[$k]", all(i).path("name").asText(all(i).path("transactional_message_id").asText), "absent")))
      // Per recipient, for those the original emailed in the recording: its App calls were taken until the run ended, and
      // a later player's had not all arrived.
      val sends = expected.sends.keySet.toVector.sorted.flatMap(to =>
        sequence("$.outbound.sends", expected.sends(to), observed.sends.getOrElse(to, Vector.empty)))
      profiles ++ tracks ++ sends ++ marked

    /** Runs of the same call compared by what they are; a run the original made more often is its two paths. */
    private def sequence(at: String, e: Vector[(String, Vector[String])], o: Vector[(String, Vector[String])]): Vector[Replay.Difference] =
      def runs(v: Vector[(String, Vector[String])]) = v.foldLeft(Vector.empty[((String, Vector[String]), Int)]) {
        case (acc :+ ((last, n)), c) if last == c => acc :+ (last, n + 1)
        case (acc, c)                             => acc :+ (c, 1)
      }
      val (er, or) = (runs(e), runs(o))
      (0 until (er.size max or.size)).toVector.flatMap { k =>
        (er.lift(k), or.lift(k)) match
          case (Some((ec, en)), Some((oc, on))) if ec == oc =>
            if en == on then None
            else if en > on then Some(Replay.Difference(path, s"$$.outbound.repeated[$k]", s"$ec x$en", s"$oc x$on"))
            else Some(Replay.Difference(path, s"$at[$k]", s"$ec x$en", s"$oc x$on"))
          case (x, y) => Some(Replay.Difference(path, s"$at[$k]", x.fold("absent")(_.toString), y.fold("absent")(_.toString)))
      }
