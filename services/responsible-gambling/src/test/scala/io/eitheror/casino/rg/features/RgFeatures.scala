package io.eitheror.casino.rg.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.rg.Json
import io.eitheror.casino.rg.Json.*
import io.eitheror.casino.rg.it.{Kit, Reply, SharedKit}
import io.eitheror.casino.rg.profile.{LimitKey, ProfileEntity, ProfileState}
import io.eitheror.casino.rg.timers.TimerNames
import io.eitheror.casino.rg.usage.{DayAt, SessionDayEntity}

import java.time.{Duration, Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A feature file of features/responsible-gambling bound against the whole service (constitution VII): the
 * player's calls through their routes, the operator's through theirs, the checks as the brand's services ask
 * them. Each scenario has a player of its own, since the database outlives scenarios. What a scenario says of
 * another service (the wallet crediting nothing, the realm refusing) is asserted on the answer this service
 * gives that service, and named as that service's spec.
 */
abstract class RgFeatures(file: String) extends GherkinSuite(s"../../features/responsible-gambling/$file") with LogCapturing:
  override val munitTimeout: scala.concurrent.duration.Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit

  protected var player   = ""
  protected var currency = "EUR"
  protected var last: Reply = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()
    assertEquals(changeSettings("minSessionMinutes" -> 60, "maxBreakDays" -> 42).status, 200)
    player = s"p-${UUID.randomUUID().toString.take(8)}"
    currency = "EUR"
    last = null

  Given("a player of brand {string} with a wallet in {string}") { (brand: String, c: String) =>
    assertEquals(brand, kit.slug)
    currency = c
  }
  Given("a player of brand {string}") { (brand: String) => assertEquals(brand, kit.slug) }

  /** A major amount in the player's currency, in minor units. */
  protected def minor(amount: String): Long = BigDecimal(amount).bigDecimal.movePointRight(2).longValueExact

  protected def json(fields: (String, Any)*): JsonNode = Json.obj(fields*)

  // ── the player's routes ─────────────────────────────────────────────────────────────────────────

  protected def putLimit(t: String, value: String, c: Option[String] = Some(currency)): Reply =
    last = kit.json("PUT", kit.playerPath(s"/$player/limits/$t"), json("value" -> value, "currency" -> c), kit.asPlayer(player))
    last

  protected def removeLimit(t: String): Reply =
    last = kit.request("DELETE", kit.playerPath(s"/$player/limits/$t?currency=$currency"), None, kit.asPlayer(player))
    last

  protected def status(): Reply = kit.request("GET", kit.playerPath(s"/$player/limits"), None, kit.asPlayer(player))

  protected def history(): Vector[JsonNode] =
    kit.request("GET", kit.playerPath(s"/$player/history"), None, kit.asPlayer(player)).json.elements.asScala.toVector

  protected def setBreak(until: Instant): Reply =
    last = kit.json("PUT", kit.playerPath(s"/$player/cooling-off"), json("until" -> until.toString), kit.asPlayer(player))
    last

  protected def excludePermanently(): Reply =
    last = kit.request("PUT", kit.playerPath(s"/$player/permanent-exclusion"), None, kit.asPlayer(player))
    last

  protected def askToReturn(): Reply =
    last = kit.request("POST", kit.playerPath(s"/$player/return"), None, kit.asPlayer(player))
    last

  // ── the operator's routes ───────────────────────────────────────────────────────────────────────

  protected def operatorLimit(t: String, value: String, c: Option[String] = Some(currency)): Reply =
    last = kit.json("PUT", kit.operatorPath(s"/$player/limits/$t"), json("value" -> value, "currency" -> c), kit.asOperator())
    last

  protected def operatorExclude(period: String): Reply =
    last = kit.json("POST", kit.operatorPath(s"/$player/self-exclude"), json("period" -> period), kit.asOperator())
    last

  protected def operatorLift(reason: String): Reply =
    last = kit.json("POST", kit.operatorPath(s"/$player/cooling-off/lift"), json("reason" -> reason), kit.asOperator())
    last

  protected def full(): JsonNode = kit.request("GET", kit.operatorPath(s"/$player"), None, kit.asOperator()).json

  protected def changeSettings(fields: (String, Any)*): Reply = kit.json("PUT", kit.operatorPath("/settings"), json(fields*), kit.asOperator())

  // ── the checks, as the brand's services ask them ────────────────────────────────────────────────

  protected def mayDeposit(amount: Long, last24h: Long = 0, last7d: Long = 0, last30d: Long = 0): Reply =
    kit.json("POST", kit.servicePath("/may-deposit"), json("playerId" -> player, "currency" -> currency, "amount" -> amount,
      "last24h" -> last24h, "last7d" -> last7d, "last30d" -> last30d), kit.asService("wallet"))

  protected def mayBet(amount: Long, losses: Option[(Long, Long, Long)] = None, as: String = "game-provider"): Reply =
    val sums = losses.toSeq.flatMap((d, w, m) => Seq("lossLast24h" -> d, "lossLast7d" -> w, "lossLast30d" -> m))
    kit.json("POST", kit.servicePath("/may-bet"), json((Seq("playerId" -> player, "currency" -> currency, "amount" -> amount) ++ sums)*),
      kit.asService(as))

  protected def mayLogIn(): Reply = kit.json("POST", kit.servicePath("/may-log-in"), json("playerId" -> player), kit.asService("players"))

  protected def mayPlay(): Reply = kit.request("GET", kit.servicePath(s"/$player/may-play"), None, kit.asService("game-provider"))

  // ── reading what happened ───────────────────────────────────────────────────────────────────────

  protected def state: ProfileState =
    kit.components.forEventSourcedEntity(EntityId(player)).call(ProfileEntity.get).invoke()

  protected def limitValue(r: Reply, t: String): Option[Long] =
    r.json.objects("limits").find(_.text("limitType").contains(t)).flatMap(_.long("value"))

  protected def pending(r: Reply, t: String): Vector[JsonNode] =
    r.json.objects("pendingChanges").filter(_.text("limitType").contains(t))

  protected def facts: Vector[CasinoPlayersFact] =
    kit.facts(CasinoPlayers).filter {
      case f: RgCaseOpened   => f.playerId == player
      case f: RgCaseClosed   => f.playerId == player
      case f: RgLimitApplied => f.playerId == player
      case _                 => false
    }

  protected def audits: Vector[AuditRecord] = kit.facts(PlatformAudit).collect { case r: AuditRecord if r.subjectId == player => r }

  protected def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = kit.eventually(what, within)(f)

  /** A game session of the player's, as game-provider publishes it on casino.sessions. */
  protected def startSession(id: String, at: Instant): Unit =
    kit.publish(CasinoSessions, player, GameSessionStarted(player, id, "game-1", "ps-1", at))

  /** Once the session-minutes consumer has counted the session on its day. */
  protected def counted(day: Instant, session: String): Unit =
    eventually(s"session $session counted") {
      val d = kit.components.forKeyValueEntity(EntityId(SessionDayEntity.id(player, day))).call(SessionDayEntity.get).invoke(DayAt(kit.now()))
      Option.when(d.sessions.contains(session))(())
    }

  protected def awaitTimer(name: String): Unit = eventually(s"the timer $name")(Option.when(kit.timerExists(name))(()))

  /** Moves the clock past `at` and fires the timer named, as the timer runtime would at that time. */
  protected def fireAt(name: String, at: Instant): Unit =
    awaitTimer(name)
    val gap = Duration.between(kit.now(), at)
    if !gap.isNegative then kit.advance(gap.plusSeconds(1))
    kit.fireTimer(name)

  protected def pendingTimer(t: String) = TimerNames.pending(player, LimitKey(t, Some(currency)))

  /** A date a scenario names, at midnight UTC; one already past is taken as five days out, so the scenario reads the same on any day. */
  protected def dateOf(text: String): Instant =
    val at = LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant
    if at.isAfter(kit.now().plus(Duration.ofHours(2))) then at else kit.now().plus(Duration.ofDays(5)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)

  protected def near(a: Instant, b: Instant, within: Duration = Duration.ofMinutes(1)): Boolean =
    Duration.between(a, b).abs.compareTo(within) <= 0
