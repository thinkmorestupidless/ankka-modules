package io.eitheror.casino.players.it

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.{Json, Neighbours, PlayersRuntime, PlayersService, Secrets, Settings}
import io.eitheror.casino.players.Json.*
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration as JDuration, Instant}
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** An HTTP answer from the service, with its headers. */
final case class Reply(status: Int, text: String, headers: Map[String, String] = Map.empty):
  def json: JsonNode                       = Json.parse(text).getOrElse(throw AssertionError(s"not JSON ($status): $text"))
  def str(field: String): String           = Json.parse(text).flatMap(_.text(field)).getOrElse("")
  def header(name: String): Option[String] = headers.get(name.toLowerCase)

/**
 * The whole service against a throwaway Postgres (ankka's integration kit): the realm as `FakeRealm`, the
 * project's neighbours as the fakes under `localServices`, `ingest` and ip-api by URL, an in-memory broker
 * for every topic `players` reads or writes, timers polled every 200 ms, a clock the kit moves, the realm
 * reconciled every second and the session end bound at 2 s. The six secrets are in the service's store.
 *
 * A stand-in for the token verifier: a token `FakeRealm` signed is a player of the brand (its
 * `eitheror_twin_id` the subject); `staff|<subject>|<roles>` is an operator of the eitheror realm.
 */
