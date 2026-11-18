package io.eitheror.casino.players.clients

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
 * The cashier's answer to a create (006 `contracts/routes.md`): where to send the player, the session's
 * `transactionId` (the request id, `paymentId` to callers that read it), its ticket (`session_token`)
 * and its `available_terminals` as the cashier wrote them (spec 027 FR-018).
 */
enum CashierOutcome:
  case Opened(redirectUrl: String, paymentId: String, cashierTicket: String, terminals: JsonNode)
  /**
   * `ingest` or `merchant` refused: the status, the `error` code and the refusal's other fields
   * (`transactionId` of a `409 REQUEST_ID_REUSED`, `retryAfterSeconds`, `field`), passed to the player.
   */
  case Refused(status: Int, code: String, fields: Vector[(String, JsonNode)] = Vector.empty)

/**
 * `ingest`'s public cashier route in the shared `payments` project (FR-043, R15), `POST
 * /v1/cashier/sessions` under the brand's merchant key (`players-merchant-key`), at `INGEST_URL`. The
 * body carries the customer fields spec 001 FR-018 names; they are forwarded, never kept.
 */
trait Ingest:
  def createSession(body: JsonNode, merchantKey: String, bound: Duration): Raced[CashierOutcome]

object Ingest:
  val Service = "ingest"

  def http(baseUrl: String): Ingest = HttpIngest(baseUrl.stripSuffix("/"))

  def read(r: Reply): Either[String, CashierOutcome] =
    if r.ok then
      (for
        b      <- r.body
        url    <- b.text("redirectUrl")
        id     <- b.text("transactionId")
        ticket <- b.text("session_token")
      yield CashierOutcome.Opened(url, id, ticket, Option(b.get("available_terminals")).filter(_.isArray).getOrElse(Json.arr())))
        .toRight("no redirectUrl, transactionId or session_token")
    else if r.status == 503 then Left("TRY_AGAIN")
    else
      val fields = r.body.filter(_.isObject).toVector.flatMap(_.properties.asScala.toVector.map(e => e.getKey -> e.getValue))
        .filterNot((k, _) => k == "error" || k == "message" || k == "service")
      Right(CashierOutcome.Refused(r.status, r.body.flatMap(_.text("error")).getOrElse(s"status_${r.status}"), fields))

private final class HttpIngest(base: String) extends Ingest:
  import Neighbour.calls
  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()

  def createSession(body: JsonNode, merchantKey: String, bound: Duration): Raced[CashierOutcome] =
    Neighbour.bounded(Ingest.Service, bound)(Future {
      val req = HttpRequest.newBuilder(URI.create(s"$base/v1/cashier/sessions")).header("Content-Type", "application/json")
        .header("merchantKey", merchantKey).POST(HttpRequest.BodyPublishers.ofString(Json.render(body))).build()
      val r = http.send(req, HttpResponse.BodyHandlers.ofString())
      Reply(r.statusCode, Json.parse(r.body).filter(_ => r.body.trim.nonEmpty), r.body)
    })(Ingest.read)
