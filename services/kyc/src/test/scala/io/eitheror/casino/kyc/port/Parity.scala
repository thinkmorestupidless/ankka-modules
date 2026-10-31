package io.eitheror.casino.kyc.port

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{CasinoPlayers, PlayerProfileUpdated, PlayerRegistered}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.contract.port.{Recording, Replay}
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.it.{Kit, Reply, SharedKit}
import io.eitheror.casino.kyc.port.WireTranslation.{Caller, Context, Row}
import io.eitheror.casino.kyc.record.KycRecord

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Replays one recording of tests/port/kyc against the shared kit (ankka-port, section 7). A step on one of
 * this service's routes is translated by [[WireTranslation]] (contracts/wire.md), sent as whoever the
 * original's helper signed it as, and its answer read back in the recording's terms; every other step
 * (the realm's pages, the casino's registration, sign-in and profile, the twins) is answered as
 * recorded. A registration and an operator's profile edit are what `players` would publish to
 * `casino.players`, so they are published to the kit's broker as `players` publishes them.
 */
object Parity:
  val root: Path      = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize
  val directory: Path = root.resolve("tests/port/kyc")

  def recordings(directory: Path = directory): Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.filter(p => p.toString.endsWith(".json") && !p.getFileName.toString.startsWith("suite-result")).toVector.sorted

  /**
   * Where the service answers differently from the original on purpose: a row of plan.md's
   * *Divergences* (named by its first words in `row`), the answer's fields it covers, and the steps where
   * it holds.
   */
  final case class Divergence(row: String, fields: Seq[String], why: String, holds: (Recording, Recording.Step) => Boolean):
    def reached(recording: Recording): Boolean = recording.steps.exists(holds(recording, _))
    def covers(field: String): Boolean         = fields.exists(f => field == f || field.startsWith(f + ".") || field.startsWith(f + "["))

  private def named(r: Recording) = r.path.getFileName.toString.stripSuffix(".json")

  /** Steps of the recordings whose names start with one of `files`, by method and the end of their path. */
  private def at(files: String*)(method: String, pathEnds: String*): (Recording, Recording.Step) => Boolean =
    (r, s) => files.exists(named(r).startsWith) && s.request.method == method && pathEnds.exists(s.request.path.takeWhile(_ != '?').endsWith)

  private def path(s: Recording.Step) = s.request.path.takeWhile(_ != '?')

  /**
   * The steps, by recording and index, whose answer the original gave before its rollup had run (its
   * `kyc-status-rollup` is a function invoked after the write, so a review's answer and a read straight
   * after a confirm carried the status before): where this service, deriving with the change, answers the
   * status after. The other reviews and reads, made once the rollup had caught up or where it changed
   * nothing, agree.
   */
  private def steps(at: (String, Int)*): (Recording, Recording.Step) => Boolean =
    (r, s) => at.exists((file, i) => named(r) == file && r.steps.lift(i).exists(_ eq s))

  /** A recorded list holding a category's earlier document beside its current one. */
  private def superseded(s: Recording.Step): Boolean =
    s.request.method == "GET" && path(s).endsWith("/kyc/documents") &&
      s.response.body.toVector.flatMap(_.objects("documents")).flatMap(_.text("docType")).filterNot(_ == "other")
        .groupBy(identity).exists(_._2.size > 1)

  val divergences: Vector[Divergence] = Vector(
    Divergence("The reviewer is recorded; an unknown document is 404; the approval's answer carries the status after",
      Seq(".body.kycStatus"), "a review's answer carries the status it derived",
      steps("TestScenario28_KYCDocuments_RejectedReasonIsAReasonCode" -> 8, "TestScenario28_KYCReview_AutomaticModeVerifiesWhenRequiredApproved" -> 13,
        "TestScenario28_KYCReview_RejectedLatestDocumentRejectsAccount" -> 8)),
    Divergence("A policy save reads `CUSTOM`", Seq(".body.licenceProfile"), "a policy save reads CUSTOM",
      (_, s) => s.request.method == "PUT" && path(s).endsWith("/kyc-policy") &&
        !s.response.body.flatMap(_.text("licenceProfile")).contains("CUSTOM")),
    Divergence("The status is derived with the change that causes it", Seq(".body.kycStatus"),
      "a read after a document change shows the status derived with it",
      steps("TestScenario28_KYCDocuments_ConfirmedUploadIsPendingAndLocked" -> 7,
        "TestScenario28_KYCDocuments_PendingOrApprovedCategoryRefusesAnotherUpload" -> 16,
        "TestScenario28_KYCDocuments_ReplacementForRejectedIsPending" -> 10)),
    Divergence("The player's read shows each category's current document", Seq(".body.documents"),
      "a category shows its current document, not the one it replaced", (_, s) => superseded(s)),
    Divergence("Another player's id is `player_id_mismatch`", Seq(".body.error"), "another player's id is player_id_mismatch",
      at("TestScenario28_KYCDocuments_PlayerViewingAnotherPlayersDocumentRefused")("GET", "/view-url"))
  )

  def diverging(recording: Recording): Vector[Divergence] = divergences.filter(_.reached(recording))

  private val StepField = """\$\.steps\[(\d+)\]\.response(.*)""".r

  def divergence(recording: Recording)(d: Replay.Difference): Option[Divergence] = d.at match
    case StepField(i, field) =>
      val step = recording.steps.lift(i.toInt)
      divergences.find(v => v.covers(field) && step.exists(v.holds(recording, _)))
    case _ => None

  private val Register = """/v1/casino/players/[^/]+/register""".r
  private val Login    = """/v1/casino/players/[^/]+/login""".r
  private val Profile  = """/v1/casino/backoffice/[^/]+/players/([^/]+)/profile""".r
  private val OfPlayer = """/v1/casino/[^/]+/players/([^/]+)/kyc/.*""".r

  /** The bytes a replay puts through a signed link: the original's suite confirmed names it never uploaded to. */
  private val Bytes = Array.fill(100)(1.toByte)

  private def contentType(key: String): String = key.toLowerCase.split('.').lastOption match
    case Some("png")           => "image/png"
    case Some("pdf")           => "application/pdf"
    case Some("jpg" | "jpeg") | _ => "image/jpeg"

  /** One replay: the uploads offered, the players registered and the one signed in last, the operator recorded. */
  private final class Replaying(kit: Kit, recording: Recording):
    var step       = -1 // Replay.run sends the steps in order
    private var ctx        = Context(kit.slug)
    private var signedIn   = Option.empty[String]
    private val registered = scala.collection.mutable.Map.empty[String, JsonNode] // a player's registration, for the profile fact
    private val brand      = "spinvibe"

    /** The realm user the original's operator was, as its approvals name it. */
    private val operator: String =
      recording.steps.flatMap(_.response.body).flatMap(b => b.text("approvedBy")).headOption.getOrElse("op-1")

    private def answered(r: Reply): Option[JsonNode] =
      Option.when(r.text.trim.nonEmpty)(Json.parse(r.text).getOrElse(Json.mapper.getNodeFactory.textNode(r.text)))

    private def record(player: String) = kit.components.forEventSourcedEntity(EntityId(player)).call(KycRecord.get)

    /** `player_registered` as `players` publishes it, with the profile registered, read before the next step. */
    private def registration(s: Recording.Step): Unit =
      for
        body   <- s.response.body if s.response.status == 201
        player <- body.text("playerId")
      do
        val b  = s.request.body.getOrElse(Json.obj())
        val at = kit.now()
        def personal(f: String) = b.text(f).map(Personal(_))
        registered(player) = b
        kit.publish(CasinoPlayers, player, PlayerRegistered(player, at, None, None, None, b.text("country"), b.text("currency"), None, at,
          personal("firstName"), personal("lastName"), personal("dateOfBirth"), personal("phone"), personal("addressLine1"),
          personal("city"), personal("postalCode")))
        scala.util.Try(kit.eventually(s"$player's registration read", 5.seconds)(
          Option.when(record(player).invoke().profile.present.nonEmpty)(()))): Unit

    /** An operator's profile edit as `players` publishes it: every field the player holds, the edit's named. */
    private def profileEdit(player: String, s: Recording.Step): Unit =
      if s.response.status / 100 == 2 then
        val edit   = s.request.body.getOrElse(Json.obj())
        val fields = registered.get(player).toVector.flatMap(_.fieldNames.asScala.toVector.map(f => f -> registered(player).text(f))) ++
          edit.fieldNames.asScala.toVector.map(f => f -> edit.text(f))
        val held   = fields.collect { case (f, Some(v)) => f -> v }.toMap
        def personal(f: String) = held.get(f).map(Personal(_))
        kit.publish(CasinoPlayers, player, PlayerProfileUpdated(player, edit.fieldNames.asScala.toVector.sorted, personal("firstName"),
          personal("lastName"), personal("dateOfBirth"), personal("phone"), personal("addressLine1"), personal("city"),
          personal("postalCode"), held.get("country"), kit.now()))
        scala.util.Try(kit.eventually(s"$player's profile read", 5.seconds)(Option.when(record(player).invoke().profile.changedAt.nonEmpty)(()))): Unit

    /** Who the original's helper signed the step as (no recorded step kept its `Authorization`). */
    private def as(request: WireTranslation.Request, player: Option[String]): Seq[(String, String)] = request.caller match
      case Caller.Players      => kit.asService("players")
      case Caller.GameProvider => kit.asService("game-provider")
      case Caller.AsRecorded => request.row match
        case Row.Upload | Row.Confirm | Row.Documents => player.fold(kit.asPublic)(kit.asPlayer)
        case Row.View if !viewedByOperator           => signedIn.orElse(player).fold(kit.asPublic)(kit.asPlayer)
        case Row.Requirements                         => kit.asPublic
        case _                                        => kit.asOperator(subject = operator)

    private def viewedByOperator = named(recording).contains("Operator")

    /** A confirmation of a name the replay was never offered: offered here first, then put, as a browser would. */
    private def offer(player: String, key: String, docType: Option[String]): Either[Reply, Unit] =
      val ct    = contentType(key)
      val reply = kit.json("POST", kit.playerPath(s"/$player/uploads"), Json.obj("category" -> docType, "contentType" -> ct), kit.asPlayer(player))
      if reply.status / 100 != 2 then Left(reply)
      else
        WireTranslation.offered(reply.json).foreach((_, o) => ctx = ctx.copy(offers = ctx.offers + (key -> o)))
        Right(())

    private def recorded(observed: Option[JsonNode]): Option[JsonNode] =
      observed.map(n => Json.parse(Json.render(n).replace(kit.slug, brand)).getOrElse(n))

    /**
     * A read asked again, for up to five seconds, while it differs from the recording: the original's
     * suite waited on the rollup, and this service's queue and reads follow a write by moments too.
     */
    private def settled(s: Recording.Step, ask: => Recording.Response): Recording.Response =
      val single  = recording.copy(steps = Vector(s.copy(capture = Map.empty)))
      val until   = System.nanoTime + 5_000_000_000L
      var reply   = ask
      def unsettled(r: Recording.Response) = Replay.run(single, _ => r).exists(divergence(single)(_).isEmpty)
      while unsettled(reply) && System.nanoTime < until do
        Thread.sleep(200)
        reply = ask
      reply

    def send(r: Recording.Request): Recording.Response =
      step += 1
      val s = recording.steps(step)
      path(s) match
        case Register() => registration(s); s.response
        case Login() =>
          s.response.body.flatMap(_.text("playerId")).foreach(p => signedIn = Some(p))
          s.response
        case Profile(_) =>
          r.path.takeWhile(_ != '?') match
            case Profile(player) => profileEdit(player, s)
            case _               =>
          s.response
        case _ => ours(r, s).getOrElse(s.response)

    private def ours(r: Recording.Request, s: Recording.Step): Option[Recording.Response] =
      val player = r.path.takeWhile(_ != '?') match
        case OfPlayer(p) => Some(p)
        case _           => signedIn
      ctx = ctx.copy(player = player)
      WireTranslation.request(r.method, r.path, r.body, ctx, operator = viewedByOperator).map { first =>
        val ready = first.row match
          case Row.Confirm if first.put.isEmpty =>
            val key = r.body.flatMap(_.text("key")).getOrElse("")
            offer(player.getOrElse(""), key, r.body.flatMap(_.text("docType"))).map(_ =>
              WireTranslation.request(r.method, r.path, r.body, ctx, operator = viewedByOperator).getOrElse(first))
          case _ => Right(first)
        ready match
          case Left(refused) => Recording.Response(refused.status, recorded(WireTranslation.narrowed(s.response.body, answered(refused))))
          case Right(request) =>
            request.put.foreach(o => kit.putSigned(o.url, Bytes, o.contentType): Unit)
            def ask(): Recording.Response =
              val reply = kit.request(request.method, request.path, request.body.map(Json.render), as(request, player))
              val body  = answered(reply)
              if request.row == Row.Upload && reply.status / 100 == 2 then
                body.flatMap(WireTranslation.offered).foreach((k, o) => ctx = ctx.copy(offers = ctx.offers + (k -> o)))
              val (status, shaped) = WireTranslation.answer(request, reply.status, body, s.response.body, ctx, r.body)
              Recording.Response(status, recorded(shaped))
            if request.method == "GET" then settled(s, ask()) else ask()
      }

  def replay(recording: Recording): Vector[Replay.Difference] =
    val kit = SharedKit.kit
    kit.reset()
    try Replay.run(recording, Replaying(kit, recording).send)
    finally kit.reset()
