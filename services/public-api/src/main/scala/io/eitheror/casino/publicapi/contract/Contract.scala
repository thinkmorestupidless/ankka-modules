package io.eitheror.casino.publicapi.contract

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** One operation of the contract: its method, its path template under `/v1` (`/players/{playerId}`) and its id. */
final case class Operation(method: String, template: String, operationId: String)

/**
 * The public contract, read once at start from the committed YAML (research R2; original `contract/load.ts`). `json`
 * is what `GET /v1/openapi.json` answers: the file as JSON, with `servers[0].url` set to the brand's address.
 * `reference` is the human page with that document embedded, so it never describes another contract (original
 * `contract/serve.ts`).
 */
final class Contract private (val document: JsonNode, val json: String, val reference: String, val operations: Vector[Operation]):
  /** The contract's page-size cap, which the service refuses to start without (original `load.ts:27-32`). */
  val maxPageSize: Int = Contract.MaxPageSize

object Contract:
  val Resource    = "public-api-v1.openapi.yaml"
  val MaxPageSize = 100
  private val Placeholder = "/*__SPEC__*/null"

  private val yaml = new ObjectMapper(new YAMLFactory())
  private val json = new ObjectMapper()

  /** The committed file, parsed as it is: no server address set. */
  def file(): JsonNode = Using.resource(resource(Resource))(yaml.readTree)

  /** The contract served, or why it cannot be: a missing file, or a page-size cap other than 100. */
  def load(serverUrl: String): Either[String, Contract] =
    for
      doc <- scala.util.Try(file()).toEither.left.map(e => s"the contract $Resource cannot be read: ${e.getMessage}")
      _   <- cap(doc)
    yield
      val served = doc.deepCopy[JsonNode]()
      served.path("servers").elements().asScala.toVector.headOption.collect { case o: ObjectNode => o.put("url", serverUrl) }
      val rendered = json.writeValueAsString(served)
      val page     = Using.resource(resource("reference.html"))(s => String(s.readAllBytes(), StandardCharsets.UTF_8))
      // `</` would close the inline <script> early.
      new Contract(served, rendered, page.replace(Placeholder, rendered.replace("</", "<\\/")), operations(doc))

  private def cap(doc: JsonNode): Either[String, Unit] =
    val declared = doc.path("components").path("parameters").path("limit").path("schema").path("maximum")
    Either.cond(declared.isInt && declared.asInt() == MaxPageSize, (), s"the contract's limit maximum is ${declared.asText("absent")}, not $MaxPageSize")

  private val Methods = Set("get", "put", "post", "delete", "patch", "head", "options")

  private def operations(doc: JsonNode): Vector[Operation] =
    doc.path("paths").properties().asScala.toVector.flatMap { entry =>
      entry.getValue.properties().asScala.toVector.collect {
        case m if Methods(m.getKey) => Operation(m.getKey.toUpperCase, entry.getKey, m.getValue.path("operationId").asText())
      }
    }

  private def resource(name: String) =
    Option(getClass.getClassLoader.getResourceAsStream(name)).getOrElse(throw IllegalStateException(s"$name is not on the classpath"))
