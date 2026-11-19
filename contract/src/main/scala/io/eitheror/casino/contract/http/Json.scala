package io.eitheror.casino.contract.http

import com.thinkmorestupidless.ankka.http.{FromBody, ToResponse}

/**
 * A JSON body a service has already encoded (spec 031). A route answering `Respond[Json]` is sent
 * exactly as `Respond[Bytes]` with `application/json` was: the same bytes, the same header. What
 * differs is its document: ankka files a described answer under the route's own content type, and a
 * `Bytes` route's is `application/octet-stream`, so a player route's JSON answers came out as bytes
 * and no generated client could read them. Under `Json` they are `application/json`.
 */
final case class Json(bytes: Array[Byte]):
  override def equals(that: Any): Boolean = that match
    case Json(b) => java.util.Arrays.equals(bytes, b)
    case _       => false
  override def hashCode: Int      = java.util.Arrays.hashCode(bytes)
  override def toString: String   = String(bytes, "UTF-8")

  /** The body as text, for a route that reads its own JSON. */
  def text: String = String(bytes, "UTF-8")

/**
 * Taken as a body, the same: a route that parses its own JSON (to answer its own refusal for an
 * unreadable one) reads `Json` where it read `String`, and its described body is filed as
 * `application/json`, not `text/plain`. The server reads the bytes whatever the request's type.
 */
object Json:
  given toResponse: ToResponse[Json] = new ToResponse[Json]:
    def status(value: Json): Int        = 200
    val contentType: String             = "application/json"
    def write(value: Json): Array[Byte] = value.bytes

  given fromBody: FromBody[Json] = new FromBody[Json]:
    val contentType: String                         = "application/json"
    def read(bytes: Array[Byte]): Either[String, Json] = Right(Json(bytes))
