package io.eitheror.casino.pspgateway.providers

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.http.Form

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A provider credential as `merchant` returned it for one use (research R7): the keys the original's
 * terminal properties use, held only for the call and never logged. `toString` names the keys only.
 */
final case class Credential(values: Map[String, String]):
  /** A value, trimmed; none when absent or empty. */
  def get(key: String): Option[String] = values.get(key).map(_.trim).filter(_.nonEmpty)

  /**
   * A per-method value where the provider keys one by method (`terminalId.VISA`, `checkoutId.APPLEPAY`),
   * falling back to the plain key, as the original's adapters read them.
   */
  def forMethod(key: String, method: String): Option[String] =
    Option(method).map(_.trim.toUpperCase).filter(_.nonEmpty).flatMap(m => get(s"$key.$m")).orElse(get(key))

  def isEmpty: Boolean = values.values.forall(_.trim.isEmpty)

  /** The secret values, for the leak suites and the log scrubber: never for anything else. */
  def secretValues: Set[String] = values.values.map(_.trim).filter(_.length >= 4).toSet

  override def toString: String = s"Credential(${values.keys.toVector.sorted.mkString(", ")})"

object Credential:
  val empty: Credential = Credential(Map.empty)

/**
 * The player fields `merchant` sends at initiation (FR-002), passed to the provider that needs them and
 * kept nowhere (FR-022). `toString` names none of them.
 */
final case class Customer(
    email: Option[String] = None,
    firstName: Option[String] = None,
    lastName: Option[String] = None,
    phone: Option[String] = None,
    mobile: Option[String] = None,
    province: Option[String] = None,
    ip: Option[String] = None,
    country: Option[String] = None,
    city: Option[String] = None,
    address: Option[String] = None,
    postCode: Option[String] = None,
    state: Option[String] = None
):
  override def toString: String = "Customer(…)"

object Customer:
  /** From the initiate body's `customer*` fields (004 `gateway.md`). */
  def from(body: JsonNode): Customer =
    import Json.text
    Customer(
      body.text("customerEmail"), body.text("customerFirstName"), body.text("customerLastName"), body.text("customerPhone"),
      body.text("customerMobile"), body.text("customerProvince"), body.text("customerIp"), body.text("customerCountry"),
      body.text("customerCity"), body.text("customerAddress"), body.text("customerPostCode"), body.text("customerState")
    )

/** One call to a provider, built by its pure package and made by `ProviderHttp`. */
final case class Outbound(
    method: String,
    url: String,
    headers: Vector[(String, String)] = Vector.empty,
    body: Option[String] = None,
    contentType: Option[String] = None
):
  /** For the log and the status page: the method and the address without its query. */
  def describe: String = s"$method ${url.takeWhile(_ != '?')}"
  override def toString: String = s"Outbound($describe)"

object Outbound:
  def get(url: String, headers: (String, String)*): Outbound = Outbound("GET", url, headers.toVector)

  def json(url: String, body: JsonNode, headers: (String, String)*): Outbound =
    Outbound("POST", url, headers.toVector, Some(Json.render(body)), Some("application/json"))

  def form(url: String, form: Form, headers: (String, String)*): Outbound =
    Outbound("POST", url, headers.toVector, Some(form.encode), Some("application/x-www-form-urlencoded"))

/** A provider's answer to a call: its status, headers (names lower case) and body. */
final case class Answered(status: Int, headers: Map[String, String], body: String):
  def ok: Boolean                = status / 100 == 2
  def json: Option[JsonNode]     = Json.parse(body).filter(_ => body.trim.nonEmpty)
  def header(name: String): Option[String] = headers.get(name.toLowerCase)
  override def toString: String  = s"Answered($status)"

/** What the gateway answers a provider's webhook with (R9's *answer* column). */
final case class Reply(status: Int, body: String = "", contentType: String = "application/json")

object Reply:
  /** The plain acknowledgement most providers take: `200`, empty. */
  val ok: Reply = Reply(200, "", "text/plain; charset=utf-8")
  /**
   * A webhook whose signature does not verify is a bad request, whatever the provider answers to the
   * rest (a decision of 2026-10-19, not the original's: it acknowledged most). A provider that retries
   * a non-`200` redelivers it, which lands once a rotated secret is put right.
   */
  val invalidSignature: Reply = text(400, "invalid signature\n")
  def json(status: Int, node: JsonNode): Reply = Reply(status, Json.render(node), "application/json")
  def text(status: Int, body: String): Reply   = Reply(status, body, "text/plain; charset=utf-8")

/** The digests the providers sign with, each as the original computes it. */
object Digests:
  def sha256(text: String): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8))
  def sha256Hex(text: String): String   = hex(sha256(text))
  def md5Hex(text: String): String      = hex(MessageDigest.getInstance("MD5").digest(text.getBytes(UTF_8)))

  def hmacSha256(key: String, data: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(data)

  def hmacSha256(key: String, text: String): Array[Byte] = hmacSha256(key, text.getBytes(UTF_8))

  def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
  def base64(bytes: Array[Byte]): String = Base64.getEncoder.encodeToString(bytes)
  def base64Url(bytes: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** Constant time over the bytes of the two texts (the port's comparison everywhere; R9). */
  def same(a: String, b: String): Boolean = MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))
  def same(a: Array[Byte], b: Array[Byte]): Boolean = MessageDigest.isEqual(a, b)

/**
 * Why a webhook did not verify, or a call was refused: a reason for the log, never a value.
 * `Rejected` is what a provider's verification says of a message.
 */
final case class Rejected(reason: String)
