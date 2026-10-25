package io.eitheror.casino.players.clients

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.Future

/** The cashier's answer to a create (006 `contracts/routes.md`): where to send the player, and the payment. */
enum CashierOutcome:
  case Opened(redirectUrl: String, paymentId: String)
  /** `ingest` or `merchant` refused: the status and the `error` code, passed to the player. */
  case Refused(status: Int, code: String)

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
        b   <- r.body
        url <- b.text("redirectUrl")
        id  <- b.text("transactionId")
      yield CashierOutcome.Opened(url, id)).toRight("no redirectUrl or transactionId")
    else if r.status == 503 then Left("TRY_AGAIN")
    else Right(CashierOutcome.Refused(r.status, r.body.flatMap(_.text("error")).getOrElse(s"status_${r.status}")))

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
