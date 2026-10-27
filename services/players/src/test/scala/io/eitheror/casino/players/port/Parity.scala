package io.eitheror.casino.players.port

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.port.{JsonPath, Recording, Replay}
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.it.{Kit, Reply, SharedKit}

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * Replays one recording of tests/port/players against the shared kit (ankka-port, section 7). A step on
 * one of this service's routes is translated by [[WireTranslation]] (the one documented difference of
 * paths and bodies), sent with the kit's token for whoever the original's step was signed in as, and its
 * answer read back in the recording's terms; every other step (the realm's own pages, the other services,
 * the twins) is answered as recorded. `setup:`, `observe:` and `time:` steps are `Replay`'s to run, not
 * to compare, and `vary` fields are compared for presence.
 */
object Parity:
  val root: Path = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize
  val directory: Path = root.resolve("tests/port/players")

  def recordings(directory: Path = directory): Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.filter(p => p.toString.endsWith(".json") && !p.getFileName.toString.startsWith("suite-result")).toVector.sorted

  /**
   * Where the service answers differently from the original on purpose: a row of plan.md's
   * *Divergences* (named by its first words in `row`), the answer's fields it covers, and the steps
   * where it holds. Empty until each story names its own.
   */
  final case class Divergence(row: String, fields: Seq[String], why: String, holds: (Recording, Recording.Step) => Boolean):
    def reached(recording: Recording): Boolean = recording.steps.exists(holds(recording, _))
    def covers(field: String): Boolean         = fields.exists(f => field == f || field.startsWith(f + ".") || field.startsWith(f + "["))

  /** Steps of the recordings whose file names start with one of `files`, named by the start of their own names. */
  private def at(files: String*)(steps: String*): (Recording, Recording.Step) => Boolean =
    (r, s) => files.exists(r.path.getFileName.toString.startsWith) && steps.exists(s.name.startsWith)

  private def operatorSigned(s: Recording.Step) = s.request.headers.get("Authorization").contains("Bearer {operatorToken}")

  val divergences: Vector[Divergence] = Vector(
    Divergence("`400 missing_fields` naming the fields", Seq(".body"), "a missing field is named, not the original's plain text",
      (_, s) => s.request.path.endsWith("/register") && WireTranslation.plainMissingFields(s.response.body)),
    Divergence("A password below the realm's policy", Seq(".status", ".body"), "a weak password is 422 password_too_weak",
      at("a registration with a password below")("register with a weak password")),
    Divergence("A `closed` player is refused sign-in and game sessions", Seq(".status", ".body"), "a closed player is refused",
      at("a closed player's sign-in", "a game session asked for a restricted or closed player is refused naming the status [closed]")(
        "sign in while closed", "launch a game while closed")),
    Divergence("`blocked` is set only by the migration", Seq(".status", ".body"), "nothing here sets blocked",
      at("a sign-in by a restricted player is refused naming the status and the attempt is kept [blocked]",
        "a game session asked for a restricted or closed player is refused naming the status [blocked]")(
        "sign in while blocked", "launch a game while blocked")),
    Divergence("Refresh tokens rotate", Seq(".status", ".body"), "a reused refresh token is refresh_expired",
      at("a refresh that is expired, reused or revoked is answered refresh expired [reused]")("refresh with the same refresh again")),
    Divergence("Sign-out, a suspension and a revoke end the realm's session", Seq(".status", ".body"),
      "an ended sign-in cannot be refreshed",
      at("a refresh that is expired, reused or revoked is answered refresh expired [revoked]", "a sign-out ends",
        "a suspension with a reason")("refresh the revoked sign-in", "refresh the ended sign-in", "refresh the suspended player's sign-in")),
    Divergence("Sign-out, a suspension and a closure end the game sessions", Seq(".body.sessions"),
      "the game sessions under an ended sign-in end with it",
      at("a closure ends everything", "a sign-out ends", "a suspension with a reason")("read the sessions")),
    Divergence("A verified second address made the sign-in address", Seq(".status", ".body"), "the old address no longer signs in",
      at("making a verified second email address")("sign in with the first address")),
    Divergence("An operator with no standing at the brand is `403`", Seq(".status", ".body"), "no standing at the brand is 403",
      at("an operator without the brand's role")("suspend without", "read the profile without", "an operator action under the player's own")),
    Divergence("`GET /{playerId}` answers a built profile", Seq(".body"), "the player's read is a built profile, not the twin",
      (_, s) => s.request.method == "GET" && s.response.status == 200 && s.request.path.matches("""/v1/casino/players/[^/]+/(\{playerId\}|[0-9a-f-]{36})""")),
    Divergence("The operator's profile has no `wallet`, `rg` or `lifetimeStats`", Seq(".body.wallet", ".body.rg", ".body.lifetimeStats",
      ".body.player.isTestAccount", ".body.player.kycRequiredDocTypes", ".body.player.kycVerificationMode"),
      "the operator's profile leaves other services' parts to them",
      (_, s) => operatorSigned(s) && s.request.path.endsWith("/profile")),
    Divergence("The export is assembled across the brand's services", Seq(".status", ".body"), "the export is read once assembled",
      at("an operator's activity export")("read the export")),
    Divergence("`consentSource` is who last set the consent", Seq(".body.consentSource"), "a withdrawal is the player's",
      at("a withdrawn email marketing consent")("read the consents"))
  )

  def diverging(recording: Recording): Vector[Divergence] = divergences.filter(_.reached(recording))

  private val StepField = """\$\.steps\[(\d+)\]\.response(.*)""".r

  def divergence(recording: Recording)(d: Replay.Difference): Option[Divergence] = d.at match
    case StepField(i, field) =>
      val step = recording.steps.lift(i.toInt)
      divergences.find(v => v.covers(field) && step.exists(v.holds(recording, _)))
    case _ => None

  private val Ours = Vector("/v1/casino/players/", "/v1/casino/sessions/", "/v1/casino/geo/", "/internal/keycloak-webhook")
  private val OperatorPath = """/v1/casino/(?!players/|sessions/|geo/|brands/|bonus/)[^/]+/.*""".r

  /** Whether a recorded step is one of the original's player, session, operator or realm routes this service serves. */
  def ours(r: Recording.Request): Boolean =
    Ours.exists(r.path.startsWith) || OperatorPath.matches(r.path.takeWhile(_ != '?')) ||
      r.path.matches("""/v1/casino/brands/[^/]+/(merge-tags|messages/preview).*""")

  /** The driver's passwords (tests/original/players-driver/api.go), which the recorder wrote as `<redacted>`. */
  private val Password     = "Players-Driver-1!"
  private val NewPassword  = "Players-Driver-2!"
  private val WeakPassword = "a"

  /** The realm user the recordings' operator signed in as (`dev-operator`, tests/port/players/README.md), named in `reviewedBy`. */
  private val RecordedOperator = "04223bf1-887d-461c-a4cf-4911ca0a9d62"

  /** An address the recorder wrote as a placeholder, and the placeholder of the link mailed to it. */
  private val Addresses = Vector("email", "otherEmail", "secondEmail")
  private val Links     = Map("link" -> "email", "otherLink" -> "otherEmail", "secondLink" -> "secondEmail", "changeToken" -> "email")

  private val Placeholder = """\{([A-Za-z]+)\}""".r
  private val TwinStatus  = """/twins/casino-[^/]+/([^/]+)""".r
  private val Launch      = """/v1/casino/sessions/[^/]+/launch""".r
  private val SelfExclude = """/v1/casino/[^/]+/players/([^/]+)/self-exclude""".r
  private val FraudCase   = """(.*/fraud-cases/)([0-9a-f-]{36})(/.*)""".r
  private val Bearer      = """Bearer \{([A-Za-z]+)\}""".r

  /**
   * One replay's own values for what the recorder wrote as placeholders: an address, device and surname
   * minted per replay (entities outlive a test), a link read from what `engagement` was asked to send, the
   * driver's passwords, and the tokens this service answered. The answer goes back in the recording's
   * terms: a minted value as its placeholder, a field the recorder redacted as `<redacted>`.
   */
  private final class Replaying(kit: Kit, recording: Recording):
    var step   = -1 // Replay.run sends the steps in order
    var player = Option.empty[String]
    private val minted   = scala.collection.mutable.LinkedHashMap.empty[String, String]
    private val captured = scala.collection.mutable.Map.empty[String, String] // this service's answers, by the recording's capture names
    private var refresh  = Option.empty[String]                              // the first sign-in's refresh token

    private def answered(r: Reply): Recording.Response =
      Recording.Response(r.status, Option.when(r.text.trim.nonEmpty)(Json.parse(r.text).getOrElse(Json.mapper.getNodeFactory.textNode(r.text))))

    private def fresh = java.util.UUID.randomUUID().toString.take(8)

    private def value(name: String): Option[String] = minted.get(name).orElse {
      val v = name match
        case a if Addresses.contains(a) => Some(s"${a.toLowerCase}-$fresh@example.test")
        case "device"                   => Some(s"device-$fresh")
        case "surname"                  => Some("Surname" + fresh.map(c => ('a' + c % 26).toChar))
        case "youngDate"                => Some(java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusYears(17).toString)
        case l if Links.contains(l)     => minted.get(Links(l)).flatMap(link)
        case "caseId"                   => caseOf
        case _                          => None
      v.filterNot(_ => Links.contains(name)).foreach(minted(name) = _) // a link is read afresh: the address may be mailed again
      v
    }

    /** The token of the last link `engagement` was asked to send to `address`, waited for briefly. */
    private def link(address: String): Option[String] =
      scala.util.Try(kit.eventually(s"a link to $address", scala.concurrent.duration.Duration(5, "s"))(kit.engagement.lastLink(address))).toOption
        .flatMap(l => Option(java.net.URI.create(l).getQuery)).flatMap(_.split('&').collectFirst { case q if q.startsWith("token=") => q.stripPrefix("token=") })
        .map(java.net.URLDecoder.decode(_, "UTF-8"))

    private def fill(text: String): String = Placeholder.replaceAllIn(text, m => java.util.regex.Matcher.quoteReplacement(value(m.group(1)).getOrElse(m.matched)))

    /** A recorded body with the replay's values: placeholders, and the redacted password and refresh token. */
    private def fill(body: JsonNode, s: Recording.Step): JsonNode =
      val filled = Json.parse(fill(Json.render(body))).getOrElse(body)
      filled match
        case o: com.fasterxml.jackson.databind.node.ObjectNode =>
          if o.text("password").contains("<redacted>") then
            val name = s.name.toLowerCase
            o.put("password", if name.contains("weak") then WeakPassword else if name.contains("new password") then NewPassword else Password)
          if o.text("refreshToken").contains("<redacted>") then refresh.foreach(o.put("refreshToken", _))
          o
        case other => other

    /** Who the original's step was, by the token its `Authorization` named. */
    private def as(r: Recording.Request, path: String): Seq[(String, String)] =
      val extra = r.headers.toSeq.filter((k, _) => k.equalsIgnoreCase("X-Forwarded-For"))
      val who = r.headers.collectFirst { case (k, v) if k.equalsIgnoreCase("authorization") => v } match
        case None if recording.scenario.startsWith("TestScenario") && path.startsWith("/v1/casino/backoffice/") =>
          kit.asOperator(subject = RecordedOperator) // the original's own suite, recorded without its headers
        case None                                       => kit.asVisitor
        case Some(Bearer("operatorToken"))              => kit.asOperator(subject = RecordedOperator)
        case Some(Bearer("outsiderToken"))              => kit.asOperator(roles = "brand_elsewhere", subject = "outsider")
        case Some(Bearer(name)) if captured.contains(name) => kit.asToken(captured(name))
        case _ if path.startsWith("/v1/casino/backoffice/") => kit.asOperator()
        case _                                          => player.fold(kit.asVisitor)(kit.asPlayer)
      who ++ extra

    /** This service's answer in the recording's terms: minted values as their placeholders, redacted fields as redacted. */
    private def recorded(expected: Option[JsonNode], observed: Option[JsonNode]): Option[JsonNode] =
      def redact(e: JsonNode, o: JsonNode): Unit = (e, o) match
        case (e: com.fasterxml.jackson.databind.node.ObjectNode, o: com.fasterxml.jackson.databind.node.ObjectNode) =>
          e.fieldNames.asScala.toVector.foreach { f =>
            if e.get(f).isTextual && e.get(f).asText() == "<redacted>" && o.has(f) then o.put(f, "<redacted>")
            else Option(o.get(f)).foreach(redact(e.get(f), _))
          }
        case _ =>
      WireTranslation.answer(expected, observed).map { n =>
        val brand = recording.original.getOrElse("brand", "spinvibe")
        val text  = minted.foldLeft(Json.render(n).replace(kit.slug, brand))((t, kv) => t.replace(kv._2, s"{${kv._1}}"))
        val back = Json.parse(text).getOrElse(n)
        expected.foreach(redact(_, back))
        back
      }

    /** The fraud case that links a player of this replay, once `FraudCasesView` holds it. */
    private def caseOf: Option[String] =
      val players = captured.values.toSet
      scala.util.Try(kit.eventually("a fraud case linking the replay's players", scala.concurrent.duration.Duration(10, "s")) {
        val r = kit.request("GET", kit.operatorPath("/fraud-cases"), None, kit.asOperator())
        r.json.path("cases").elements.asScala.find(_.path("playerIds").elements.asScala.exists(p => players(p.asText()))).flatMap(_.text("caseId"))
      }).toOption

    /**
     * A step on another service's route that changes a player here, made through the route that service
     * calls on this one (contracts/wire.md, *Steps of other services*): a launch opens the game session as
     * `game-provider` does, and an exclusion (the original's twin write of `self_excluded` among them) is
     * `responsible-gambling`'s. A launch is answered with this service's session or refusal and the
     * recorded `gameUrl` and `launchToken`, which are `game-provider`'s; an exclusion as recorded.
     */
    private def standIn(r: Recording.Request, s: Recording.Step): Option[Recording.Response] =
      def exclude(id: String) =
        kit.json("PUT", kit.servicePath(s"/$id/exclusion"), Json.obj("at" -> kit.now().toString), kit.asService("responsible-gambling"))
      r.path match
        case TwinStatus(id) if r.method == "POST" && r.body.flatMap(_.text("account_status")).contains("self_excluded") =>
          exclude(id); Some(s.response)
        case SelfExclude(id) => exclude(id); Some(s.response)
        case Launch() =>
          val b       = r.body.getOrElse(Json.obj())
          val token = r.headers.collectFirst { case (k, Bearer(name)) if k.equalsIgnoreCase("authorization") => name }.getOrElse("token")
          val session = captured.get(token).flatMap(t => scala.util.Try(kit.sessionOf(t)).toOption)
          val reply = answered(kit.json("POST", kit.servicePath(s"/${b.text("playerId").getOrElse("")}/game-sessions"),
            Json.obj("gameId" -> b.text("gameId"), "currency" -> b.text("currency"), "demo" -> false, "playerSessionId" -> session),
            kit.asService("game-provider")))
          if reply.status != 201 then Some(reply.copy(body = recorded(s.response.body, reply.body)))
          else
            val out = Json.obj("sessionId" -> reply.body.flatMap(_.text("sessionId")))
            Seq("gameUrl", "launchToken").foreach(f => s.response.body.flatMap(n => Option(n.get(f))).foreach(out.set[JsonNode](f, _)))
            Some(Recording.Response(200, Some(out)))
        case _ => None

    /**
     * A read asked again, for up to five seconds, while it differs from the recording: this service's
     * reads are views, which follow a write by moments where the original read its twin at once. Where a
     * divergence holds, the difference is what the read settles to, so it is asked again until it shows.
     */
    private def settled(s: Recording.Step, ask: => Recording.Response): Recording.Response =
      val single  = recording.copy(steps = Vector(s.copy(capture = Map.empty)))
      val until   = System.nanoTime + 5_000_000_000L
      val towards = divergences.exists(_.holds(recording, s))
      var reply   = ask
      def unsettled(r: Recording.Response) =
        val (covered, other) = Replay.run(single, _ => r.copy(body = recorded(s.response.body, r.body))).partition(divergence(single)(_).isDefined)
        other.nonEmpty || (towards && covered.isEmpty)
      while unsettled(reply) && System.nanoTime < until do
        Thread.sleep(200)
        reply = ask
      reply

    def send(r: Recording.Request): Recording.Response =
      step += 1
      val s = recording.steps(step)
      val filled = r.copy(path = fill(r.path), body = r.body.map(fill(_, s)))
      standIn(filled, s).getOrElse(sendOurs(r, filled, s))

    private def sendOurs(r: Recording.Request, asked: Recording.Request, s: Recording.Step): Recording.Response =
      if !ours(r) then s.response
      else
        val filled = asked.path match
          case FraudCase(before, id, after) if !(captured.values ++ minted.values).exists(_ == id) => asked.copy(path = before + caseOf.getOrElse(id) + after)
          case _ => asked
        if filled.path.contains("{") then Recording.Response(0, Some(Json.obj("unfilled" -> filled.path)))
        else
          val path  = WireTranslation.path(filled.method, filled.path, WireTranslation.Context(kit.slug, player))
          val body  = WireTranslation.body(path, filled.body).map(Json.render)
          def ask() = answered(kit.request(filled.method, path, body, as(filled, path)))
          val reply = if filled.method != "GET" || Replay.arranged(s) then ask() else settled(s, ask())
          if reply.status / 100 == 2 then
            reply.body.flatMap(_.text("playerId")).foreach(p => player = Some(p))
            if refresh.isEmpty && path.endsWith("/login") then refresh = reply.body.flatMap(_.text("refreshToken"))
            s.capture.foreach((name, at) => reply.body.flatMap(JsonPath.get(_, at)).foreach(v => captured(name) = v.asText()))
          reply.copy(body = recorded(s.response.body, reply.body))

  def replay(recording: Recording): Vector[Replay.Difference] =
    val kit = SharedKit.kit
    kit.reset()
    try Replay.run(recording, Replaying(kit, recording).send)
    finally kit.reset()

  /** A recording's body as text, for a suite that changes one and replays it. */
  def text(node: JsonNode): String = Json.render(node)
