package io.eitheror.casino.publicapi.contract

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * T086 (FR-001, FR-002): the served contract and the original's copy differ only at contracts/amendments.md's paths.
 * Descriptions and summaries are matched by key name, wherever they are; any other difference fails, naming its path.
 */
class ContractAmendmentSuite extends munit.FunSuite:
  private val yaml = ObjectMapper(YAMLFactory())

  private def repo: Path =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null).find(p => Files.isDirectory(p.resolve("specs/016-public-api")))
      .getOrElse(fail("no specs/016-public-api above the working directory"))
  private lazy val original: JsonNode = yaml.readTree(repo.resolve("specs/016-public-api/contracts/original/public-api-v1.openapi.yaml").toFile)

  /** contracts/amendments.md's table, as path patterns (`*` one segment). */
  val Allowed: Vector[String] = Vector("info.title", "info.version", "info.description", "servers.*.url", "components.securitySchemes.bearerAuth.*",
    "components.schemas.Error.properties.error.enum", "components.schemas.Error.properties.error.enum.*",
    "components.responses.Unauthorized.description", "components.responses.Forbidden.description",
    "components.responses.TooManyRequests.description", "components.responses.NotFound.description")

  def allowed(path: Vector[String]): Boolean =
    path.lastOption.exists(k => k == "summary" || k == "description") ||
      Allowed.exists { a => val p = a.split('.').toVector; p.size == path.size && p.zip(path).forall((x, y) => x == "*" || x == y) }

  /** Every JSON path where `a` and `b` differ: a changed leaf, or a member or element only one of them holds. */
  def differences(a: JsonNode, b: JsonNode, at: Vector[String] = Vector.empty): Vector[Vector[String]] =
    if a.isObject && b.isObject then
      (a.fieldNames().asScala ++ b.fieldNames().asScala).toVector.distinct.flatMap { k =>
        (Option(a.get(k)), Option(b.get(k))) match
          case (Some(x), Some(y)) => differences(x, y, at :+ k)
          case _                  => Vector(at :+ k)
      }
    else if a.isArray && b.isArray && a.size == b.size then
      (0 until a.size).toVector.flatMap(i => differences(a.get(i), b.get(i), at :+ i.toString))
    else if a == b then Vector.empty
    else Vector(at)

  test("the served contract differs from the original only where contracts/amendments.md allows") {
    val bad = differences(original, Contract.file()).filterNot(allowed)
    assertEquals(bad.map(_.mkString(".")), Vector.empty)
  }

  test("the amendments are made: the credential, the version and the error codes") {
    val f = Contract.file()
    assertEquals(f.path("info").path("version").asText(), "1.1.0")
    assertEquals(f.path("components").path("securitySchemes").path("bearerAuth").path("bearerFormat").asText(), "access key")
    assertEquals(f.path("components").path("schemas").path("Error").path("properties").path("error").path("enum").elements().asScala.map(_.asText()).toVector,
      Vector("invalid_request", "limit_exceeded", "key_missing", "key_invalid", "key_expired", "key_disabled", "not_found", "snapshot_expired",
        "quota_exceeded", "unavailable"))
  }

  test("a change anywhere else fails, naming its path") {
    val changed = Contract.file().deepCopy[ObjectNode]()
    changed.path("components").path("schemas").path("Player").path("properties").asInstanceOf[ObjectNode].remove("brandId")
    changed.path("paths").path("/players").path("get").asInstanceOf[ObjectNode].put("operationId", "players")
    val bad = differences(original, changed).filterNot(allowed).map(_.mkString("."))
    assert(bad.contains("components.schemas.Player.properties.brandId"), bad.toString)
    assert(bad.contains("paths./players.get.operationId"), bad.toString)
  }
