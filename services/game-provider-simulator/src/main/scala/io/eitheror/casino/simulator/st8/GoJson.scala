package io.eitheror.casino.simulator.st8

import com.fasterxml.jackson.databind.JsonNode

import scala.jdk.CollectionConverters.*

/**
 * JSON written byte for byte as the original's stand-in writes it with Go's `encoding/json`
 * (contracts/st8-wire.md, "Conventions"). `marshal` is `json.Marshal`: no trailing newline.
 * `encode` is `json.NewEncoder(w).Encode`: a trailing newline. Both escape `<`, `>` and `&` as
 * `<`, `>` and `&`, and U+2028 and U+2029, as Go's HTML-safe default does. A Go map
 * is written with its keys sorted; a Go struct in its declared field order: `Obj` keeps the order
 * it is given, and `sorted` sorts.
 */
object GoJson:

  enum Value:
    case Str(s: String)
    case Num(n: String)
    case Bool(b: Boolean)
    case Null
    case Arr(items: Vector[Value])
    case Obj(fields: Vector[(String, Value)])
    /** Already-encoded JSON, embedded as is (an adapter's answer the stand-in passes through). */
    case Raw(json: String)

  import Value.*

  def str(s: String): Value                     = Str(s)
  def obj(fields: (String, Value)*): Value       = Obj(fields.toVector)
  def sorted(fields: (String, Value)*): Value    = Obj(fields.toVector.sortBy(_._1))
  def strings(fields: (String, String)*): Value  = sorted(fields.map((k, v) => k -> Str(v))*)
  def arr(items: Value*): Value                  = Arr(items.toVector)
  def strArr(items: Seq[String]): Value          = Arr(items.toVector.map(Str(_)))

  def marshal(value: Value): String = render(value)
  def encode(value: Value): String  = render(value) + "\n"

  /** A Jackson tree as Go would re-encode it after decoding into `any`: map keys sorted. */
  def fromJackson(node: JsonNode): Value =
    if node == null || node.isNull then Null
    else if node.isTextual then Str(node.asText())
    else if node.isBoolean then Bool(node.asBoolean())
    else if node.isNumber then Num(node.numberValue().toString)
    else if node.isArray then Arr(node.elements().asScala.toVector.map(fromJackson))
    else Obj(node.properties().asScala.toVector.map(e => e.getKey -> fromJackson(e.getValue)).sortBy(_._1))

  private def render(value: Value): String =
    val b = StringBuilder()
    write(b, value)
    b.toString

  private def write(b: StringBuilder, value: Value): Unit = value match
    case Str(s)  => quote(b, s)
    case Num(n)  => b.append(n)
    case Bool(v) => b.append(if v then "true" else "false")
    case Null    => b.append("null")
    case Raw(j)  => b.append(j)
    case Arr(items) =>
      b.append('[')
      items.zipWithIndex.foreach { (item, i) =>
        if i > 0 then b.append(',')
        write(b, item)
      }
      b.append(']')
    case Obj(fields) =>
      b.append('{')
      fields.zipWithIndex.foreach { case ((k, v), i) =>
        if i > 0 then b.append(',')
        quote(b, k)
        b.append(':')
        write(b, v)
      }
      b.append('}')

  private val hex = "0123456789abcdef"

  private def quote(b: StringBuilder, s: String): Unit =
    b.append('"')
    s.foreach {
      case '"'  => b.append("\\\"")
      case '\\' => b.append("\\\\")
      case '\n' => b.append("\\n")
      case '\r' => b.append("\\r")
      case '\t' => b.append("\\t")
      case '\b' => b.append("\\b")
      case '\f' => b.append("\\f")
      case c if c < 0x20 || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ' =>
        b.append("\\u")
        b.append(hex((c >> 12) & 0xf)).append(hex((c >> 8) & 0xf)).append(hex((c >> 4) & 0xf)).append(hex(c & 0xf))
      case c => b.append(c)
    }
    b.append('"')
