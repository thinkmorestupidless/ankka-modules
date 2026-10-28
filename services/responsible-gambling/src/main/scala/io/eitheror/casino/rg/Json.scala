package io.eitheror.casino.rg

import com.fasterxml.jackson.databind.node.{ArrayNode, JsonNodeFactory, ObjectNode}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * JSON as trees, for the bodies whose shape is the provider's: bodies read fields that may be
 * strings or numbers and ignore the rest, and the answers are written field by field as the
 * original wrote them. Component state uses ankka's derived codecs instead.
 */
object Json:
  val mapper: ObjectMapper = ObjectMapper()
  private val nodes        = JsonNodeFactory.instance

  /** The body as a JSON object, or none when it is not one (FR-004: a bad request). */
  def parseObject(text: String): Option[ObjectNode] =
    Try(mapper.readTree(text)).toOption.collect { case o: ObjectNode => o }

  def parse(text: String): Option[JsonNode] = Try(mapper.readTree(text)).toOption.filter(_ != null)

  def obj(fields: (String, Any)*): ObjectNode =
    val o = nodes.objectNode()
    fields.foreach((k, v) => set(o, k, v))
    o

  def arr(values: Any*): ArrayNode =
    val a = nodes.arrayNode()
    values.foreach {
      case n: JsonNode => a.add(n)
      case s: String   => a.add(s)
      case l: Long     => a.add(l)
      case i: Int      => a.add(i)
      case b: Boolean  => a.add(b)
      case other       => a.add(other.toString)
    }
    a

  /** Sets a field; `None` leaves it out, `Some(v)` writes `v`. */
  def set(o: ObjectNode, key: String, value: Any): Unit = value match
    case None             => ()
    case Some(v)          => set(o, key, v)
    case n: JsonNode      => o.set[JsonNode](key, n): Unit
    case s: String        => o.put(key, s): Unit
    case l: Long          => o.put(key, l): Unit
    case i: Int           => o.put(key, i): Unit
    case b: Boolean       => o.put(key, b): Unit
    case d: Double        => o.put(key, d): Unit
    case xs: Iterable[?]  => o.set[JsonNode](key, arr(xs.toSeq*)): Unit
    case null             => o.putNull(key): Unit
    case other            => o.put(key, other.toString): Unit

  def render(node: JsonNode): String = mapper.writeValueAsString(node)

  extension (node: JsonNode)
    /** A field the protocol names as a string, accepted as a number too; empty is absent. */
    def text(field: String): Option[String] =
      Option(node.get(field)).filter(n => n.isTextual || n.isNumber).map(_.asText).map(_.trim).filter(_.nonEmpty)

    def textOr(field: String, default: String): String = text(field).getOrElse(default)

    def bool(field: String): Option[Boolean] =
      Option(node.get(field)).collect {
        case n if n.isBoolean => n.asBoolean
        case n if n.isTextual => n.asText.equalsIgnoreCase("true")
      }

    def long(field: String): Option[Long] =
      Option(node.get(field)).flatMap(n => if n.isNumber then Some(n.asLong) else n.asText.toLongOption)

    def strings(field: String): Vector[String] =
      Option(node.get(field)).filter(_.isArray).map(_.elements.asScala.map(_.asText).toVector).getOrElse(Vector.empty)

    def objects(field: String): Vector[JsonNode] =
      Option(node.get(field)).filter(_.isArray).map(_.elements.asScala.toVector).getOrElse(Vector.empty)

  extension (o: ObjectNode)
    /** A JSON object's fields as strings, for a body's free-form map (`credentials`, `properties`). */
    def stringMap(field: String): Map[String, String] =
      Option(o.get(field)).filter(_.isObject).map { n =>
        n.properties.asScala.toVector.collect { case e if e.getValue.isValueNode => e.getKey -> e.getValue.asText }.toMap
      }.getOrElse(Map.empty)

  def objOf(m: Map[String, String]): ObjectNode =
    val o = nodes.objectNode()
    m.toVector.sortBy(_._1).foreach((k, v) => o.put(k, v))
    o
