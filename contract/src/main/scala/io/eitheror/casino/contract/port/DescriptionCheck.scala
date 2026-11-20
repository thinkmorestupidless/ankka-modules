package io.eitheror.casino.contract.port

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.networknt.schema.{InputFormat, JsonSchemaFactory, SpecVersion}

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

/**
 * A service's kept description against what the original answered (spec 031 FR-012 to FR-014,
 * contracts/description-check.md): each recorded step that is one of the service's player routes
 * must answer a status the operation names, or one of the platform's, with a body its schema admits.
 * The requests are not checked: they are what the original accepted, and a rebuilt route's request
 * differs where its spec says so.
 */
object DescriptionCheck:

  /** One of the original's routes, as the rebuilt one's operation: `host` is the service the recorder sorted the call under. */
  final case class OriginalRoute(method: String, path: Regex, host: Option[String], operationId: String)
  final case class Difference(recording: String, step: String, operationId: String, what: String)
  final case class Result(differences: Vector[Difference], unrecorded: Vector[String], matched: Int)

  /** The statuses the platform answers for every route, which no description names (ankka `HeldToDocument`). */
  val PlatformStatuses: Set[Int] = Set(400, 401, 403, 413, 500, 503, 504)

  private val mapper  = ObjectMapper()
  private val schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

  final case class Operation(id: String, method: String, path: String, node: JsonNode):
    def line: String = s"${method.toUpperCase} $path"

  /**
   * Every recording under `tests/port/` (or the directories named), sorted. A JSON file with no
   * top-level `steps` is not a recording (`suite-result.json`, the programme's `import/bundle.json`):
   * the recordings check (`just recordings`) is what holds a recording well formed, not this.
   */
  def recordings(root: Path, directories: String*): Vector[Path] =
    val dirs = if directories.isEmpty then Vector(root.resolve("tests/port")) else directories.toVector.map(d => root.resolve(s"tests/port/$d"))
    dirs.filter(java.nio.file.Files.isDirectory(_)).flatMap { d =>
      val s = java.nio.file.Files.walk(d)
      try s.iterator().asScala.filter(p => p.toString.endsWith(".json") && mapper.readTree(p.toFile).has("steps")).toVector
      finally s.close()
    }.sorted

  /** A service's kept list of player routes no recording calls (`tests/port/unrecorded-player-routes/<service>.txt`), comments dropped. */
  def unrecordedList(root: Path, service: String): Vector[String] =
    val f = root.resolve(s"tests/port/unrecorded-player-routes/$service.txt")
    if !java.nio.file.Files.exists(f) then Vector.empty
    else java.nio.file.Files.readAllLines(f).asScala.toVector.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#"))

  /** Every operation of a description, in the document's order. */
  def operations(document: JsonNode): Vector[Operation] =
    document.path("paths").properties().asScala.toVector.flatMap { e =>
      e.getValue.properties().asScala.toVector.collect {
        case m if Set("get", "put", "post", "delete", "patch").contains(m.getKey) =>
          val op = m.getValue
          Operation(Option(op.get("operationId")).map(_.asText()).getOrElse(s"${m.getKey} ${e.getKey}"), m.getKey, e.getKey, op)
      }
    }

  def run(description: Path, recordings: Vector[Path], routes: Vector[OriginalRoute],
      read: (Recording, Recording.Step) => Option[Recording.Step]): Result =
    val document = mapper.readTree(description.toFile)
    val ops      = operations(document).map(o => o.id -> o).toMap
    val components = Option(document.get("components"))
    val seen     = scala.collection.mutable.LinkedHashSet.empty[String]
    val differences = Vector.newBuilder[Difference]
    var matched  = 0
    routes.filterNot(r => ops.contains(r.operationId)).foreach { r =>
      differences += Difference("-", "-", r.operationId, "the route table names an operation the description does not have")
    }
    recordings.sortBy(_.toString).foreach { file =>
      val recording = Recording.load(file).fold(e => throw IllegalArgumentException(e), identity)
      recording.steps.foreach { recorded =>
        read(recording, recorded).foreach { step =>
          val path = step.request.path.takeWhile(_ != '?')
          routes.find(r => r.method.equalsIgnoreCase(step.request.method) && r.path.matches(path) &&
              r.host.forall(h => step.request.service.contains(h))).flatMap(r => ops.get(r.operationId)).foreach { op =>
            matched += 1
            seen += op.id
            check(op, step, components).foreach(what => differences += Difference(file.getFileName.toString, step.name, op.id, what))
          }
        }
      }
    }
    Result(differences.result(), ops.values.toVector.filterNot(o => seen.contains(o.id)).map(_.line).sorted, matched)

  /** What is wrong with one step's answer for an operation, if anything. */
  def check(op: Operation, step: Recording.Step, components: Option[JsonNode]): Option[String] =
    val status    = step.response.status
    val responses = op.node.path("responses")
    val answer    = Option(responses.get(status.toString)).orElse(Option(responses.get("default")))
    answer match
      case None if PlatformStatuses(status) => None
      case None => Some(s"status $status is not named (${responses.fieldNames().asScala.mkString(", ")})")
      case Some(a) =>
        val schema = Option(a.path("content").get("application/json")).flatMap(c => Option(c.get("schema")))
        (schema, step.response.body) match
          case (Some(s), Some(body)) =>
            val refused = validate(s, components, body)
            Option.when(refused.nonEmpty)(s"status $status: ${refused.mkString("; ")}")
          case _ => None
    end match

  /** The messages of a schema refusing a value, with the document's components in reach of its references. */
  def validate(schema: JsonNode, components: Option[JsonNode], value: JsonNode): Vector[String] =
    val root = schema.deepCopy[JsonNode]() match
      case o: ObjectNode => components.foreach(c => o.set[JsonNode]("components", c)); o
      case other         => other
    schemas.getSchema(root).validate(value.toString, InputFormat.JSON).asScala.toVector.map(_.getMessage).sorted
