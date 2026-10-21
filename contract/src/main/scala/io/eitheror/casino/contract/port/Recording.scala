package io.eitheror.casino.contract.port

import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * What the original answered to one scenario of its own end-to-end suite (spec 001 FR-029 to
 * FR-032; contracts/recording.md). One file per Go test function under `tests/port/<service>/`.
 */
final case class Recording(
    path: Path,
    scenario: String,
    requirement: Option[String],
    runs: Int,
    steps: Vector[Recording.Step],
    vary: Map[String, String],
    original: Map[String, String] = Map.empty // the original's commit and the brand it was recorded as
)

object Recording:
  /** `service` is the rebuilt service the recorder sorted the request under, by the base URL it went to. */
  final case class Request(method: String, path: String, headers: Map[String, String], body: Option[JsonNode], service: Option[String] = None)
  final case class Response(status: Int, body: Option[JsonNode])
  /** `vary`: this step's answer's fields that differed between the two runs of the original, checked for presence only. */
  final case class Step(name: String, request: Request, response: Response, capture: Map[String, String], vary: Map[String, String] = Map.empty)

  private val mapper = ObjectMapper()

  def load(path: Path): Either[String, Recording] =
    Try(mapper.readTree(Files.readString(path))).toEither.left.map(e => s"$path is not JSON: ${e.getMessage}").flatMap(parse(path, _))

  def parse(path: Path, root: JsonNode): Either[String, Recording] =
    def text(n: JsonNode, f: String): Either[String, String] =
      Option(n.get(f)).filter(_.isTextual).map(_.asText()).toRight(s"$path: '$f' is missing")
    def map(n: JsonNode): Map[String, String] =
      Option(n).map(_.properties().asScala.map(e => e.getKey -> e.getValue.asText()).toMap).getOrElse(Map.empty)
    def body(n: JsonNode): Option[JsonNode] = Option(n.get("body")).filterNot(_.isNull)
    for
      scenario <- text(root, "scenario")
      stepsNode <- Option(root.get("steps")).filter(_.isArray).toRight(s"$path: 'steps' is missing")
      steps <- stepsNode.elements().asScala.toVector.zipWithIndex.foldLeft[Either[String, Vector[Step]]](Right(Vector.empty)) {
        case (acc, (s, i)) =>
          for
            done <- acc
            req  <- Option(s.get("request")).toRight(s"$path: step $i has no request")
            res  <- Option(s.get("response")).toRight(s"$path: step $i has no response")
            m    <- text(req, "method")
            p    <- text(req, "path")
          yield done :+ Step(
            Option(s.get("name")).map(_.asText()).getOrElse(s"step $i"),
            Request(m, p, map(req.get("headers")), body(req), Option(req.get("service")).map(_.asText()).filter(_.nonEmpty)),
            Response(Option(res.get("status")).map(_.asInt()).getOrElse(0), body(res)),
            map(s.get("capture")),
            map(s.get("vary"))
          )
      }
    yield Recording(
      path,
      scenario,
      Option(root.get("requirement")).map(_.asText()).filter(_.nonEmpty),
      Option(root.get("runs")).map(_.asInt()).getOrElse(1),
      steps,
      map(root.get("vary")),
      map(root.get("original"))
    )

/** The subset of JSONPath recordings use: `$`, `.name` and `[index]`. */
object JsonPath:
  private val Segment = """\.([A-Za-z0-9_\-]+)|\[(\d+)\]""".r

  def segments(path: String): Vector[Either[String, Int]] =
    Segment.findAllMatchIn(path.stripPrefix("$")).map(m => Option(m.group(1)).toLeft(m.group(2).toInt)).toVector

  def get(node: JsonNode, path: String): Option[JsonNode] =
    segments(path).foldLeft(Option(node)) {
      case (Some(n), Left(field)) => Option(n.get(field))
      case (Some(n), Right(i))    => Option(n.get(i))
      case (None, _)              => None
    }

/**
 * Replays a recording against a rebuilt service and says where it answered differently (FR-032).
 * Captures fill `{name}` in later paths and bodies; a `vary` path is checked for presence only, in the step
 * it varied in (or, in a recording made before steps kept their own, in every step).
 * A step named `setup: …`, `observe: …` or `time: …` stands for something the scenario arranges, reads
 * off the record or waits for (tests/original/README.md): it is sent and its captures taken, and its
 * answer, which is the stand-in's, is not compared.
 */
object Replay:

  /** Whether a step is the scenario's arrangement rather than a behaviour of the service under test. */
  def arranged(step: Recording.Step): Boolean =
    Seq("setup:", "observe:", "time:").exists(step.name.startsWith)

  final case class Difference(recording: Path, at: String, expected: String, observed: String):
    def message: String = s"$recording $at: expected $expected, observed $observed"

  /** `send` makes one request to the rebuilt service, as the service's own test kit does. */
  def run(recording: Recording, send: Recording.Request => Recording.Response): Vector[Difference] =
    val captured = scala.collection.mutable.Map.empty[String, String]
    def fill(text: String): String = captured.foldLeft(text)((t, kv) => t.replace(s"{${kv._1}}", kv._2))
    val mapper = ObjectMapper()
    recording.steps.zipWithIndex.flatMap { (step, i) =>
      val request = step.request.copy(
        path = fill(step.request.path),
        body = step.request.body.map(b => mapper.readTree(fill(mapper.writeValueAsString(b))))
      )
      val observed = send(request)
      val at = s"$$.steps[$i].response"
      step.capture.foreach((name, path) => observed.body.flatMap(JsonPath.get(_, path)).foreach(v => captured(name) = v.asText()))
      if arranged(step) then Vector.empty
      else
        val status =
          if observed.status != step.response.status then Vector(Difference(recording.path, s"$at.status", step.response.status.toString, observed.status.toString))
          else Vector.empty
        status ++ compare(recording, step.vary ++ recording.vary, s"$at.body", "$", step.response.body, observed.body)
    }

  /** `vary` is the step's own, with the recording-wide form of older recordings, which holds in every step. */
  private def compare(recording: Recording, vary: Map[String, String], at: String, path: String, expected: Option[JsonNode], observed: Option[JsonNode]): Vector[Difference] =
    def show(n: Option[JsonNode]) = n.fold("absent")(_.toString)
    if vary.contains(path) then
      if observed.isDefined || expected.isEmpty then Vector.empty
      else Vector(Difference(recording.path, s"$at${path.stripPrefix("$")}", "present", "absent"))
    else
      (expected, observed) match
        case (None, None) => Vector.empty
        case (Some(e: ObjectNode), Some(o: ObjectNode)) =>
          val names = (e.fieldNames().asScala ++ o.fieldNames().asScala).toVector.distinct
          names.flatMap(n => compare(recording, vary, at, s"$path.$n", Option(e.get(n)), Option(o.get(n))))
        case (Some(e: ArrayNode), Some(o: ArrayNode)) if e.size == o.size =>
          (0 until e.size).toVector.flatMap(i => compare(recording, vary, at, s"$path[$i]", Option(e.get(i)), Option(o.get(i))))
        case (e, o) if e == o => Vector.empty
        case (e, o)           => Vector(Difference(recording.path, s"$at${path.stripPrefix("$")}", show(e), show(o)))
