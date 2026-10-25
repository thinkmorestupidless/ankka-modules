package io.eitheror.casino.ingest.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.Json.*

import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, TimeUnit}
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*

/**
 * `merchant` as `ingest` calls it (contracts/merchant.md; 004 `contracts/commands.md`), answering from a script:
 * the create answers a session (a minted `session_token`, the `transactionId` echoed, the terminals it is told
 * to offer, `resolvedFrom`) or a scripted refusal, and can be held for a while or until released; a submit
 * answers the payment once per ticket and that first answer again on a repeat (004 correction 2); the read
 * answers a payment in the original's fields; `/keys` answers the index it is given. Everything received is
 * kept by route, so a test can say what `merchant` was and was not asked, and as which sub-brand.
 */
final class FakeMerchant(bind: Int = 0) extends FakeServer(bind):
  import FakeMerchant.*

  @volatile var terminals: Vector[String]           = Vector("sim-eur")
  @volatile var refuseCreate: Option[(Int, String)] = None
  @volatile var refuseSubmit: Option[Answer]        = None
  @volatile var delay: Long                         = 0
  @volatile var keys: Vector[(String, String, String)] = Vector.empty
  /** A recording's answers, before the script: what the original answered the step a call stands for. */
  @volatile var recorded: PartialFunction[Received, Answer] = PartialFunction.empty

  private val held     = ConcurrentHashMap[String, (CountDownLatch, Answer)]()
  private val waiting  = ConcurrentHashMap[String, CountDownLatch]()
  private val sessions = TrieMap.empty[String, Session]
  private val payments = TrieMap.empty[String, Payment]
  private val submits  = TrieMap.empty[String, Answer]

  def reset(): Unit =
    terminals = Vector("sim-eur"); refuseCreate = None; refuseSubmit = None; delay = 0; recorded = PartialFunction.empty
    held.values.asScala.foreach(_._1.countDown())
    waiting.values.asScala.foreach(_.countDown())
    held.clear()
    waiting.clear()
    clearReceived()

  /** The next create under `transactionId` (or submit under a ticket) is not answered until released, then answers `answer`. */
  def hold(id: String, answer: Answer = Answer(0, "")): Unit = held.put(id, (CountDownLatch(1), answer))
  def release(id: String): Unit =
    Option(held.remove(id)).foreach(_._1.countDown())
    Option(waiting.remove(id)).foreach(_.countDown())

  def received(route: String): Vector[Received] = received.filter(r => FakeMerchant.routeOf(r).contains(route))
  def creates: Vector[JsonNode]                 = received(Create).flatMap(_.json)

  def payment(paymentId: String): Option[Payment] = payments.get(paymentId)

  protected def route(r: Received): Answer =
    if recorded.isDefinedAt(r) then recorded(r)
    else
      routeOf(r) match
        case Some(Create)  => create(r)
        case Some(Submit)  => submit(r)
        case Some(Abandon) => abandon(r)
        case Some(Read)    => read(r)
        case Some(Keys)    => Answer.ok(Json.obj("keys" -> Json.arr(keys.map((d, k, s) => Json.obj("digest" -> d, "keyId" -> k, "subBrandId" -> s))*)))
        case _             => Answer(404, """{"error":"not_found"}""")

  /** Waits as told: a hold until released (then its answer, if it names one), else the delay. */
  private def waited(id: String): Option[Answer] =
    Option(held.remove(id)) match
      case Some((latch, answer)) =>
        waiting.put(id, latch)
        latch.await(60, TimeUnit.SECONDS): Unit
        Option.when(answer.status != 0)(answer)
      case None =>
        if delay > 0 then Thread.sleep(delay)
        None

  private def create(r: Received): Answer =
    val body = r.json.getOrElse(Json.obj())
    val tx   = body.text("transactionId").getOrElse("")
    waited(tx).getOrElse {
      refuseCreate match
        case Some((status, code)) => Answer.json(status, Json.obj("error" -> code))
        case None =>
          val token   = UUID.randomUUID().toString
          val offered = terminals
          sessions.put(token, Session(token, body.text("subBrandId").getOrElse(""), body.long("amount").getOrElse(0), body.text("currency").getOrElse(""), offered))
          Answer.ok(Json.obj("session_token" -> token, "transactionId" -> tx, "redirectUrl" -> body.text("redirectUrl").getOrElse(""),
            "available_terminals" -> Json.arr(offered.map(t => Json.obj("terminalEntityId" -> t, "name" -> t, "scheme" -> "VISA",
              "gateway" -> "DREAMZPAY", "supportedCurrencies" -> Json.arr("EUR")))*),
            "resolvedFrom" -> Json.obj("level" -> "subBrand", "entityId" -> body.text("subBrandId").getOrElse(""), "name" -> "Sub-brand")))
    }

  private def submit(r: Received): Answer =
    val token = r.path.stripPrefix("/v1/cashier/sessions/").stripSuffix("/submit")
    waited(token).getOrElse {
      submits.get(token).getOrElse {
        sessions.get(token) match
          case None => Answer(404, """{"error":"SESSION_NOT_FOUND"}""")
          case Some(s) =>
            val chosen = r.json.flatMap(_.text("selectedTerminalEntityId")).getOrElse("")
            if !s.offered.contains(chosen) then Answer.json(400, Json.obj("error" -> "terminal_not_found", "message" -> s"terminal $chosen not found"))
            else
              refuseSubmit.getOrElse {
                val id = UUID.randomUUID().toString
                payments.put(id, Payment(id, s, chosen))
                val a = Answer.ok(Json.obj("paymentId" -> id, "status" -> "AWAITING_REDIRECT"))
                submits.put(token, a)
                a
              }
      }
    }

  private def abandon(r: Received): Answer =
    val token = r.path.stripPrefix("/v1/cashier/sessions/").stripSuffix("/abandon")
    sessions.get(token) match
      case None => Answer(404, """{"error":"SESSION_NOT_FOUND"}""")
      case Some(s) =>
        payments.values.find(_.session.token == token) match
          case Some(p) =>
            payments.put(p.id, p.copy(status = "FAILED"))
            Answer.ok(readOf(payments(p.id)))
          case None => Answer.ok(Json.obj("status" -> "FAILED", "currency" -> s.currency, "amountCents" -> s.amount))

  private def read(r: Received): Answer =
    payments.get(r.path.stripPrefix("/v1/cashier/payments/")).fold(Answer(404, """{"error":"PAYMENT_NOT_FOUND"}"""))(p => Answer.ok(readOf(p)))

  private def readOf(p: Payment): JsonNode =
    Json.obj("merchantId" -> p.session.subBrand, "customerRef" -> "goldvibe:p1", "status" -> p.status,
      "amount" -> java.math.BigDecimal.valueOf(p.session.amount, 2).toPlainString, "amountCents" -> p.session.amount, "currency" -> p.session.currency,
      "psp" -> "DREAMZPAY", "scheme" -> "VISA", "terminalId" -> p.terminal, "pspTransactionId" -> s"psp-${p.id}",
      "redirectUrl" -> s"https://gateway.example/pay/${p.id}")

object FakeMerchant:
  val Create  = "create"
  val Submit  = "submit"
  val Abandon = "abandon"
  val Read    = "read"
  val Keys    = "keys"

  final case class Session(token: String, subBrand: String, amount: Long, currency: String, offered: Vector[String])
  final case class Payment(id: String, session: Session, terminal: String, status: String = "AWAITING_REDIRECT")

  def routeOf(r: Received): Option[String] = (r.method, r.path) match
    case ("POST", "/v1/cashier/sessions")                         => Some(Create)
    case ("POST", p) if p.startsWith("/v1/cashier/sessions/") && p.endsWith("/submit")  => Some(Submit)
    case ("POST", p) if p.startsWith("/v1/cashier/sessions/") && p.endsWith("/abandon") => Some(Abandon)
    case ("GET", p) if p.startsWith("/v1/cashier/payments/")      => Some(Read)
    case ("GET", "/v1/cashier/keys")                              => Some(Keys)
    case _                                                        => None
