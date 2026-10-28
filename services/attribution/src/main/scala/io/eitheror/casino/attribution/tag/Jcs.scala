package io.eitheror.casino.attribution.tag

import com.fasterxml.jackson.databind.JsonNode

import java.math.BigDecimal as JBigDecimal
import scala.jdk.CollectionConverters.*

/**
 * The JSON Canonicalization Scheme (RFC 8785): members sorted by their names' UTF-16 code units, no
 * whitespace, strings escaped as ECMAScript's `JSON.stringify` does, numbers as ECMAScript prints a
 * double. A feed page is signed over this form of its `facts` (contracts/protocol.md).
 */
object Jcs:

  def canonical(node: JsonNode): String =
    val out = StringBuilder()
    write(node, out)
    out.toString

  def bytes(node: JsonNode): Array[Byte] = canonical(node).getBytes(java.nio.charset.StandardCharsets.UTF_8)

  private def write(node: JsonNode, out: StringBuilder): Unit =
    if node == null || node.isNull || node.isMissingNode then out.append("null")
    else if node.isObject then
      out.append('{')
      node.fieldNames.asScala.toVector.sorted.zipWithIndex.foreach { (name, i) =>
        if i > 0 then out.append(',')
        string(name, out)
        out.append(':')
        write(node.get(name), out)
      }
      out.append('}')
    else if node.isArray then
      out.append('[')
      node.elements.asScala.zipWithIndex.foreach { (n, i) =>
        if i > 0 then out.append(',')
        write(n, out)
      }
      out.append(']')
    else if node.isTextual then string(node.asText, out)
    else if node.isBoolean then out.append(if node.asBoolean then "true" else "false")
    else if node.isNumber then out.append(number(node.decimalValue))
    else string(node.asText, out)

  private def string(s: String, out: StringBuilder): Unit =
    out.append('"')
    s.foreach {
      case '"'  => out.append("\\\"")
      case '\\' => out.append("\\\\")
      case '\b' => out.append("\\b")
      case '\f' => out.append("\\f")
      case '\n' => out.append("\\n")
      case '\r' => out.append("\\r")
      case '\t' => out.append("\\t")
      case c if c < 0x20 => out.append(f"\\u${c.toInt}%04x")
      case c    => out.append(c)
    }
    out.append('"')

  /**
   * A number as ECMAScript's `Number.prototype.toString` prints the double nearest it: the shortest digits
   * that read back as that double (the JDK's `Double.toString` since 19), then placed by ECMAScript's rule.
   */
  def number(value: JBigDecimal): String =
    val d = value.doubleValue
    if d.isNaN || d.isInfinite then throw IllegalArgumentException(s"$value is not a JSON number")
    if d == 0.0 then "0"
    else
      val shortest = JBigDecimal(java.lang.Double.toString(math.abs(d))).stripTrailingZeros
      val digits   = shortest.unscaledValue.toString
      val k        = digits.length
      val n        = k - shortest.scale // the value is 0.digits × 10^n
      val text =
        if k <= n && n <= 21 then digits + "0" * (n - k)
        else if 0 < n && n <= 21 then digits.take(n) + "." + digits.drop(n)
        else if -6 < n && n <= 0 then "0." + "0" * -n + digits
        else
          val e    = n - 1
          val sign = if e < 0 then "-" else "+"
          (if k == 1 then digits else digits.head.toString + "." + digits.tail) + "e" + sign + math.abs(e)
      if d < 0 then "-" + text else text
