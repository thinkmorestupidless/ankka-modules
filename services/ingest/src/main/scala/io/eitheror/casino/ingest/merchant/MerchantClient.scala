package io.eitheror.casino.ingest.merchant

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.requests.Answer

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** An active key as `merchant`'s index lists it (004 `contracts/commands.md`, `GET /keys`). */
final case class KeyEntry(digest: String, keyId: String, subBrandId: String)

/**
 * The seam to `merchant` (contracts/merchant.md): the four cashier commands and the key index, each
 * answered as `merchant`'s status and body, a `4xx` among them, never thrown. A call that got no answer
 * (`ServiceUnresolvable`, `ServiceUnanswered`) is a failed future, which the answer bound reads as one.
 */
trait MerchantClient:
  def create(command: JsonNode): Future[Answer]
  def submit(ticket: String, body: JsonNode): Future[Answer]
  def abandon(ticket: String): Future[Answer]
  def read(paymentId: String): Future[Answer]
  def keys(): Future[Seq[KeyEntry]]

object MerchantClient:
  /** `merchant` in this project, by identity: the certificate says who is calling (FR-012). */
  def http(services: ServiceClients, name: String): MerchantClient = HttpMerchant(services, name)

  /** Each call on a virtual thread of its own, so the handler can bound it and still hear it late. */
  private[merchant] given calls: ExecutionContext = ExecutionContext.fromExecutor(Executors.newVirtualThreadPerTaskExecutor())

  def segment(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20")

  /** `merchant`'s `keys[{digest, keyId, subBrandId}]`; an entry missing a field is skipped. */
  def entries(body: JsonNode): Seq[KeyEntry] =
    Option(body.get("keys")).filter(_.isArray).map(_.elements.asScala.toVector.flatMap { k =>
      for
        digest <- Json.text(k)("digest")
        keyId  <- Json.text(k)("keyId")
        sub    <- Json.text(k)("subBrandId")
      yield KeyEntry(digest, keyId, sub)
    }).getOrElse(Vector.empty)

private final class HttpMerchant(services: ServiceClients, name: String) extends MerchantClient:
  import MerchantClient.{calls, entries, segment}

  private def call(method: String, path: String, body: Option[JsonNode]): Future[Answer] = Future {
    val r = services(name).request(method, path, body.map(b => Json.render(b).getBytes(UTF_8)), body.map(_ => "application/json"))
    Answer(r.status, r.text)
  }

  def create(command: JsonNode): Future[Answer] = call("POST", "/v1/cashier/sessions", Some(command))
  def submit(ticket: String, body: JsonNode): Future[Answer] = call("POST", s"/v1/cashier/sessions/${segment(ticket)}/submit", Some(body))
  def abandon(ticket: String): Future[Answer] = call("POST", s"/v1/cashier/sessions/${segment(ticket)}/abandon", None)
  def read(paymentId: String): Future[Answer] = call("GET", s"/v1/cashier/payments/${segment(paymentId)}", None)

  def keys(): Future[Seq[KeyEntry]] =
    call("GET", "/v1/cashier/keys", None).flatMap { a =>
      if a.ok then Future.successful(a.json.map(entries).getOrElse(Vector.empty))
      else Future.failed(IllegalStateException(s"$name answered ${a.status} to the key index"))
    }
