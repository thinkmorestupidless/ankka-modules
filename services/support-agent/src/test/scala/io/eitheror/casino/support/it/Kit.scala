package io.eitheror.casino.support.it

import com.thinkmorestupidless.ankka.agent.TestModelProvider
import com.thinkmorestupidless.ankka.agent.judgment.TestJudgmentProvider
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, ServiceIdentity, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, MovableClock}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.support.{Json, Settings, SupportRuntime, SupportService}
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.config.{AgentConfiguration, ConfigBody, ConfigChange, Put}
import io.eitheror.casino.support.conversation.{Conversation, ConversationState}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant
import scala.concurrent.duration.*

/** An HTTP answer from the service. */
final case class Reply(status: Int, text: String):
  def str(field: String): String = Json.parse(text).flatMap(_.text(field)).getOrElse("")

/**
 * The whole service against a throwaway Postgres (ankka's integration kit, research R14): the five neighbours as
 * [[FakeNeighbour]]s under `localServices`, the service named `support-agent`, an in-memory
 * broker, timers polled every 200 ms on a clock the kit moves, and a keyring in memory. Each turn is answered by
 * `model`, a scripted provider a suite replaces with [[newModel]]; compaction has its own (the two would race for one
 * queue). The judged guardrails ask `judge`. A token `player|<id>` is a player of the brand; `staff|<subject>|<roles>`
 * an operator.
 */
final class Kit(base: Settings = Kit.Base):
  val slug     = base.brand
  val players  = FakeNeighbours.players()
  val wallet   = FakeNeighbours.wallet()
  val payouts  = FakeNeighbours.payouts()
  val rewards  = FakeNeighbours.rewards()
  val rg       = FakeNeighbours.rg()
  val fakes    = Vector(players, wallet, payouts, rewards, rg)
  val broker   = InMemoryBroker()
  val clock    = MovableClock.at(Instant.now())
  val judge    = TestJudgmentProvider()
  val compactor = TestModelProvider(name = "compactor")

  @volatile var settings: Settings = base
  @volatile var model: TestModelProvider = TestModelProvider()

  /** A fresh scripted model for the next turns; the one before is dropped with whatever it still had queued. */
  def newModel(): TestModelProvider =
    model = TestModelProvider()
    model

  private def rewire(): Unit =
    SupportRuntime.wire(settings, () => clock.instant())
    SupportRuntime.model = _ => model
    SupportRuntime.judge = Some(judge)
  rewire()

  private val timers = TimerRuntime(pollInterval = 200.millis, clock = clock)
  private val server = HttpServer.at("127.0.0.1", 0)(SupportService.endpoints(() => Kit.authenticate)*)
  private val agents = SupportService.agents(settings, compactor, Some(judge))

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    SupportService.descriptors(agents),
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, agents, server),
    serviceIdentity = ServiceIdentity.local("support-agent"),
    localServices = Map(
      base.players -> players.address,
      base.wallet  -> wallet.address,
      base.payouts -> payouts.address,
      base.rewards -> rewards.address,
      base.rg      -> rg.address
    )
  )

  def components = testKit.componentClient
  def baseUrl    = s"http://127.0.0.1:${server.boundPort.get}"

  def stop(): Unit =
    testKit.stop()
    fakes.foreach(_.stop())

  /** Back to the kit's own settings, a fresh model, the judge and the fakes as they start, for the next test. */
  def reset(s: Settings = Kit.Base): Unit =
    settings = s
    fakes.foreach(_.reset())
    judge.reset()
    compactor.reset()
    newModel(): Unit
    rewire()

  /** The service stopped and started again on the same database and keyring; `whileStopped` runs between. */
  def restartService(whileStopped: => Unit = ()): Unit = testKit.restartService(whileStopped = whileStopped)

  // ── the brand's configuration ─────────────────────────────────────────────

  /** The configuration changed by `f` from what it is now, as an operator's put; the agent is on by default. */
  def configure(f: ConfigBody => ConfigBody = identity): Put =
    val now = components.forKeyValueEntity(AgentConfiguration.id(slug)).call(AgentConfiguration.get).invoke()
    AgentConfiguration.put(components, slug, ConfigChange(f(now.current.copy(enabled = true)), "kit", clock.instant()))

  def conversation(session: String): ConversationState =
    components.forEventSourcedEntity(com.thinkmorestupidless.ankka.core.EntityId(session)).call(Conversation.get).invoke()

  // ── HTTP ──────────────────────────────────────────────────────────────────

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def request(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil): Reply =
    val b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(java.time.Duration.ofSeconds(30))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(t => HttpRequest.BodyPublishers.ofString(t)))
    if body.isDefined then b.header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(r.statusCode, r.body)

  def chatPath(rest: String) = s"/v1/casino/agent/$slug$rest"

  /** A turn: `message` on `session` as the player. */
  def chat(player: String, session: String, message: String): SseReader =
    SseReader(baseUrl + chatPath("/run/chat_with_player"), Json.render(Json.obj("message" -> message, "session_id" -> session)),
      asPlayer(player))

  /** The decision on the session's awaited action, as the player. */
  def resume(player: String, session: String, action: String, feedback: Option[String] = None): SseReader =
    SseReader(baseUrl + chatPath(s"/resume/$session"), Json.render(Json.obj("action" -> action, "feedback" -> feedback.orNull)),
      asPlayer(player))

  // ── who is calling ────────────────────────────────────────────────────────

  private def gatewayCaller = testKit.asCaller(Caller.Gateway)
  def asPlayer(playerId: String): Seq[(String, String)] = Seq(gatewayCaller, "Authorization" -> s"Bearer player|$playerId")
  def asOperator(roles: String = "brand_admin", subject: String = "op-1"): Seq[(String, String)] =
    Seq(gatewayCaller, "Authorization" -> s"Bearer staff|$subject|brand_$slug,$roles")
  def asService(name: String, project: String = "local"): Seq[(String, String)] = Seq(testKit.asCaller(Caller.Service(project, name)))

  def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = testKit.eventually(what, within)(f)

object Kit:
  val Base: Settings = Settings(brand = FakeNeighbours.Brand, project = FakeNeighbours.Brand)

  /** Stands in for the token verifier: what is tested is what each audience's ACL decides once a token is verified. */
  val authenticate: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")).map(_.split('|').toList) match
      case Some("staff" :: subject :: roles :: _) =>
        AuthDecision.Allow(Principal(subject, None, None, false, roles.split(',').filter(_.nonEmpty).toSet,
          Map("iss" -> "https://auth.example/realms/eitheror"), Some(Routes.StaffIssuer)))
      case Some("player" :: id :: Nil) =>
        AuthDecision.Allow(Principal(s"realm-user-$id", None, None, false, Set.empty,
          Map("iss" -> s"https://auth.example/realms/casino-${FakeNeighbours.Brand}", Routes.PlayerIdClaim -> id), Some(Routes.PlayerIssuer)))
      case Some(_) => AuthDecision.Unauthenticated("realm=\"casino\", error=\"invalid_token\"")
      case None    => AuthDecision.Unauthenticated("realm=\"casino\"")
  }

/** One kit for every suite in the test JVM. */
object SharedKit:
  lazy val kit: Kit =
    val k = Kit()
    sys.addShutdownHook(k.stop())
    k
