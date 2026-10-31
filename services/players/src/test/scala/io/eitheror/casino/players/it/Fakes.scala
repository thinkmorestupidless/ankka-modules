package io.eitheror.casino.players.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** A fake with the two knobs every neighbour has: `silent` (no answer within any bound) and `delayMillis`. */
abstract class KnobbedServer(bind: Int) extends FakeServer(bind):
  @volatile var silent: Boolean   = false
  @volatile var delayMillis: Long = 0

  protected def answer(r: Received): Answer

  protected final def route(r: Received): Answer =
    if silent then Thread.sleep(60_000)
    if delayMillis > 0 then Thread.sleep(delayMillis)
    answer(r)

  protected def knobsReset(): Unit =
    silent = false; delayMillis = 0; clearReceived()

/**
 * `responsible-gambling` as `players` calls it (`clients/Rg.scala`): `may-log-in` answered per player
 * (allowed unless scripted), `may-deposit` answered from `deposit` (allowed unless set), and the GAMSTOP
 * refusals recorded.
 */
final class FakeRg(brand: String, bind: Int = 0) extends KnobbedServer(bind):
  private val prefix = s"/v1/casino/brands/$brand/rg"
  val logIn                    = ConcurrentHashMap[String, JsonNode]()
  @volatile var deposit: JsonNode = Json.obj("decision" -> "allowed")

  def coolingOff(playerId: String, until: java.time.Instant): Unit =
    logIn.put(playerId, Json.obj("decision" -> "cooling_off", "endsAt" -> until.toString)): Unit
  def exclude(playerId: String): Unit = logIn.put(playerId, Json.obj("decision" -> "excluded")): Unit

  def reset(): Unit = { logIn.clear(); deposit = Json.obj("decision" -> "allowed"); knobsReset() }

  def asked(call: String): Vector[JsonNode] = received.filter(_.path == s"$prefix/$call").flatMap(_.json)

  protected def answer(r: Received): Answer = r.path.stripPrefix(prefix) match
    case "/may-log-in" =>
      val player = r.json.flatMap(_.text("playerId")).getOrElse("")
      Answer.ok(Option(logIn.get(player)).getOrElse(Json.obj("decision" -> "allowed")))
    case "/may-deposit"      => Answer.ok(deposit)
    case "/gamstop-refusal"  => Answer(204, "")
    case p if p.startsWith("/erasure/players/") =>
      Answer.ok(Json.obj("service" -> "responsible-gambling", "playerId" -> p.split('/').last, "erased" -> Json.arr("caseNotes")))
    case p if p.startsWith("/exports/players/") =>
      Answer.ok(Json.obj("service" -> "responsible-gambling", "playerId" -> p.split('/').last, "records" -> Json.obj()))
    case _                   => Answer(404, """{"error":"unknown route"}""")

/**
 * `kyc` in the shapes of spec 009's contracts/routes.md: the deposit check allowed unless a refusal
 * `(code, message)` is scripted for the player, with the `currency` each check sent; the erasure's
 * certificate and the export's part.
 */
final class FakeKyc(brand: String, bind: Int = 0) extends KnobbedServer(bind):
  private val prefix = s"/v1/casino/brands/$brand/kyc"
  val refusals = ConcurrentHashMap[String, (String, String)]()

  def reset(): Unit = { refusals.clear(); knobsReset() }

  def checks: Vector[JsonNode] = received.filter(_.path == s"$prefix/deposit-check").flatMap(_.json)
  def erasures: Vector[String] = received.filter(_.path.startsWith(s"$prefix/erasure/players/")).map(_.path.split('/').last)

  protected def answer(r: Received): Answer = r.path.stripPrefix(prefix) match
    case "/deposit-check" =>
      val player = r.json.flatMap(_.text("playerId")).getOrElse("")
      Option(refusals.get(player)).fold(Answer.ok(Json.obj("allowed" -> true, "kycStatus" -> "verified"))) { (code, message) =>
        Answer.ok(Json.obj("allowed" -> false, "code" -> code, "message" -> message, "kycStatus" -> "unverified"))
      }
    case p if p.startsWith("/erasure/players/") =>
      Answer.ok(Json.obj("service" -> "kyc", "playerId" -> p.split('/').last, "objectsDeleted" -> 0, "recordsKept" -> 0,
        "erasedAt" -> java.time.Instant.now().toString))
    case p if p.startsWith("/exports/players/") =>
      Answer.ok(Json.obj("service" -> "kyc", "playerId" -> p.split('/').last, "documents" -> Json.arr(), "status" -> "unverified"))
    case _ => Answer(404, "")

/** One send `engagement` was asked for. */
final case class Sent(key: String, playerId: String, trigger: String, to: String, link: String)

/**
 * `engagement`'s send route: every send recorded with its link so a test follows it, a repeat of a key
 * sending nothing and answering the first outcome; `outcome` is what it answers (`accepted` by default).
 */
