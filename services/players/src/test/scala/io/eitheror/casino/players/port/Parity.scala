package io.eitheror.casino.players.port

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.port.{Recording, Replay}
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

  val divergences: Vector[Divergence] = Vector.empty

  def diverging(recording: Recording): Vector[Divergence] = divergences.filter(_.reached(recording))

  private val StepField = """\$\.steps\[(\d+)\]\.response(.*)""".r

  def divergence(recording: Recording)(d: Replay.Difference): Option[Divergence] = d.at match
    case StepField(i, field) =>
      val step = recording.steps.lift(i.toInt)
      divergences.find(v => v.covers(field) && step.exists(v.holds(recording, _)))
    case _ => None

  private val Ours = Vector("/v1/casino/players/", "/v1/casino/sessions/", "/v1/casino/geo/", "/internal/keycloak-webhook")
  private val OperatorPath = """/v1/casino/(?!players/|sessions/|geo/|brands/)[^/]+/.*""".r

  /** Whether a recorded step is one of the original's player, session, operator or realm routes this service serves. */
  def ours(r: Recording.Request): Boolean =
    Ours.exists(r.path.startsWith) || OperatorPath.matches(r.path.takeWhile(_ != '?')) ||
      r.path.matches("""/v1/casino/brands/[^/]+/(merge-tags|messages/preview).*""")

  private final class Replaying(kit: Kit, recording: Recording):
    var step   = -1 // Replay.run sends the steps in order
    var player = Option.empty[String]

    private def answered(r: Reply): Recording.Response =
      Recording.Response(r.status, Option.when(r.text.trim.nonEmpty)(Json.parse(r.text).getOrElse(Json.mapper.getNodeFactory.textNode(r.text))))

    /** Who the original's step was: an operator on its backoffice paths, the player signed in on the rest, else nobody. */
    private def as(r: Recording.Request, path: String): Seq[(String, String)] =
      val signedIn = r.headers.exists((k, _) => k.equalsIgnoreCase("authorization"))
      if !signedIn then kit.asVisitor
      else if path.startsWith(s"/v1/casino/backoffice/") || path.startsWith(s"/v1/casino/brands/") then kit.asOperator()
      else player.fold(kit.asVisitor)(kit.asPlayer)

    def send(r: Recording.Request): Recording.Response =
      step += 1
      val s = recording.steps(step)
      if !ours(r) then s.response
      else if r.path.contains("{") then Recording.Response(0, Some(Json.obj("unfilled" -> r.path)))
      else
        val path   = WireTranslation.path(r.method, r.path, WireTranslation.Context(kit.slug, player))
        val body   = WireTranslation.body(path, r.body).map(Json.render)
        val reply  = answered(kit.request(r.method, path, body, as(r, path)))
        reply.body.flatMap(_.text("playerId")).filter(_ => reply.status / 100 == 2).foreach(p => player = Some(p))
        reply.copy(body = WireTranslation.answer(s.response.body, reply.body))

  def replay(recording: Recording): Vector[Replay.Difference] =
    val kit = SharedKit.kit
    kit.reset()
    try Replay.run(recording, Replaying(kit, recording).send)
    finally kit.reset()

  /** A recording's body as text, for a suite that changes one and replays it. */
  def text(node: JsonNode): String = Json.render(node)
