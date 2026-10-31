package io.eitheror.casino.exchange.it

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.{HttpExchange, HttpServer as JdkServer}
import io.eitheror.casino.exchange.Json
import io.eitheror.casino.exchange.Json.*

import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import scala.jdk.CollectionConverters.*

/** A neighbour scripted at its route contract: one JDK server, a handler from (method, path, body) to (status, body). */
abstract class Fake:
  /** How long every answer takes, for a perf suite's neighbours at a fixed latency. */
  @volatile var latency: java.time.Duration = java.time.Duration.ZERO

  private val server = JdkServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  server.createContext("/", (exchange: HttpExchange) => {
    val body = String(exchange.getRequestBody.readAllBytes(), UTF_8)
    if !latency.isZero then Thread.sleep(latency)
    val path = exchange.getRequestURI.getPath.split('/').map(URLDecoder.decode(_, UTF_8)).mkString("/")
    val (status, text) =
      try handle(exchange.getRequestMethod, path, Json.parse(body).getOrElse(Json.obj()))
      catch case e: Exception => (500, Json.render(Json.obj("error" -> "FAKE", "message" -> e.toString)))
    val bytes = text.getBytes(UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, if bytes.isEmpty then -1 else bytes.length.toLong)
    if bytes.nonEmpty then exchange.getResponseBody.write(bytes)
    exchange.close()
  })
  server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
  server.start()

  protected def handle(method: String, path: String, body: JsonNode): (Int, String)

  def address: String = s"http://127.0.0.1:${server.getAddress.getPort}"
  def stop(): Unit    = server.stop(0)

  protected def answer(fields: (String, Any)*): (Int, String) = (200, Json.render(Json.obj(fields*)))
  protected def refuse(status: Int, code: String): (Int, String) =
    (status, Json.render(Json.obj("error" -> code, "message" -> code)))

/**
 * `players`, `kyc` and `responsible-gambling` at their gate routes (contracts/ports.md), for one brand.
 * Every player is active and allowed unless scripted; `down` names a service that answers 503; every
 * ask is logged as (gate, player) in order.
 */
final class FakeGates(brand: String) extends Fake:
  val status  = ConcurrentHashMap[String, String]()
  val kyc     = ConcurrentHashMap[String, String]()
  val rg      = ConcurrentHashMap[String, String]()
  @volatile var down: Set[String] = Set.empty
  private val log = ConcurrentLinkedQueue[(String, String)]()

  private val Status = s"/v1/casino/brands/$brand/players/([^/]+)/status".r
  private val Play   = s"/v1/casino/brands/$brand/kyc/([^/]+)/play-check".r
  private val MayBet = s"/v1/casino/brands/$brand/rg/may-bet"

  protected def handle(method: String, path: String, body: JsonNode): (Int, String) = path match
    case Status(p) =>
      log.add("account" -> p)
      if down("players") then refuse(503, "UNAVAILABLE") else answer("status" -> status.getOrDefault(p, "active"))
    case Play(p) =>
      log.add("play gate" -> p)
      if down("kyc") then refuse(503, "UNAVAILABLE")
      else Option(kyc.get(p)).fold(answer("allowed" -> true))(s => answer("allowed" -> false, "kycStatus" -> s, "message" -> s))
    case MayBet if method == "POST" =>
      val p = body.textOr("playerId", "")
      log.add("bet gate" -> p)
      if down("responsible-gambling") then refuse(503, "UNAVAILABLE") else answer("decision" -> rg.getOrDefault(p, "allowed"))
    case _ => refuse(404, "NOT_FOUND")

  def asked(player: String): Vector[String] = log.asScala.toVector.collect { case (g, `player`) => g }

  def reset(): Unit =
    status.clear(); kyc.clear(); rg.clear(); log.clear(); down = Set.empty; latency = java.time.Duration.ZERO

/**
 * The wallet at its spec 022 routes, keeping what they move: each player's real balance, each order's
 * reservation, each market's pool, and every keyed call once. `script` answers the next calls of a
 * route (`reservations`, `release`, `settlements`, `splits`, `merges`, `payments`) with scripted
 * replies before it moves anything, for a pending or refused answer.
 */
