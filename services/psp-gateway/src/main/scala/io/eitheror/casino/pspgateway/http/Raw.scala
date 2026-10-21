package io.eitheror.casino.pspgateway.http

/**
 * A webhook as it arrived (FR-012): the body's bytes as text, untouched, with the request's content
 * type, headers and query. Verification is over `body`; nothing is parsed before it.
 */
final case class Raw(body: String, contentType: Option[String] = None, headers: Vector[(String, String)] = Vector.empty,
    query: Map[String, String] = Map.empty):

  def header(name: String): Option[String] = headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }

  /** The media type alone, lower case: `application/x-www-form-urlencoded` from `...; charset=utf-8`. */
  def mediaType: Option[String] = contentType.map(_.takeWhile(_ != ';').trim.toLowerCase).filter(_.nonEmpty)

  def isForm: Boolean = mediaType.contains("application/x-www-form-urlencoded")
  def isJson: Boolean = mediaType.exists(t => t == "application/json" || t.endsWith("+json"))

  /** The body as a form, whatever its content type says. */
  def form: Form = Form.decode(body)

  /** Whether the body is empty, told apart from a body of another content type (FR-012). */
  def isEmpty: Boolean = body.trim.isEmpty

  /**
   * DreamzPay's reading (`webhook.go:61-110`): a form by content type, JSON by content type, and when
   * the content type says neither, whichever parses with fields; the other is the fallback.
   */
  def sniffed: Raw.Body =
    def asJson = io.eitheror.casino.pspgateway.Json.parseObject(body).filter(_.size > 0).map(Raw.Body.Json(_))
    def asForm = Some(form).filter(!_.isEmpty).filter(_ => !body.trim.startsWith("{")).map(Raw.Body.Fields(_))
    val read =
      if isForm then asForm.orElse(asJson)
      else if isJson then asJson.orElse(asForm)
      else asJson.orElse(asForm)
    read.getOrElse(Raw.Body.Empty)

object Raw:
  enum Body:
    case Fields(form: Form)
    case Json(node: com.fasterxml.jackson.databind.node.ObjectNode)
    case Empty

    /** A field of either shape as text. */
    def text(key: String): Option[String] = this match
      case Fields(f) => f.text(key)
      case Json(n)   => io.eitheror.casino.pspgateway.Json.text(n)(key)
      case Empty     => None
