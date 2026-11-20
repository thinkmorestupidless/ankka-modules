package io.eitheror.casino.backoffice.it

import ch.qos.logback.classic.{Logger as LogbackLogger, LoggerContext}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Caller, HttpServer, LocalCallers}
import com.thinkmorestupidless.ankka.runtime.{ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, InMemoryKeyring}
import io.eitheror.casino.backoffice.{Answers, BackofficeStateService, Erasure, Settings, StateRuntime}
import io.eitheror.casino.backoffice.session.*
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.time.{Duration as JDuration, Instant}
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The realm client the service runs on, counting refreshes and keeping the last tokens the realm answered. */
final class Recording(inner: RealmClient) extends RealmClient:
  val refreshes                               = AtomicInteger()
  @volatile var last: Option[RealmTokens]     = None
  private def kept(r: Either[RealmRefusal, RealmTokens]) = { r.foreach(t => last = Some(t)); r }
  def exchange(code: String, verifier: String, redirectUri: String, now: Instant) = kept(inner.exchange(code, verifier, redirectUri, now))
  def refresh(refreshToken: Secret, now: Instant) = { refreshes.incrementAndGet(); kept(inner.refresh(refreshToken, now)) }
  def revoke(refreshToken: Secret)                = inner.revoke(refreshToken)

/** Keycloak with the `eitheror` realm: the brand's confidential client (PKCE S256) and one operator. Access tokens live 25 seconds. */
object Realm:
  val Client   = "backoffice-goldvibe"
  val Secret   = "backoffice-goldvibe-secret"
  val Address  = "https://backoffice-goldvibe.example"
  val Callback = s"$Address/auth/callback"

  val json: String =
    s"""{
       |  "realm": "eitheror", "enabled": true, "accessTokenLifespan": 25, "ssoSessionIdleTimeout": 1800,
       |  "roles": {"realm": [{"name": "brand_goldvibe"}, {"name": "brand_support@goldvibe"}]},
       |  "clients": [{
       |    "clientId": "$Client", "enabled": true, "publicClient": false, "secret": "$Secret",
       |    "standardFlowEnabled": true, "directAccessGrantsEnabled": false,
       |    "redirectUris": ["$Callback"], "attributes": {"pkce.code.challenge.method": "S256"}
       |  }],
       |  "users": [{
       |    "username": "ada", "enabled": true, "email": "ada@example.com", "emailVerified": true,
       |    "firstName": "Ada", "lastName": "Operator",
       |    "credentials": [{"type": "password", "value": "ada-password", "temporary": false}],
       |    "realmRoles": ["brand_goldvibe", "brand_support@goldvibe"]
       |  }]
       |}""".stripMargin

  final class Keycloak extends GenericContainer[Keycloak](DockerImageName.parse("quay.io/keycloak/keycloak:26.0.7"))

  lazy val container: Keycloak =
    val c = Keycloak()
      .withExposedPorts(8080)
      .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
      .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
      .withCopyToContainer(Transferable.of(json), "/opt/keycloak/data/import/eitheror.json")
      .withCommand("start-dev", "--import-realm")
      .waitingFor(Wait.forHttp("/realms/eitheror/.well-known/openid-configuration").forPort(8080)
        .withStartupTimeout(JDuration.ofMinutes(4)))
    c.start()
    c

  def issuer: URI = URI.create(s"http://${container.getHost}:${container.getMappedPort(8080)}/realms/eitheror")

  /** The browser's part of the code flow, by hand: the login form posted, the code read from the redirect. */
  def signIn(challenge: String, username: String = "ada", password: String = "ada-password"): String =
    // Cookies by hand: Keycloak marks its login cookies Secure, which the JDK's cookie store keeps off plain http.
    val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    def enc(s: String) = URLEncoder.encode(s, UTF_8)
    val auth = s"$issuer/protocol/openid-connect/auth?client_id=$Client&redirect_uri=${enc(Callback)}&response_type=code" +
      s"&scope=openid&state=st-1&code_challenge=$challenge&code_challenge_method=S256"
    val page    = http.send(HttpRequest.newBuilder(URI.create(auth)).GET().build(), HttpResponse.BodyHandlers.ofString())
    val cookies = page.headers.allValues("Set-Cookie").asScala.map(_.takeWhile(_ != ';')).mkString("; ")
    val action = """action="([^"]+)"""".r.findFirstMatchIn(page.body).map(_.group(1).replace("&amp;", "&"))
      .getOrElse(throw AssertionError(s"no login form (${page.statusCode})"))
    val posted = http.send(HttpRequest.newBuilder(URI.create(action)).header("Content-Type", "application/x-www-form-urlencoded")
      .header("Cookie", cookies)
      .POST(HttpRequest.BodyPublishers.ofString(s"username=${enc(username)}&password=${enc(password)}&credentialId=")).build(),
      HttpResponse.BodyHandlers.ofString())
    val location = posted.headers.firstValue("Location").orElseThrow(() => AssertionError(s"no redirect (${posted.statusCode})"))
    """[?&]code=([^&]+)""".r.findFirstMatchIn(location).map(_.group(1)).getOrElse(throw AssertionError("no code in the redirect"))

  /** A PKCE pair: the verifier and its S256 challenge. */
  def pkce(): (String, String) =
    val bytes = new Array[Byte](32)
    SecureRandom().nextBytes(bytes)
    val verifier  = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    val challenge = Base64.getUrlEncoder.withoutPadding.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(UTF_8)))
    (verifier, challenge)

