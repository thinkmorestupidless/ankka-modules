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

/** `payouts` as `players` erases and exports through it (spec 007 contracts/routes.md): nothing held here. */
final class FakePayouts(brand: String, bind: Int = 0) extends KnobbedServer(bind):
  private val prefix = s"/v1/casino/brands/$brand/payouts"

  def reset(): Unit = knobsReset()

  def erasures: Vector[String] = received.filter(_.path.startsWith(s"$prefix/erasure/players/")).map(_.path.split('/').last)

  protected def answer(r: Received): Answer = r.path.stripPrefix(prefix) match
    case p if p.startsWith("/erasure/players/") => Answer.ok(Json.obj("erased" -> Json.arr()))
    case p if p.startsWith("/exports/players/") =>
      Answer.ok(Json.obj("service" -> "payouts", "playerId" -> p.split('/').last, "destinations" -> Json.arr(), "requests" -> Json.arr()))
    case _                                     => Answer(404, "")

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
  @volatile var reason: Option[String] = None
  @volatile var failing: Boolean = false

  def reset(): Unit = { byKey.clear(); log.clear(); outcome = "accepted"; reason = None; failing = false; knobsReset() }

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
      Answer.ok(Json.obj((Vector[(String, Any)]("status" -> outcome) ++ reason.map("reason" -> _))*))

/**
 * `ingest`'s public cashier create (006 `contracts/routes.md`): answers `redirectUrl`, the
 * `transactionId` sent or a minted one, a `session_token` of its own and `terminals` as
 * `available_terminals`, the merchant key recorded. A repeat under a `transactionId` with the same body
 * answers the first answer and opens nothing; with another body, `409 REQUEST_ID_REUSED` naming it.
 * `refusal` answers `(status, error)` instead.
 */
final class FakeIngest(bind: Int = 0) extends KnobbedServer(bind):
  @volatile var refusal: Option[(Int, String)] = None
  val terminals: JsonNode = Json.arr(Json.obj("terminalEntityId" -> "term-card-1", "scheme" -> "card", "name" -> "Card"),
    Json.obj("terminalEntityId" -> "term-crypto-1", "scheme" -> "crypto", "name" -> "Crypto"))
  private val sessions = ConcurrentHashMap[String, (String, JsonNode)]()

  def reset(): Unit = { refusal = None; sessions.clear(); knobsReset() }

  def creates: Vector[Received] = received.filter(r => r.method == "POST" && r.path == "/v1/cashier/sessions")
  def merchantKeys: Vector[String] = creates.flatMap(_.headers.get("merchantkey"))

  /** The sessions opened, each with its answer: a repeat that was answered from the first opens none. */
  def opened: Vector[JsonNode] = sessions.values.asScala.toVector.map(_._2)

  protected def answer(r: Received): Answer =
    if r.path != "/v1/cashier/sessions" then Answer(404, "")
    else
      refusal.fold {
        val body = r.json.map(Json.render).getOrElse("")
        val id   = r.json.flatMap(_.text("transactionId")).getOrElse(s"pay-${UUID.randomUUID()}")
        val made = Json.obj("redirectUrl" -> s"https://cashier.example/$id", "transactionId" -> id,
          "session_token" -> s"st-${UUID.randomUUID()}", "available_terminals" -> terminals, "resolvedFrom" -> "sub-brand")
        val (first, answer) = sessions.computeIfAbsent(id, _ => (body, made))
        if first == body then Answer.ok(answer)
        else Answer.json(409, Json.obj("error" -> "REQUEST_ID_REUSED", "transactionId" -> id))
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

  /** Each operator's and brand's count of givings, as merchant numbers them. */
  private val givings = ConcurrentHashMap[String, java.lang.Long]()

  def reset(): Unit = { sums.clear(); failingErasures.clear(); givings.clear(); knobsReset() }

  def erasures: Vector[String] = received.filter(_.path.startsWith(s"$prefix/erasure/players/")).map(_.path.split('/').last)
  def exports: Vector[String]  = received.filter(_.path.startsWith(s"$prefix/exports/players/")).map(_.path.split('/').last)

  /** merchant's `PUT /v1/operators/{id}/brand-roles/{brand}` (spec 004 H6, amended by 032), which this fake also plays. */
  def brandRoles: Vector[Received] = received.filter(r => r.method == "PUT" && r.path.startsWith("/v1/operators/") && r.path.contains("/brand-roles/"))

  protected def answer(r: Received): Answer =
    val p = r.path.stripPrefix(prefix)
    if r.path.startsWith("/v1/operators/") && r.path.contains("/brand-roles/") && r.method == "PUT" then
      // spec 032 H6: the giving is recorded and answered pending; the realm is merchant's to set after
      val asked = Json.parse(r.body).getOrElse(Json.obj())
      givings.merge(r.path, 1L, _ + _)
      Answer(202, Json.render(Json.obj("roles" -> asked.get("roles"), "version" -> givings.get(r.path), "state" -> "pending",
        "at" -> "2026-11-20T10:00:00Z")))
    else if r.path.startsWith("/v1/operators/") && r.path.contains("/brand-roles/") then
      // H6r: the last giving, in force, and the roles it asked held
      Option(givings.get(r.path)).fold(Answer.ok(Json.obj("held" -> Json.arr())))(v =>
        Answer.ok(Json.obj("version" -> v, "state" -> "in_force", "at" -> "2026-11-20T10:00:01Z", "held" -> Json.arr())))
    else if p.startsWith("/wallets/") && p.endsWith("/deposit-sums") then
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