/** `echo` prints each accepted send's link, for a local run (`FakesMain`). */
final class FakeEngagement(brand: String, bind: Int = 0, echo: Boolean = false) extends KnobbedServer(bind):
  private val byKey             = ConcurrentHashMap[String, Sent]()
  private val log               = java.util.concurrent.ConcurrentLinkedQueue[Sent]()
  @volatile var outcome: String = "accepted"
  @volatile var failing: Boolean = false

  def reset(): Unit = { byKey.clear(); log.clear(); outcome = "accepted"; failing = false; knobsReset() }

  def sends: Vector[Sent]                     = log.asScala.toVector
  def sendsTo(address: String): Vector[Sent]  = sends.filter(_.to.equalsIgnoreCase(address))
  def lastLink(address: String): Option[String] = sendsTo(address).lastOption.map(_.link)

  protected def answer(r: Received): Answer =
    if r.path != s"/v1/casino/brands/$brand/engagement/sends" then Answer(404, "")
    else if failing then Answer(500, """{"error":"boom"}""")
    else
      val b = r.json.getOrElse(Json.obj())
      val s = Sent(b.textOr("key", ""), b.textOr("playerId", ""), b.textOr("trigger", ""), b.textOr("to", ""), b.textOr("link", ""))
      if outcome == "accepted" && byKey.putIfAbsent(s.key, s) == null then
        log.add(s): Unit
        if echo then println(s"engagement: ${s.trigger} to player ${s.playerId}: ${s.link}")
      Answer.ok(Json.obj("status" -> outcome))

/**
 * `ingest`'s public cashier create: answers `redirectUrl` and a minted `transactionId`, the merchant key
 * recorded; `refusal` answers `(status, error)` instead.
 */
final class FakeIngest(bind: Int = 0) extends KnobbedServer(bind):
  @volatile var refusal: Option[(Int, String)] = None

  def reset(): Unit = { refusal = None; knobsReset() }

  def creates: Vector[Received] = received.filter(r => r.method == "POST" && r.path == "/v1/cashier/sessions")
  def merchantKeys: Vector[String] = creates.flatMap(_.headers.get("merchantkey"))

  protected def answer(r: Received): Answer =
    if r.path != "/v1/cashier/sessions" then Answer(404, "")
    else
      refusal.fold {
        val id = r.json.flatMap(_.text("transactionId")).getOrElse(s"pay-${UUID.randomUUID()}")
        Answer.ok(Json.obj("redirectUrl" -> s"https://cashier.example/$id", "transactionId" -> id, "session_token" -> "st"))
      }((status, error) => Answer.json(status, Json.obj("error" -> error)))

/**
 * The brand's `wallet` as `players` calls it: the deposit sums per player (zeros unless set), its erasure
 * route answering a certificate and its export route answering a part.
 */
final class FakeWallet(brand: String, bind: Int = 0) extends KnobbedServer(bind):
  private val prefix = s"/v1/casino/brands/$brand"
  val sums = ConcurrentHashMap[String, (Long, Long, Long)]()

  /** Erasure subjects answered `500`: one target failing while the other answers. */
  val failingErasures: java.util.Set[String] = ConcurrentHashMap.newKeySet[String]()

  def reset(): Unit = { sums.clear(); failingErasures.clear(); knobsReset() }

  def erasures: Vector[String] = received.filter(_.path.startsWith(s"$prefix/erasure/players/")).map(_.path.split('/').last)
  def exports: Vector[String]  = received.filter(_.path.startsWith(s"$prefix/exports/players/")).map(_.path.split('/').last)

  protected def answer(r: Received): Answer =
    val p = r.path.stripPrefix(prefix)
    if p.startsWith("/wallets/") && p.endsWith("/deposit-sums") then
      val player = java.net.URLDecoder.decode(p.split('/')(2), "UTF-8")
      val (d1, d7, d30) = Option(sums.get(player)).getOrElse((0L, 0L, 0L))
      Answer.ok(Json.obj("last24h" -> d1, "last7d" -> d7, "last30d" -> d30))
    else if p.startsWith("/erasure/players/") && failingErasures.contains(p.split('/').last) then
      Answer(500, """{"error":"boom"}""")
    else if p.startsWith("/erasure/players/") then
      Answer.ok(Json.obj("subject" -> s"player/${p.split('/').last}", "confirmed" -> true, "destroyed" -> Json.arr(), "note" -> "fake"))
    else if p.startsWith("/exports/players/") then
      Answer.ok(Json.obj("service" -> "wallet", "playerId" -> p.split('/').last, "records" -> Json.arr()))
    else Answer(404, "")

/** ip-api.com: a country per address (`fail` for any other). */
final class FakeGeo(bind: Int = 0) extends KnobbedServer(bind):
  val countries = ConcurrentHashMap[String, String]()

  def reset(): Unit = { countries.clear(); knobsReset() }

  protected def answer(r: Received): Answer =
    Option(countries.get(r.path.stripPrefix("/json/"))).fold(Answer.ok(Json.obj("status" -> "fail")))(c =>
      Answer.ok(Json.obj("status" -> "success", "countryCode" -> c)))

/**
 * ankka-vendor's agent inside the brand's project (spec 021): `GET /vendor/modules` answers `modules`
 * as set, `POST /vendor/requests` records the request and answers `204`, refusing a module it does not
 * know with `400`.
 */
final class FakeAgent(bind: Int = 0) extends KnobbedServer(bind):
  @volatile var modules: JsonNode = Json.obj("modules" -> Json.arr(Json.obj("module" -> "casino", "status" -> "running",
    "version" -> "0.1.0", "follow" -> "stable")), "requests" -> Json.arr(), "missingKeys" -> Json.arr())
  @volatile var known: Set[String] = Set("casino", "predictions", "pool")

  def reset(): Unit = knobsReset()

  def requests: Vector[JsonNode] = received.filter(r => r.method == "POST" && r.path == "/vendor/requests").flatMap(_.json)

  protected def answer(r: Received): Answer = (r.method, r.path) match
    case ("GET", "/vendor/modules") => Answer.ok(modules)
    case ("POST", "/vendor/requests") =>
      if r.json.flatMap(_.text("module")).exists(known) then Answer(204, "")
      else Answer.json(400, Json.obj("error" -> "unknown module"))
    case _ => Answer(404, "")