final case class Reply(status: Int, text: String):
  def json: JsonNode = Answers.parse(text).getOrElse(throw AssertionError(s"not JSON ($status): $text"))
  def error: String  = json.path("error").asText("")

/** quickstart §4: the whole service over Postgres (ankka's integration kit) and a Keycloak container. */
class StateIt extends munit.FunSuite:
  override val munitTimeout: FiniteDuration = 6.minutes

  private given ExecutionContext = ExecutionContext.global

  private var kit: AnkkaTestKit       = scala.compiletime.uninitialized
  private var realm: Recording        = scala.compiletime.uninitialized
  private var server: HttpServer      = scala.compiletime.uninitialized
  private val logged                  = ListAppender[ILoggingEvent]()
  private val http                    = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  override def beforeAll(): Unit =
    val root = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext].getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    logged.start()
    root.asInstanceOf[LogbackLogger].addAppender(logged)
    val settings = Settings("goldvibe", Realm.issuer, Realm.Client, URI.create(Realm.Address), Realm.Secret)
    realm = Recording(KeycloakRealmClient(settings))
    StateRuntime.realm = realm
    StateRuntime.now = () => Instant.now()
    val timers = TimerRuntime(200.millis)
    server = HttpServer.at("127.0.0.1", 0)(BackofficeStateService.endpoints(settings)*)
    kit = AnkkaTestKit.start(BackofficeStateService.descriptors, Seq(ProjectionRuntime(), timers, server),
      configure = _.withErasureHandler(Erasure.handler), keyring = InMemoryKeyring())
    StateRuntime.views = () => kit.service.viewClient
    StateRuntime.timers = () => timers.timerScheduler

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    Try(Realm.container.stop()): Unit

  // ── HTTP ─────────────────────────────────────────────────────────────────────────────────────────

  private def base = s"http://127.0.0.1:${server.boundPort.get}"

  private def asBackoffice = LocalCallers.header(Caller.Service(kit.project, "backoffice"))

  private def call(method: String, path: String, body: Option[String] = None, caller: (String, String) = asBackoffice): Reply =
    val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(JDuration.ofSeconds(60)).header(caller._1, caller._2)
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString))
    body.foreach(_ => b.header("Content-Type", "application/json"))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(r.statusCode, r.body)

  /** Signs Ada in: the code exchanged at `POST /sessions`. */
  private def signIn(): JsonNode =
    val (verifier, challenge) = Realm.pkce()
    val code                  = Realm.signIn(challenge)
    val r = call("POST", "/sessions", Some(s"""{"code":"$code","verifier":"$verifier","redirectUri":"${Realm.Callback}"}"""))
    assertEquals(r.status, 200, r.text)
    r.json

  private def token(session: String): Reply = call("POST", s"/sessions/$session/token")

  private def summary(session: String): SessionSummary =
    kit.componentClient.forEventSourcedEntity(EntityId(session)).call(Session.get).invoke()

  private def printed: String = logged.list.asScala.map(e => s"${e.getFormattedMessage} ${Option(e.getThrowableProxy).fold("")(_.getMessage)}")
    .mkString("\n")

  // ── the checks ───────────────────────────────────────────────────────────────────────────────────

  test("a code is exchanged with PKCE: the session is opened and its token answered") {
    val opened  = signIn()
    val session = opened.path("session").asText
    assert(Session.validId(session), session)
    assert(opened.path("subject").asText.nonEmpty)
    assert(Instant.parse(opened.path("expiresAt").asText).isAfter(Instant.now().plusSeconds(1000)))
    val t = token(session)
    assertEquals(t.status, 200, t.text)
    assertEquals(t.json.path("accessToken").asText.count(_ == '.'), 2)
    assertEquals(t.json.path("roles").elements.asScala.map(_.asText).toSet.intersect(Set("brand_goldvibe", "brand_support@goldvibe")),
      Set("brand_goldvibe", "brand_support@goldvibe"))
    assertEquals(summary(session).subject, opened.path("subject").asText)
  }

  test("a code the realm refuses, a wrong verifier or a foreign redirect opens nothing") {
    val (verifier, challenge) = Realm.pkce()
    val code                  = Realm.signIn(challenge)
    val (other, _)            = Realm.pkce()
    val wrong = call("POST", "/sessions", Some(s"""{"code":"$code","verifier":"$other","redirectUri":"${Realm.Callback}"}"""))
    assertEquals((wrong.status, wrong.error), (400, "code_refused"), wrong.text)
    val foreign = call("POST", "/sessions", Some(s"""{"code":"$code","verifier":"$verifier","redirectUri":"https://evil.example/cb"}"""))
    assertEquals((foreign.status, foreign.error), (400, "redirect_uri_mismatch"))
  }

  test("two concurrent token calls near expiry make one refresh") {
    val session = signIn().path("session").asText
    // Access tokens live 25 seconds: every token is under the 30-second margin, so each call wants a refresh.
    val before = realm.refreshes.get
    val both   = Await.result(Future.sequence(Seq(Future(token(session)), Future(token(session)))), 90.seconds)
    assertEquals(realm.refreshes.get - before, 1)
    both.foreach(r => assertEquals(r.status, 200, r.text))
    assertEquals(both.map(_.json.path("accessToken").asText).distinct.size, 1)
    assertEquals(summary(session).refreshes, 1)
  }

  test("sign-out ends the session and revokes at the realm") {
    val session = signIn().path("session").asText
    val refresh = realm.last.map(_.refreshToken).get
    assertEquals(call("DELETE", s"/sessions/$session").status, 204)
    val after = token(session)
    assertEquals((after.status, after.error), (404, "session_ended"))
    assertEquals(summary(session).reason, Some(EndReason.SignedOut))
    // The realm itself no longer takes the refresh token.
    assert(KeycloakRealmClient(Settings("goldvibe", Realm.issuer, Realm.Client, URI.create(Realm.Address), Realm.Secret))
      .refresh(refresh, Instant.now()).left.exists(_.isInstanceOf[RealmRefusal.Refused]))
    assertEquals(call("DELETE", s"/sessions/$session").status, 204)
  }

  test("a refresh the realm refuses ends the session revoked; every later token is 404") {
    val session = signIn().path("session").asText
    realm.revoke(realm.last.map(_.refreshToken).get).fold(why => fail(s"$why"), identity)
    val t = token(session)
    assertEquals((t.status, t.error), (404, "session_ended"), t.text)
    assertEquals(summary(session).reason, Some(EndReason.Revoked))
    assertEquals(token(session).status, 404)
  }

  test("a preference naming a player is refused; the operator's own are kept") {
    val subject = "8c1f2a90-0000-4000-8000-000000000001"
    val named = call("PUT", s"/preferences/$subject",
      Some("""{"jobs":[{"reference":"exp-1","kind":"player_export","startedAt":"2026-11-15T10:00:00Z","player":"p-1"}]}"""))
    assertEquals((named.status, named.error), (400, "unknown_preference"), named.text)
    val unknown = call("PUT", s"/preferences/$subject", Some("""{"lastPlayer":"p-1"}"""))
    assertEquals((unknown.status, unknown.error), (400, "unknown_preference"))
    val fresh = call("GET", s"/preferences/$subject")
    assertEquals(fresh.status, 200)
    assertEquals(fresh.json.path("theme").asText, "dark")
    assert(fresh.json.path("worklistKinds").isNull)
    val put = call("PUT", s"/preferences/$subject",
      Some("""{"theme":"light","onlineNotices":true,"jobs":[{"reference":"exp-1","kind":"export","startedAt":"2026-11-15T10:00:00Z"}]}"""))
    assertEquals(put.status, 200, put.text)
    val read = call("GET", s"/preferences/$subject").json
    assertEquals(read.path("theme").asText, "light")
    assert(read.path("onlineNotices").asBoolean)
    assertEquals(read.path("jobs").get(0).path("reference").asText, "exp-1")
  }

  test("only the brand's backoffice is admitted") {
    val players = call("GET", "/preferences/abc", caller = LocalCallers.header(Caller.Service(kit.project, "players")))
    assert(players.status == 403 || players.status == 401, players.toString)
    val internet = call("POST", "/sessions/x/token", caller = LocalCallers.header(Caller.Gateway))
    assert(internet.status == 403 || internet.status == 401, internet.toString)
  }

  test("an operator's erasure ends their sessions and deletes their preferences") {
    val opened  = signIn()
    val session = opened.path("session").asText
    val subject = opened.path("subject").asText
    assertEquals(call("PUT", s"/preferences/$subject", Some("""{"theme":"light"}""")).status, 200)
    // The view's table is made by its first projected change: until then a query is refused, so it is asked again.
    kit.eventually("the session in SessionsBySubject")(
      Try(SessionsBySubject.open(kit.service.viewClient, subject)).toOption.flatMap(_.find(_.session == session)))
    val erased = call("DELETE", s"/subjects/$subject")
    assertEquals(erased.status, 200, erased.text)
    assert(erased.json.path("sessionsEnded").asInt >= 1, erased.text)
    assert(erased.json.path("preferencesDeleted").asBoolean)
    assertEquals(summary(session).reason, Some(EndReason.Erased))
    assertEquals(token(session).status, 404)
    assertEquals(call("GET", s"/preferences/$subject").json.path("theme").asText, "dark")
    val again = call("DELETE", s"/subjects/$subject").json
    assertEquals((again.path("sessionsEnded").asInt, again.path("preferencesDeleted").asBoolean), (0, false))
  }

  test("no token, name or email is in any log line, and the tokens, name and email are sealed in the database") {
    val session = signIn().path("session").asText
    val access  = token(session).json.path("accessToken").asText
    val held    = realm.last.toVector.flatMap(t => Vector(t.accessToken.reveal, t.refreshToken.reveal)) :+ access
    val text    = printed
    held.foreach(t => assert(!text.contains(t), "a token was logged"))
    assert(!text.contains("ada@example.com"))
    kit.assertNoPersonalValue("ada@example.com", "Ada Operator")
    kit.assertNoPersonalValue(held*)
  }