final class Kit(base: Settings = Kit.Base):
  val slug       = base.brand
  val realm      = FakeRealm(slug)
  val rg         = FakeRg(slug)
  val kyc        = FakeKyc(slug)
  val payouts    = FakePayouts(slug)
  val engagement = FakeEngagement(slug)
  val ingest     = FakeIngest()
  val wallet     = FakeWallet(slug)
  val geo        = FakeGeo()
  val agent      = FakeAgent()
  val broker     = InMemoryBroker()

  val settings: Settings = base.copy(keycloakUrl = realm.address, ingestUrl = Some(ingest.address), geoUrl = geo.address)

  @volatile var clockOffset: JDuration = JDuration.ZERO
  def now(): Instant = Instant.now().plus(clockOffset)
  def advance(d: JDuration): Unit = clockOffset = clockOffset.plus(d)

  /** Everything logged while the kit runs, for `LeakSuite`. */
  val log: ListAppender[ILoggingEvent] = ListAppender[ILoggingEvent]()
  log.start()
  LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].addAppender(log)

  rewire()

  private val timers = TimerRuntime(200.millis)
  private val server = HttpServer.at("127.0.0.1", 0)(PlayersService.endpoints(() => Kit.authenticate)*)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    PlayersService.descriptors,
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server),
    localServices = Map(
      "responsible-gambling" -> rg.address,
      "kyc"                  -> kyc.address,
      "payouts"              -> payouts.address,
      "engagement"           -> engagement.address,
      "wallet"               -> wallet.address,
      "merchant"             -> wallet.address,
      Neighbours.VendorAgent -> agent.address
    )
  )

  Kit.Secrets.foreach((name, value) => testKit.secrets.put(name, value))
  testKit.secrets.put(Secrets.RealmAdmin, Json.render(Json.obj("clientId" -> realm.adminClient, "clientSecret" -> realm.adminSecret)))
  PlayersService.started(timers, testKit.service.viewClient)

  def components = testKit.componentClient
  def views      = testKit.service.viewClient
  def scheduler  = timers.timerScheduler
  def baseUrl: String = s"http://127.0.0.1:${server.boundPort.get}"

  /** Points the process-wide wiring at this kit's settings and clock; the realm client is made afresh. */
  def rewire(s: Settings = settings): Unit =
    PlayersRuntime.wire(s, () => now())
    Neighbours.reset()

  def fakes: Seq[FakeServer] = Seq(realm, rg, kyc, payouts, engagement, ingest, wallet, geo, agent)

  /** Points the process-wide runtime at this kit again (its clock, timers and views), after another kit took it. */
  def adopt(): Unit =
    rewire()
    PlayersService.started(timers, views)

  /** Stops the service and starts it again over the same database, as a crash and a redeploy would. */
  def restart(): Unit =
    testKit.restartService()
    PlayersService.started(timers, views)

  def stop(): Unit =
    testKit.stop()
    fakes.foreach(_.stop())
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].detachAppender(log): Unit

  /** The fakes as they started, the clock now, the log empty. Entities and views keep what earlier tests left. */
  def reset(): Unit =
    realm.reset(); rg.reset(); kyc.reset(); payouts.reset(); engagement.reset(); ingest.reset(); wallet.reset(); geo.reset(); agent.reset()
    clockOffset = JDuration.ZERO
    log.list.clear()
    io.eitheror.casino.players.registration.RegistrationRuntime.reset()
    rewire()

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def request(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil): Reply =
    val b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(java.time.Duration.ofSeconds(30))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(t => HttpRequest.BodyPublishers.ofString(t)))
    if body.isDefined then b.header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(r.statusCode, r.body, r.headers.map.asScala.toMap.map((k, v) => k.toLowerCase -> v.asScala.headOption.getOrElse("")))

  def json(method: String, path: String, body: JsonNode, headers: Seq[(String, String)] = Nil): Reply =
    request(method, path, Some(Json.render(body)), headers)

  // ── who is calling ──────────────────────────────────────────────────────────────────────────────

  private def as(c: Caller): (String, String) = testKit.asCaller(c)

  /** Through the installation's gateway, with no token: a visitor. */
  def asVisitor: Seq[(String, String)] = Seq(as(Caller.Gateway))

  /** A player's token, as the realm would issue it after a grant (no realm session behind it unless `session`). */
  def playerToken(playerId: String, session: String = s"rs-${UUID.randomUUID()}"): String =
    FakeRealm.jwt(Json.obj("sub" -> s"u-$playerId", "iss" -> s"https://auth.example/realms/${realm.realm}",
      "eitheror_twin_id" -> playerId, "session_state" -> session))

  def asPlayer(playerId: String): Seq[(String, String)] = asVisitor :+ ("Authorization" -> s"Bearer ${playerToken(playerId)}")
  def asToken(token: String): Seq[(String, String)]     = asVisitor :+ ("Authorization" -> s"Bearer $token")

  def staffToken(roles: String = s"brand_$slug", subject: String = "op-1"): String = s"staff|$subject|$roles"
  def asOperator(roles: String = s"brand_$slug", subject: String = "op-1"): Seq[(String, String)] =
    asVisitor :+ ("Authorization" -> s"Bearer ${staffToken(roles, subject)}")

  /** Another service of this project (or of `project`), by its certificate. */
  def serviceToken(name: String, project: String = "local"): (String, String) = as(Caller.Service(project, name))
  def asService(name: String, project: String = "local"): Seq[(String, String)] = Seq(serviceToken(name, project))

  // ── the routes, as a player drives them ─────────────────────────────────────────────────────────

  def playerPath(rest: String): String  = s"/v1/casino/players/$slug$rest"
  def operatorPath(rest: String): String = s"/v1/casino/backoffice/$slug$rest"
  def servicePath(rest: String): String  = s"/v1/casino/brands/$slug/players$rest"
  def sessionPath(rest: String): String  = s"/v1/casino/sessions/$slug$rest"
  def realmPath(rest: String): String    = s"/internal/realm/$slug$rest"

  /** A registration with quickstart's fields, `fields` replacing any; a `None` leaves one out. */
  def register(fields: (String, Any)*): Reply =
    val defaults = Vector[(String, Any)]("email" -> s"p-${UUID.randomUUID().toString.take(8)}@example.test",
      "password" -> "Passw0rd!", "currency" -> "EUR", "displayName" -> "Ada", "country" -> "GB")
    val names = fields.map(_._1).toSet
    json("POST", playerPath("/register"), Json.obj((defaults.filterNot(f => names(f._1)) ++ fields.filterNot(_._2 == None))*), asVisitor)

  /** Follows the last verification link `engagement` was asked to send to `address`. */
  def verify(address: String): Reply =
    val link  = eventually(s"a verification link to $address")(engagement.lastLink(address))
    val token = URI.create(link).getQuery.split('&').collectFirst { case q if q.startsWith("token=") => q.stripPrefix("token=") }
      .map(java.net.URLDecoder.decode(_, "UTF-8")).getOrElse(fail(s"no token in $link"))
    json("POST", playerPath("/verify-email"), Json.obj("token" -> token), asVisitor)

  def signIn(address: String, password: String = "Passw0rd!"): Reply =
    json("POST", playerPath("/login"), Json.obj("email" -> address, "password" -> password), asVisitor)

  /** A registered player whose address is verified: the address and the player id. */
  def activePlayer(fields: (String, Any)*): (String, String) =
    val address = s"p-${UUID.randomUUID().toString.take(8)}@example.test"
    val r       = register((("email" -> address) +: fields)*)
    if r.status != 201 then fail(s"registration: ${r.status} ${r.text}")
    val v = verify(address)
    if v.status != 200 then fail(s"verification: ${v.status} ${v.text}")
    (address, r.str("playerId"))

  /** The realm session a token names (`session_state`). */
  def realmSessionOf(token: String): String =
    FakeRealm.verify(token).flatMap(_.text("session_state")).getOrElse(fail("the token names no session"))

  /** The player session opened for a sign-in's token, once `SessionsView` holds it. */
  def sessionOf(token: String): String =
    val realmSession = realmSessionOf(token)
    eventually(s"the session of $realmSession")(io.eitheror.casino.players.sessions.SessionsView.ofRealm(views, realmSession).map(_.sessionId))

  // ── facts, views, tables ────────────────────────────────────────────────────────────────────────

  /** What was published on `topic`, decoded (an unreadable message is dropped). */
  def facts[F <: Fact](topic: Topic[F]): Vector[F] =
    broker.publishedTo(topic.name).toVector.flatMap(d => scala.util.Try(topic.serializer.fromBytes(d.message.payload)).toOption)

  def factTexts(topic: Topic[?]): Vector[String] = broker.publishedTo(topic.name).toVector.map(d => String(d.message.payload, "UTF-8"))

  /** A fact published on `topic` as its producer would. */
  def publish[F <: Fact](topic: Topic[F], key: String, fact: F): Unit =
    scala.concurrent.Await.result(broker.publish(topic.name, Some(key), topic.serializer.toBytes(fact),
      Metadata.empty.set(Metadata.CeSubject, key).set(Metadata.CeType, topic.contractName)), 10.seconds): Unit

  /** Every row of a view's table, as its payload text. */
  def rows(table: String): Vector[String] = Kit.query(testKit.jdbcUrl, s"SELECT payload FROM $table")

  /** Whole seconds until the timer named is due, by the database's clock; none when no such timer. */
  def timerDueIn(name: String): Option[Long] =
    Kit.query(testKit.jdbcUrl, s"SELECT CAST(EXTRACT(EPOCH FROM (due_at - now())) AS bigint) FROM ankka_timers WHERE timer_name = '$name'")
      .headOption.map(_.toLong)

  /** The timer named, due now: the clock stepped past it, as far as the timer runtime is concerned. */
  def fireTimer(name: String): Unit =
    val c = java.sql.DriverManager.getConnection(testKit.jdbcUrl, "ankka", "ankka")
    try c.createStatement().executeUpdate(s"UPDATE ankka_timers SET due_at = now() WHERE timer_name = '$name'"): Unit
    finally c.close()

  /** Every table of the service's database, each row as text: what `LeakSuite` searches. */
  def tables: Map[String, Vector[String]] =
    Kit.query(testKit.jdbcUrl, "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'")
      .map(t => t -> Kit.query(testKit.jdbcUrl, s"SELECT CAST(r AS text) FROM \"$t\" r")).toMap

  def logText: Vector[String] = log.list.asScala.toVector.map(e => s"${e.getFormattedMessage} ${Option(e.getThrowableProxy).fold("")(_.getMessage)}")

  def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = testKit.eventually(what, within)(f)

  private def fail(why: String): Nothing = throw AssertionError(why)

