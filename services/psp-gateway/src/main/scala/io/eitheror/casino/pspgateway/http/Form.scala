package io.eitheror.casino.pspgateway.http

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/**
 * `application/x-www-form-urlencoded`, over the raw body (FR-012), as Go's `url.Values` reads and
 * writes it: a repeated key keeps every value in order, `Get` is the first, and encoding sorts by key.
 */
final case class Form(entries: Vector[(String, String)]):
  /** The first value of a key, as Go's `Values.Get`; none when absent (an empty value is `Some("")`). */
  def get(key: String): Option[String] = entries.collectFirst { case (k, v) if k == key => v }

  /** The first value, trimmed, none when absent or empty. */
  def text(key: String): Option[String] = get(key).map(_.trim).filter(_.nonEmpty)

  def all(key: String): Vector[String] = entries.collect { case (k, v) if k == key => v }
  def keys: Vector[String]            = entries.map(_._1).distinct
  def isEmpty: Boolean                = entries.isEmpty

  /** Every key with its values, as Go's `url.Values` holds them. */
  def values: Map[String, Vector[String]] = keys.map(k => k -> all(k)).toMap

  def without(key: String): Form = Form(entries.filterNot(_._1 == key))

  /** Encoded as Go's `Values.Encode`: keys sorted, values in order, `+` for a space. */
  def encode: String =
    entries.map(_._1).distinct.sorted.flatMap(k => all(k).map(v => s"${Form.escape(k)}=${Form.escape(v)}")).mkString("&")

object Form:
  def of(fields: (String, String)*): Form = Form(fields.toVector)

  /** A body or query string decoded; a pair that does not decode is dropped, as Go's `ParseQuery` skips it. */
  def decode(text: String): Form =
    Form(
      text.split('&').toVector.filter(_.nonEmpty).flatMap { pair =>
        val (k, v) = pair.indexOf('=') match
          case -1 => (pair, "")
          case i  => (pair.take(i), pair.drop(i + 1))
        for
          key   <- unescape(k)
          value <- unescape(v)
        yield key -> value
      }
    )

  def escape(s: String): String = URLEncoder.encode(s, UTF_8)

  private def unescape(s: String): Option[String] = Try(URLDecoder.decode(s, UTF_8)).toOption
