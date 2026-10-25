package io.eitheror.casino.ingest.shape

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{ArrayNode, JsonNodeFactory, ObjectNode}
import io.eitheror.casino.ingest.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat
import scala.jdk.CollectionConverters.*

/**
 * Lower-case SHA-256 hex: of a raw cashier key, as `merchant` and the original's `validateKey` hash it
 * (research R3), and of a create body with its object keys sorted at every depth, so two sends of the same
 * content under one request id are the same whatever order the caller wrote them in (R5).
 */
object Digests:
  def ofKey(raw: String): String = hex(raw.getBytes(UTF_8))

  def ofBody(body: JsonNode): String = hex(Json.render(sorted(body)).getBytes(UTF_8))

  /** Constant time over the digests' bytes (FR-002): never a string compare. */
  def same(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))

  private def hex(bytes: Array[Byte]): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def sorted(node: JsonNode): JsonNode = node match
    case o: ObjectNode =>
      val out = JsonNodeFactory.instance.objectNode()
      o.properties.asScala.toVector.sortBy(_.getKey).foreach(e => out.set[JsonNode](e.getKey, sorted(e.getValue)))
      out
    case a: ArrayNode =>
      val out = JsonNodeFactory.instance.arrayNode()
      a.elements.asScala.foreach(e => out.add(sorted(e)))
      out
    case other => other
