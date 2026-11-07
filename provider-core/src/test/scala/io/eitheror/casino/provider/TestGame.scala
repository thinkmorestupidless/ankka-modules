package io.eitheror.casino.provider

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.sdk.ServiceResponse
import com.thinkmorestupidless.ankka.testkit.ScriptedService
import io.eitheror.casino.provider.st8.{Bonus, Instance}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration as JDuration, Instant}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * A game service, as `contracts/game-contract.md` describes one, small enough to read: it registers
 * `test-slot` and `test-table` with the house provider, plays a stake, a settle or a cancel when a
 * suite tells it to, and holds free-round instances in the `BonusBook`'s own shapes (`Bonus`,
 * `Instance`), in memory, since it is no service of the runtime's. It serves the routes the provider
 * calls on a game (`/v1/game/bonus*`, `/v1/game/health`) on loopback, and no page.
 *
 * It is a test fixture and is never an image (spec 026 FR-005): nothing scripted is deployable.
 * `provider` is the provider's base address; a suite sets it once the provider has started.
 */
final class TestGame(@volatile var provider: String = ""):

  val server: ScriptedService = ScriptedService.start()
  def address: String         = server.address

  /** The service name the provider sees this game as; a suite maps it to `address`. */
  val name = "test-game"

  private val mapper = ObjectMapper()
  private val http   = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(JDuration.ofSeconds(5)).build()

  private val lock    = Object()
  private val bonuses = mutable.Map.empty[String, Bonus]
  private val ends    = mutable.Map.empty[String, Instant]
  @volatile private var healthy = true

  server.answer(handle)

  /** A provider answer: its status and body. */
  final case class Answer(status: Int, body: JsonNode):
    def text(field: String): Option[String] = Option(body.get(field)).filter(!_.isNull).map(_.asText())

  // ---- what the game calls on the provider --------------------------------------------------

  /** Registers both test games under this game's page address; answers each registration. */
  def register(): Vector[Answer] = Vector(registerGame("test-slot", "Slots"), registerGame("test-table", "Table games"))

  def registerGame(code: String, category: String, demo: Boolean = true): Answer =
    val body = obj(
      "name" -> s"Test ${code.stripPrefix("test-")}", "category" -> category,
      "bonusTypes" -> Vector("free_bets"), "features" -> Vector.empty[String], "themes" -> Vector.empty[String],
      "restrictedTerritories" -> Vector.empty[String], "prohibitedTerritories" -> Vector.empty[String],
      "demo" -> demo, "pageAddress" -> s"$address/play/$code"
    )
    call("PUT", s"/v1/games/registry/$code", Some(body))

  def deregister(code: String): Answer = call("DELETE", s"/v1/games/registry/$code", None)

  /** What the game page does first with the launch ticket it was handed. */
  def check(token: String): Answer = call("POST", s"/v1/games/sessions/$token/check", None)

  def session(token: String): Answer = call("GET", s"/v1/games/sessions/$token", None)

  /** Opens a round with a stake in minor units; `kind` is `debit` or `free_debit`. */
  def stake(token: String, roundId: String, amount: Long, kind: String = "debit"): Answer =
    call("POST", s"/v1/games/sessions/$token/rounds/$roundId/stake", Some(obj("amount" -> amount, "kind" -> kind)))

  /** Settles a round with a win in minor units, 0 for a loss. */
  def settle(token: String, roundId: String, amount: Long): Answer =
    call("POST", s"/v1/games/sessions/$token/rounds/$roundId/settle", Some(obj("amount" -> amount)))

  def cancel(token: String, roundId: String): Answer = call("POST", s"/v1/games/sessions/$token/rounds/$roundId/cancel", None)

  def callback(id: String): Answer = call("GET", s"/v1/games/callbacks/$id", None)

  // ---- what the provider calls on the game --------------------------------------------------

  /** The game answers its health route as unavailable until `up()`. */
  def down(): Unit = healthy = false
  def up(): Unit   = healthy = true

  def bonus(bonusId: String): Option[Bonus] = lock.synchronized(bonuses.get(bonusId))

  /** When a provisioned bonus may last be claimed. */
  def claimDeadline(bonusId: String): Option[Instant] = lock.synchronized(ends.get(bonusId))

  /** A free round the game plays: one instance spent, as `BonusBook.spend` spends it. */
  def spendFreeRound(bonusId: String, player: String): Option[Instance] = lock.synchronized {
    bonuses.get(bonusId).flatMap(b => b.instances.get(player).filter(i => i.status == "active" && i.remaining > 0).map { i =>
      val left  = i.remaining - 1
      val after = Instance(if left <= 0 then "used" else i.status, left)
      bonuses(bonusId) = b.copy(instances = b.instances.updated(player, after))
      after
    })
  }

  def requests: Vector[ScriptedService.Request] = server.requests

  private def handle(r: ScriptedService.Request): ServiceResponse =
    val path = r.path.takeWhile(_ != '?')
    (r.method, path.split('/').toVector.drop(1)) match
      case ("GET", Vector("v1", "game", "health")) =>
        if healthy then json(200, obj("status" -> "ready")) else json(503, obj("status" -> "unavailable"))
      case ("POST", Vector("v1", "game", "bonus")) => provision(read(r))
      case ("GET", Vector("v1", "game", "bonus", id)) =>
        lock.synchronized(bonuses.get(id)).fold(json(404, obj("error" -> "bonus_not_found")))(b => json(200, rendered(b, "active")))
      case ("POST", Vector("v1", "game", "bonus", id, "cancel")) =>
        val player = Option(read(r).get("player")).map(_.asText()).getOrElse("")
        lock.synchronized {
          bonuses.get(id).filter(_.instances.contains(player)).map { b =>
            val after = b.copy(instances = b.instances.updated(player, b.instances(player).copy(status = "canceled")))
            bonuses(id) = after
            after
          }
        }.fold(json(404, obj("error" -> "bonus_not_found")))(b => json(200, rendered(b, "cancelled")))
      case _ => json(404, obj("error" -> "not_found"))

  private def provision(n: JsonNode): ServiceResponse =
    def s(f: String) = Option(n.get(f)).filter(!_.isNull).map(_.asText()).getOrElse("")
    val code    = s("code")
    val players = Option(n.get("players")).map(_.elements().asScala.map(_.asText()).toVector).getOrElse(Vector.empty)
    val count   = Option(n.get("count")).map(_.asInt()).getOrElse(0)
    if !Set("test-slot", "test-table").contains(code) || players.isEmpty || count <= 0 then
      json(200, obj("status" -> "rejected", "instances" -> Vector.empty[String]))
    else
      // The test game's own schedule is a week; the deadline is never earlier than durationDays.
      val days  = Option(n.get("durationDays")).filter(_.canConvertToInt).map(_.asInt()).getOrElse(0).max(7)
      val until = Instant.now().plus(JDuration.ofDays(days.toLong))
      val b     = Bonus(s("bonusId"), players, count, s("value"), "free_bets", s("currency"), Vector(code), s("brand"),
        players.map(_ -> Instance("active", count)).toMap)
      lock.synchronized { bonuses(b.bonusId) = b; ends(b.bonusId) = until }
      val out = rendered(b, "active")
      out.put("claimDeadline", until.toString)
      json(200, out)

  private def rendered(b: Bonus, status: String): ObjectNode =
    val out  = mapper.createObjectNode()
    out.put("status", status)
    val list = out.putArray("instances")
    b.players.foreach { p =>
      val i = b.instances(p)
      list.addObject().put("player", p).put("remaining", i.remaining).put("status", i.status): Unit
    }
    out

  private def call(method: String, path: String, body: Option[ObjectNode]): Answer =
    require(provider.nonEmpty, "TestGame.provider is not set")
    val b = HttpRequest.newBuilder(URI.create(provider + path)).timeout(JDuration.ofSeconds(30))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(n => HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(n))))
    body.foreach(_ => b.header("Content-Type", "application/json"))
    val r    = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    val node = scala.util.Try(mapper.readTree(r.body)).toOption.filter(_ != null).getOrElse(mapper.createObjectNode())
    Answer(r.statusCode, node)

  private def read(r: ScriptedService.Request): JsonNode =
    scala.util.Try(mapper.readTree(r.body)).toOption.filter(_ != null).getOrElse(mapper.createObjectNode())

  private def json(status: Int, node: JsonNode): ServiceResponse =
    ServiceResponse(status, "application/json", mapper.writeValueAsBytes(node), Vector.empty)

  private def obj(fields: (String, Any)*): ObjectNode =
    val n = mapper.createObjectNode()
    fields.foreach {
      case (k, v: String)    => n.put(k, v)
      case (k, v: Long)      => n.put(k, v)
      case (k, v: Int)       => n.put(k, v)
      case (k, v: Boolean)   => n.put(k, v)
      case (k, v: Vector[?]) => val a = n.putArray(k); v.foreach(x => a.add(x.toString))
      case (k, v)            => n.put(k, v.toString)
    }
    n
