package io.eitheror.casino.ingest.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients}
import io.eitheror.casino.ingest.{IngestRuntime, Json, Settings}
import io.eitheror.casino.ingest.Json.*
import io.eitheror.casino.ingest.api.Answers.Reply
import io.eitheror.casino.ingest.keys.KeyIndex
import io.eitheror.casino.ingest.merchant.MerchantClient
import io.eitheror.casino.ingest.rate.{RateWindow, Take}
import io.eitheror.casino.ingest.requests.*
import io.eitheror.casino.ingest.shape.Digests
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID

/** What every endpoint is given: how an operator's token is verified, and the service's clients. */
final class Ctx(val authenticate: () => Acl, val clients: EndpointClients):
  def settings: Settings = IngestRuntime.settings
  def now(): Instant     = IngestRuntime.now()
  val components         = clients.componentClient
  val views              = clients.viewClient
  lazy val merchant: MerchantClient = MerchantClient.http(clients.services, settings.merchantService)

/** A request answered before it reached `merchant`: what the caller is told and what is recorded. */
final case class Ended(reply: Reply, record: RecordState)

/**
 * One public request in the pipeline of research R2: its record id and arrival, minted as it comes in; each
 * step answers the value it found or the refusal that ends the request, and every end writes the request's
 * record once (FR-016). Nothing is logged but the record id, the route, the outcome and the sub-brand.
 */
final class Context(ctx: Ctx, val route: String, header: String => Option[String]):
  val recordId: String    = UUID.randomUUID().toString
  val receivedAt: Instant = ctx.now()

  /** The record as far as the request is known; each step adds to it. */
  var record: RecordState = RecordState(recordId, route = route, receivedAt = Some(receivedAt))

  def ended(reply: Reply, outcome: String, refusal: Option[String] = None): Ended =
    Ended(reply, record.copy(outcome = outcome, refusal = refusal))

  /** Writes the record and answers: the one way a request ends. */
  def finish(reply: Reply, r: RecordState): Reply =
    ctx.components.forKeyValueEntity(EntityId(recordId)).call(RequestRecord.write).invoke(r): Unit
    Context.log.info("request {} {} {} {}", recordId, route, r.outcome, r.subBrand.getOrElse("-"))
    reply

  def finish(e: Ended): Reply = finish(e.reply, e.record)

  /** FR-021: the bytes bounded before anything is parsed, then a JSON object. */
  def body(raw: String): Either[Ended, ObjectNode] =
    if raw.getBytes(UTF_8).length > ctx.settings.bodyBound then Left(ended(Answers.bodyTooLarge, Outcome.BodyTooLarge))
    else Json.parseObject(raw).toRight(ended(Answers.notJson, Outcome.NotJson))

  /** What a create's record keeps of its body, where it reads: the amount, the currency, the country, the request id. */
  def noted(body: JsonNode): Unit =
    record = record.copy(
      amount = Option(body.get("amountCents")).filter(n => n.isIntegralNumber && n.canConvertToLong).map(_.longValue),
      currency = body.text("currency"), country = body.text("country"),
      requestId = Option(body.get("transactionId")).filter(_.isTextual).map(_.asText).filter(_.nonEmpty))

  /**
   * FR-002, FR-005: the `merchantKey` header in any case, its digest found in the index and compared in
   * constant time, and only an active key admitted. The three refusals are recorded apart and answered
   * alike; a disabled key's record names its sub-brand.
   */
  def key(): Either[Ended, String] =
    header(Context.KeyHeader).map(_.trim).filter(_.nonEmpty) match
      case None => Left(ended(Answers.unauthenticated, Outcome.KeyMissing))
      case Some(raw) =>
        val started = System.nanoTime()
        val digest  = Digests.ofKey(raw)
        val row     = KeyIndex.find(ctx.components, digest).filter(r => Digests.same(r.digest, digest))
        Context.onKeyStep(System.nanoTime() - started)
        row match
          case Some(r) if r.active =>
            record = record.copy(subBrand = Some(r.subBrand))
            Right(r.subBrand)
          case Some(disabled) =>
            record = record.copy(subBrand = Some(disabled.subBrand))
            Left(ended(Answers.invalidKey, Outcome.KeyDisabled))
          case None => Left(ended(Answers.invalidKey, Outcome.KeyUnknown))

  /** FR-015: one request of the sub-brand's minute, or `429` with the seconds to wait. */
  def take(subBrand: String): Either[Ended, Unit] =
    record = record.copy(subBrand = Some(subBrand))
    val taken = ctx.components.forKeyValueEntity(EntityId(subBrand)).call(RateWindow.take).invoke(Take(ctx.now(), ctx.settings.rateLimit))
    if taken.served then Right(()) else Left(ended(Answers.tooMany(taken.waitSeconds), Outcome.Rate))

  /** Which sub-brand an ownership names, by ticket or payment id. */
  def owner(id: String): Option[OwnershipState] =
    ctx.components.forKeyValueEntity(EntityId(id)).call(Ownership.read).invoke()

object Context:
  private val log = LoggerFactory.getLogger(classOf[Context])

  val KeyHeader    = "merchantKey"
  val TicketHeader = "cashierTicket"

  /** A test's hook on the key step's duration in nanoseconds (SC-003); nothing in the service reads it. */
  @volatile var onKeyStep: Long => Unit = _ => ()

  /** Runs a request's steps: what they answer, or the refusal that ended them, written and answered. */
  def run(c: Context)(steps: => Either[Ended, Reply]): Reply = steps.fold(c.finish, identity)