final class FakeWallet(brand: String) extends Fake:
  val real         = ConcurrentHashMap[String, Long]()
  val reservations = ConcurrentHashMap[String, (String, Long)]() // order -> (player, amount)
  val pools        = ConcurrentHashMap[String, Long]()
  private val done = ConcurrentHashMap[String, Unit]()
  private val log  = ConcurrentLinkedQueue[(String, String, JsonNode)]() // (route, key, body)
  val script       = ConcurrentHashMap[String, List[(Int, String)]]()

  private val W     = s"/v1/casino/brands/$brand/wallets/([^/]+)/([^/]+)/(reservations|reservations/release|balance)".r
  private val Pool  = s"/v1/casino/brands/$brand/accounts/pool/([^/]+)/(splits|merges|payments)".r
  private val Settle = s"/v1/casino/brands/$brand/settlements"

  def reserved(player: String): Long = reservations.asScala.values.collect { case (`player`, a) => a }.sum
  def available(player: String): Long = real.getOrDefault(player, 0L) - reserved(player)
  def calls(route: String): Vector[(String, JsonNode)] = log.asScala.toVector.collect { case (`route`, k, b) => k -> b }

  private def scripted(route: String): Option[(Int, String)] = synchronized {
    Option(script.get(route)).flatMap(_.headOption).map { r =>
      script.computeIfPresent(route, (_, l) => if l.tail.isEmpty then null else l.tail): Unit
      r
    }
  }

  /** A keyed move made once: a repeat of its key answers as the first did. */
  private def once(route: String, key: String, body: JsonNode)(move: => Option[(Int, String)]): (Int, String) = synchronized {
    log.add((route, key, body))
    scripted(route).getOrElse {
      if done.containsKey(s"$route/$key") then answer("txnId" -> s"t-$key")
      else move.getOrElse { done.put(s"$route/$key", ()); answer("txnId" -> s"t-$key") }
    }
  }

  private def take(player: String, order: String, amount: Long): Option[(Int, String)] =
    Option(reservations.get(order)) match
      case Some((`player`, held)) if held >= amount =>
        if held == amount then reservations.remove(order) else reservations.put(order, (player, held - amount)): Unit
        None
      case Some(_) => Some(refuse(409, "RESERVATION_SHORT"))
      case None    => Some(refuse(404, "RESERVATION_NOT_FOUND"))

  private def credit(player: String, amount: Long): Unit = real.merge(player, amount, _ + _): Unit

  protected def handle(method: String, path: String, b: JsonNode): (Int, String) = path match
    case W(p, _, "balance") => answer("realBalance" -> real.getOrDefault(p, 0L), "reserved" -> reserved(p), "available" -> available(p))
    case W(p, _, "reservations") =>
      val key = b.textOr("key", ""); val amount = b.long("amount").getOrElse(0L)
      once("reservations", key, b) {
        if available(p) < amount then Some(refuse(409, "INSUFFICIENT_FUNDS"))
        else { reservations.put(key, (p, amount)); None }
      }
    case W(p, _, "reservations/release") =>
      val order = b.textOr("orderId", ""); val seq = b.long("sequence").getOrElse(0L)
      once("release", s"$order/$seq", b)(take(p, order, b.long("amount").getOrElse(0L)))
    case Settle =>
      val buyer = b.textOr("buyer", ""); val amount = b.long("amount").getOrElse(0L); val unused = b.long("unused").getOrElse(0L)
      once("settlements", b.textOr("tradeId", ""), b) {
        take(buyer, b.textOr("orderId", ""), amount + unused).orElse {
          credit(buyer, -amount); credit(b.textOr("seller", ""), amount); None
        }
      }
    case Pool(m, "splits") =>
      val p = b.textOr("player", ""); val amount = b.long("amount").getOrElse(0L); val unused = b.long("unused").getOrElse(0L)
      once("splits", b.textOr("key", ""), b) {
        take(p, b.textOr("orderId", ""), amount + unused).orElse { credit(p, -amount); pools.merge(m, amount, _ + _); None }
      }
    case Pool(m, kind @ ("merges" | "payments")) =>
      val p = b.textOr("player", ""); val amount = b.long("amount").getOrElse(0L)
      val key = if kind == "merges" then b.textOr("key", "") else s"$m/${b.textOr("positionId", "")}"
      once(kind, key, b) {
        if pools.getOrDefault(m, 0L) < amount then Some(refuse(409, "INSUFFICIENT_FUNDS"))
        else { pools.merge(m, -amount, _ + _); credit(p, amount); None }
      }
    case _ => refuse(404, "NOT_FOUND")

  def reset(): Unit = synchronized {
    real.clear(); reservations.clear(); pools.clear(); done.clear(); log.clear(); script.clear(); latency = java.time.Duration.ZERO
  }