object Kit:
  val Base: Settings = Settings(
    brand = "goldvibe",
    realMoney = false,
    realmReconcileInterval = JDuration.ofSeconds(1),
    sessionEndBound = JDuration.ofSeconds(2),
    mayLogInBound = JDuration.ofMillis(500),
    realmAnswerBound = JDuration.ofMillis(300),
    exportPartBound = JDuration.ofSeconds(2),
    registrationBound = JDuration.ofSeconds(5),
    erasureHoldPoll = JDuration.ofMillis(300)
  )

  /** The secrets but the realm's admin client, which names `FakeRealm`'s. */
  val Secrets: Map[String, String] = Map(
    io.eitheror.casino.players.Secrets.WebhookSecret -> "kit-webhook-secret",
    io.eitheror.casino.players.Secrets.RealmSecret   -> "kit-realm-secret",
    io.eitheror.casino.players.Secrets.EmailToken    -> "kit-email-token-secret",
    io.eitheror.casino.players.Secrets.LookupKey     -> "kit-lookup-key",
    io.eitheror.casino.players.Secrets.MerchantKey   -> "mk_kit_goldvibe"
  )

  /** Stands in for the token verifier: what is tested is what each audience's ACL decides once a token is verified. */
  val authenticate: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")) match
      case None => AuthDecision.Unauthenticated("realm=\"casino\"")
      case Some(token) if token.startsWith("staff|") =>
        token.split('|').toList match
          case _ :: subject :: roles :: _ =>
            AuthDecision.Allow(Principal(subject, None, None, false, roles.split(',').filter(_.nonEmpty).toSet,
              Map("iss" -> "https://auth.example/realms/eitheror"), Some(Routes.StaffIssuer)))
          case _ => AuthDecision.Unauthenticated("realm=\"eitheror\", error=\"invalid_token\"")
      case Some(token) =>
        FakeRealm.verify(token) match
          case Some(claims) =>
            val fields = claims.fieldNames.asScala.toVector.flatMap(n => claims.text(n).map(n -> _)).toMap
            fields.get("eitheror_twin_id") match
              case Some(player) => AuthDecision.Allow(Principal(player, None, None, false, Set.empty, fields, Some(Routes.PlayerIssuer)))
              case None         => AuthDecision.Unauthenticated("realm=\"casino\", error=\"invalid_token\"")
          case None => AuthDecision.Unauthenticated("realm=\"casino\", error=\"invalid_token\"")
  }

  /** One column of a read of the kit's database, as text. */
  def query(jdbcUrl: String, sql: String): Vector[String] =
    val c = java.sql.DriverManager.getConnection(jdbcUrl, "ankka", "ankka")
    try
      val rs  = c.createStatement().executeQuery(sql)
      val out = Vector.newBuilder[String]
      while rs.next() do out += rs.getString(1)
      out.result()
    finally c.close()

/** One kit for every suite in the test JVM that neither stops a fake nor moves the clock for good. */
object SharedKit:
  @volatile private var made = false

  lazy val kit: Kit =
    val k = Kit()
    made = true
    sys.addShutdownHook(k.stop())
    k

  /** After a suite ran its own kit: the shared one takes the process-wide runtime back, if it was made. */
  def readopt(): Unit = if made then kit.adopt()
