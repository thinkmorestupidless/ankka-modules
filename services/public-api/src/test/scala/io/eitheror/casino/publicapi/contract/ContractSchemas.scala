package io.eitheror.casino.publicapi.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.{JsonSchemaFactory, SchemaValidatorsConfig, SpecVersion}

import scala.jdk.CollectionConverters.*

/**
 * Validates a body against the contract's schema for a route and status (original `test/contract/validator.ts`).
 * OpenAPI 3.1's schemas are JSON Schema 2020-12 and point into the document (`#/components/...`), so a schema is the
 * whole document with a root `$ref` to the response's schema; the document's other members are unknown keywords and
 * ignored. Formats are asserted, as the original's `ajv-formats`.
 */
object ContractSchemas:
  val document: JsonNode = Contract.file()
  private val factory    = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
  private val config     = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()

  private def escape(s: String) = s.replace("~", "~0").replace("/", "~1")
  private def at(pointer: String): JsonNode = document.at(pointer.stripPrefix("#"))

  /** The statuses the contract documents for a route. */
  def statuses(template: String): Set[Int] =
    document.path("paths").path(template).path("get").path("responses").fieldNames().asScala.map(_.toInt).toSet

  /** The JSON pointer of the body schema of a route at a status, following `$ref`s between responses. */
  def pointer(template: String, status: Int): String =
    var p = s"#/paths/${escape(template)}/get/responses/$status"
    var r = at(p)
    require(!r.isMissingNode, s"the contract documents no $status for GET $template")
    while r.has("$ref") do { p = r.path("$ref").asText(); r = at(p) }
    require(r.path("content").path("application/json").has("schema"), s"$status for GET $template has no JSON body schema")
    s"$p/content/application~1json/schema"

  /** By pointer, a schema the document's references resolve in. */
  def schemaAt(pointer: String) =
    val root = document.deepCopy[ObjectNode]()
    root.put("$schema", "https://json-schema.org/draft/2020-12/schema").put("$ref", pointer)
    factory.getSchema(root, config)

  private val cache = scala.collection.concurrent.TrieMap.empty[String, com.networknt.schema.JsonSchema]

  /** What is wrong with `body` as the route's answer at `status`; empty when it is valid. */
  def problems(template: String, status: Int, body: JsonNode): Vector[String] =
    val s = cache.getOrElseUpdate(s"$template $status", schemaAt(pointer(template, status)))
    s.validate(body).asScala.toVector.map(m => s"${m.getInstanceLocation}: ${m.getMessage}").sorted
