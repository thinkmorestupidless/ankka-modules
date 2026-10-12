package io.eitheror.casino.simulator

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.sdk.ServiceResponse
import com.thinkmorestupidless.ankka.testkit.ScriptedService
import io.eitheror.casino.simulator.st8.{Keys, Signer}

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable

/**
 * A brand's provider routes, as spec 003 describes them, small enough to read: signatures verified
 * against the simulator's key document (current and next), a malformed body answered HTTP 400,
 * check consuming a launch ticket once, a wallet per player starting at 100.00, and every money
 * callback idempotent under its transaction id (cancel id for a cancel). Scenarios script it to be
 * slow or to fail for a number of calls.
 *
 * It is a test double of a rebuilt brand for this service's own suites; the parity of a real brand
 * under the simulator is spec 003's kit's.
 */
final class FakeBrand(keys: Keys):

  val server: ScriptedService = ScriptedService.start()
  def address: String         = server.address

  private val mapper = ObjectMapper()

  private val lock        = Object()
  private val issued      = mutable.Map.empty[String, String]   // launch ticket -> player
  private val used        = mutable.Set.empty[String]
  private val sessions    = mutable.Map.empty[String, String]   // session ticket -> player
  private val wallets     = mutable.Map.empty[String, BigDecimal]
  private val outcomes    = mutable.Map.empty[String, (String, BigDecimal, String, Map[String, String])] // key -> (status, balance, player, sent)
  private val delays      = ConcurrentHashMap[String, (Int, Long)]()  // kind -> (calls left, millis)
  private val failures    = ConcurrentHashMap[String, Int]()          // kind -> errors left
  private val withdrawn   = mutable.Set.empty[String]

  server.answer(handle)

  /** The brand issues a launch ticket for a player, as its launch does before calling the provider. */
  def issue(ticket: String, player: String): Unit = lock.synchronized {
    issued(ticket) = player
    used -= ticket
    wallets.getOrElseUpdate(player, BigDecimal("100.00")): Unit
  }

  def balanceOf(player: String): BigDecimal = lock.synchronized(wallets.getOrElse(player, BigDecimal(0)))

  /** The next `calls` calls of `kind` are answered after `millis`, having been processed first. */
  def slow(kind: String, calls: Int, millis: Long): Unit = delays.put(kind, (calls, millis)): Unit

  /** The next `calls` calls of `kind` are answered `error` without being processed. */
  def failing(kind: String, calls: Int): Unit = failures.put(kind, calls): Unit

  /** Forget a key, as a brand does once the key document stops listing it. */
  def distrust(kid: String): Unit = lock.synchronized(withdrawn += kid): Unit
  def trust(kid: String): Unit    = lock.synchronized(withdrawn -= kid): Unit

  def requests: Vector[ScriptedService.Request]              = server.requests
  def requestsTo(kind: String): Vector[ScriptedService.Request] = server.requests.filter(_.path.endsWith(s"/$kind"))
  def clear(): Unit                                          = server.clear()
  def stop(): Unit                                           = server.stop()

  /** True when the request's `x-st8-sign` verifies under a key the document lists. */
  def signedByListedKey(r: ScriptedService.Request): Boolean = verifiedKid(r).isDefined

  private def verifiedKid(r: ScriptedService.Request): Option[String] =
    r.header(Signer.Header).flatMap { sig =>
      (Vector(keys.currentKeyId) ++ keys.nextKeyId).filterNot(withdrawn.contains).find { kid =>
        keys.publicOf(kid).exists(Signer.verify(r.body, sig, _))
      }
    }

  private def json(status: Int, fields: (String, String)*): ServiceResponse =
    val node = mapper.createObjectNode()
    fields.foreach((k, v) => node.put(k, v))
    ServiceResponse(status, "application/json", mapper.writeValueAsBytes(node), Vector.empty)

  private def money(b: BigDecimal): String = b.setScale(2, BigDecimal.RoundingMode.HALF_EVEN).toString

  private def handle(r: ScriptedService.Request): ServiceResponse =
    val kind = r.path.split('/').last
    Option(failures.get(kind)).filter(_ > 0) match
      case Some(left) =>
        failures.put(kind, left - 1)
        json(200, "status" -> "error")
      case None =>
        val answer = process(kind, r)
        Option(delays.get(kind)).filter(_._1 > 0).foreach { (left, millis) =>
          delays.put(kind, (left - 1, millis))
          Thread.sleep(millis)
        }
        answer

  private def process(kind: String, r: ScriptedService.Request): ServiceResponse = lock.synchronized {
    if verifiedKid(r).isEmpty then json(200, "status" -> "auth_failed")
    else
      scala.util.Try(mapper.readTree(r.body)).toOption.collect { case o: ObjectNode => o } match
        case None => json(400, "status" -> (if kind == "player_profile" then "player_not_found" else "session_expired"))
        case Some(body) =>
          def text(f: String): Option[String] = Option(body.get(f)).filter(n => !n.isNull).map(_.asText()).filter(_.nonEmpty)
          val player = text("token").flatMap(sessions.get).orElse(text("player").filter(wallets.contains))
          kind match
            case "check" =>
              text("token").filter(t => issued.contains(t) && !used.contains(t)) match
                case None => json(200, "status" -> "session_expired")
                case Some(ticket) =>
                  used += ticket
                  val session = s"s-$ticket"
                  sessions(session) = issued(ticket)
                  json(200, "status" -> "ok", "token" -> session, "currency" -> "EUR", "player_id" -> issued(ticket), "country" -> "MT")
            case "balance" =>
              player.fold(json(200, "status" -> "player_not_found"))(p => json(200, "status" -> "ok", "balance" -> money(wallets(p)), "currency" -> "EUR"))
            case "debit" | "credit" | "buyin" | "payout" =>
              val needsToken = kind == "debit" || kind == "credit"
              val who = if needsToken then text("token").flatMap(sessions.get) else player
              who match
                case None => json(200, "status" -> (if needsToken then "session_expired" else "player_not_found"))
                case Some(p) =>
                  val key = s"$kind/${text("transaction_id").getOrElse("")}"
                  outcomes.get(key) match
                    case Some((status, balance, _, _)) => json(200, "status" -> status, "balance" -> money(balance), "currency" -> "EUR")
                    case None =>
                      val amount = text("amount").map(BigDecimal(_)).getOrElse(BigDecimal(0))
                      val sign   = if kind == "debit" || kind == "buyin" then -1 else 1
                      val after  = wallets(p) + amount * sign
                      val correction = text("provider_kind").contains("correction_debit")
                      if sign < 0 && after < 0 && !correction then json(200, "status" -> "not_enough_money", "balance" -> money(wallets(p)), "currency" -> "EUR")
                      else
                        wallets(p) = after
                        outcomes(key) = ("ok", after, p, Map("kind" -> kind, "amount" -> money(amount)))
                        json(200, "status" -> "ok", "balance" -> money(after), "currency" -> "EUR")
            case "cancel" =>
              player match
                case None => json(200, "status" -> "session_expired")
                case Some(p) =>
                  val cancelKey = s"cancel/${text("cancel_id").getOrElse("")}"
                  outcomes.get(cancelKey) match
                    case Some((status, balance, _, _)) => json(200, "status" -> status, "balance" -> money(balance), "currency" -> "EUR")
                    case None =>
                      val ref = text("transaction_id").getOrElse("")
                      outcomes.collectFirst { case (k, (_, _, owner, sent)) if k.endsWith(s"/$ref") && owner == p && !k.startsWith("cancel/") => sent } match
                        case None => json(200, "status" -> "transaction_not_found", "balance" -> money(wallets(p)), "currency" -> "EUR")
                        case Some(sent) =>
                          val amount = BigDecimal(sent("amount"))
                          val back   = if sent("kind") == "debit" || sent("kind") == "buyin" then amount else -amount
                          wallets(p) = wallets(p) + back
                          outcomes(cancelKey) = ("ok", wallets(p), p, Map.empty)
                          json(200, "status" -> "ok", "balance" -> money(wallets(p)), "currency" -> "EUR")
            case other => json(200, "status" -> "error", "detail" -> s"no route $other")
  }
