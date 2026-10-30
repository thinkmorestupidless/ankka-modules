package io.eitheror.casino.attribution.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.{EntityId, Metadata}
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, GrantEntry, GrantTarget, Grants, HttpServer, LocalCallers, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, MovableClock, TimerProbe}
import io.eitheror.casino.attribution.{AttributionService, Json, Settings}
import io.eitheror.casino.attribution.carriers.WebhookHttp
import io.eitheror.casino.attribution.networks.{AddNetwork, Addresses, DocumentFetch, DocumentRefresh, Network, NetworkState}
import io.eitheror.casino.attribution.record.{AttributionRecord, Record}
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.{AffiliatesAttribution, AttributionFact, CasinoPlayers, PlayerRegistered, Topic}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Clock, Duration, Instant, ZoneId}
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.*

/** An HTTP answer from the service. */
final case class Reply(status: Int, text: String, headers: Map[String, Vector[String]] = Map.empty):
  def json: JsonNode = Json.parse(text).getOrElse(throw AssertionError(s"not JSON ($status): $text"))
  def header(name: String): Option[String] = headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v.head }

/**
 * The whole service against a throwaway Postgres (ankka's integration kit): an in-memory broker carrying
 * the six topics, timers on a clock the test moves, documents fetched from `FakeNetwork` and webhooks posted
 * to `WebhookReceiver`, in the zone `Africa/Johannesburg` beside the casino module. A stand-in for Keycloak
 * reads a bearer token `staff|<id>|<roles>` as an operator and `player|<id>` as a player; a machine is presented as ankka presents one
 * outside a cluster, by the local caller header, holding a grant on the feed this kit gives it.
 */
final class Kit(val brand: String = "goldvibe", modules: Set[String] = Set("casino"), beforeStart: Kit => Unit = _ => ()):
  val zone: ZoneId     = ZoneId.of("Africa/Johannesburg")
  val network          = FakeNetwork("programme")
  val receiver         = WebhookReceiver()
  val broker           = InMemoryBroker()
  val clock            = MovableClock.at(Instant.parse("2026-10-25T08:00:00Z"))
  val probe            = TimerProbe()
  val grants           = Grants.of()

  val settings = Settings(brand, reportingZone = zone, modules = modules, documentTimeout = Duration.ofSeconds(5),
    webhookTimeout = Duration.ofSeconds(2), webhookPlainHttp = true)

  val timers = TimerRuntime(200.millis, probe, clock)
  private val server = HttpServer.at("127.0.0.1", 0)(AttributionService.endpoints(settings, () => Kit.authenticate, () => clock.instant())*)
    .withGrants(grants)

  wire()
  beforeStart(this)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    AttributionService.descriptors(settings),
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server)
  )
  probe.bind(testKit)
  AttributionService.start(testKit.service, timers, settings, clock)

  def base: String = s"http://127.0.0.1:${server.boundPort.get}"
  def components   = testKit.componentClient
  def views        = testKit.service.viewClient

  private def wire(): Unit =
    AttributionService.wire(settings, DocumentFetch.http(settings.documentTimeout), WebhookHttp.client(settings.webhookTimeout), clock)

  /** Points the process-wide wiring back at this kit, after another kit in the same JVM took it. */
  def rewire(): Unit =
    wire()
    AttributionService.wireViews(views)
    io.eitheror.casino.attribution.carriers.Delivery.timers.set(Some(timers.timerScheduler))
    // The process-wide caches hold the other kit's keys: a service holds one brand's in a cluster.
    io.eitheror.casino.attribution.inputs.Registrar.forget()
    io.eitheror.casino.attribution.api.TrackEndpoint.forgetSalt()

  def stop(): Unit =
    testKit.stop(); network.stop(); receiver.stop()

  /** Clears the fakes between scenarios. The database keeps what earlier scenarios wrote; ids are fresh per scenario. */
  def reset(): Unit =
    network.reset(); receiver.reset(); grants.set(Vector.empty)

  /** A fresh id for a scenario. */
  def fresh(prefix: String): String = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  // ── time ────────────────────────────────────────────────────────────────────────────────────────

  def now: Instant = clock.instant()

  /** Moves the clock forward; the timers run what fell due within a poll. */
  def moveClock(to: Instant): Unit = clock.moveTo(to)

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def request(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil): Reply =
    val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(30))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(t => HttpRequest.BodyPublishers.ofString(t)))
    if body.isDefined then b.header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    import scala.jdk.CollectionConverters.*
    Reply(r.statusCode, r.body, r.headers.map.asScala.view.mapValues(_.asScala.toVector).toMap)

  /** A request from the internet, through the gateway, with no token. */
  def fromInternet: Seq[(String, String)] = Seq(LocalCallers.header(Caller.Gateway))

  /** An operator of this brand, through the gateway. */
  def asOperator(op: String = "op-1", roles: String = s"brand_$brand"): Seq[(String, String)] =
    fromInternet :+ ("Authorization" -> s"Bearer staff|$op|$roles")

  /** A player of the brand's realm, through the gateway: what an operator route refuses `wrong_issuer`. */
  def asPlayer(player: String = "p-1"): Seq[(String, String)] =
    fromInternet :+ ("Authorization" -> s"Bearer player|$player")

  /**
   * A service of this project calling, as `players` calls the erasure. Outside a cluster the server's own
   * identity is `local/local`, so a service of its project is one of project `local`.
   */
  def asService(name: String = "players", project: String = "local"): Seq[(String, String)] =
    Seq(LocalCallers.header(Caller.Service(project, name)))

  /** A machine calling through the gateway, holding a grant on the feed. */
  def asMachine(org: String, name: String): Seq[(String, String)] =
    val machine = Caller.Machine(org, name)
    val feed    = GrantTarget.Route("GET", s"/v1/casino/brands/$brand/attribution/networks/{network}/feed")
    if !grants.entries.exists(_.grantee == machine) then grants.set(grants.entries :+ GrantEntry(machine, feed))
    Seq(LocalCallers.header(machine))

  // ── the broker ──────────────────────────────────────────────────────────────────────────────────

  /** Publishes a fact on its topic, as its owner would. */
  def publish[F <: io.eitheror.casino.contract.facts.Fact](topic: Topic[F], fact: F): Unit =
    Await.result(broker.publish(topic.name, Some(fact.key), topic.serializer.toBytes(fact),
      Metadata.empty.set(Metadata.CeSubject, fact.key).set(Metadata.CeType, topic.contractName)), 10.seconds): Unit

  /** What the brand published on `affiliates.attribution` for a network, in order. */
  def factsOn(network: String): Vector[AttributionFact] = attributionFacts.filter(_.network == network)

  def attributionFacts: Vector[AttributionFact] =
    broker.publishedTo(AffiliatesAttribution.name).toVector.map(d => AffiliatesAttribution.serializer.fromBytes(d.message.payload))

  def attributionMessages = broker.publishedTo(AffiliatesAttribution.name).toVector

  // ── setup through the components, until the operator's routes are built ─────────────────────────

  /** Adds `FakeNetwork` (or another) as a network, with its documents fetched now. */
  def addNetwork(fake: FakeNetwork = network, id: Option[String] = None, legacy: Boolean = true, codes: Boolean = true,
      roundStream: Boolean = false): NetworkState =
    val nid = id.getOrElse(fake.id)
    val addresses = Addresses(fake.keyDocumentUrl, fake.revocationsUrl, Option.when(legacy)(fake.legacyUrl),
      Option.when(codes)(fake.codesUrl(brand)))
    val n = components.forKeyValueEntity(EntityId(nid))
    n.call(Network.add).invoke(AddNetwork(nid, addresses, "op-1", now)): Unit
    if roundStream then n.call(Network.setRoundStream).invoke(true): Unit
    eventually(s"network $nid in the view")(AttributionServiceViews.network(views, nid))
    refreshDocuments()
    n.call(Network.get).invoke()

  /** Fetches every expired document of every live network now, as the minute's timer would. */
  def refreshDocuments(): Vector[(String, String, Boolean)] =
    DocumentRefresh.refresher(components, clock).run()

  /** Makes a network's pseudonym key in the store, as the operator's route will. */
  def makePseudonymKey(network: String = this.network.id): Unit =
    if testKit.secrets.get(Pseudonyms.secretName(network)).isEmpty then
      testKit.secrets.put(Pseudonyms.secretName(network), Pseudonyms.newKey())
    components.forKeyValueEntity(EntityId(network)).call(Network.pseudonymKeyMade).invoke(now): Unit

  /** A `player_registered` carrying the tag, published as `players` would; answers the player id. */
  def bindPlayer(tag: Option[String], player: String = fresh("p"), clickId: Option[String] = None, bonusCode: Option[String] = None,
      country: Option[String] = Some("ZA"), at: Instant = now): String =
    publish(CasinoPlayers, PlayerRegistered(player, at, tag, clickId, bonusCode, country, Some("EUR"), None, at))
    player

  def record(player: String): Record.State =
    components.forEventSourcedEntity(EntityId(player)).call(AttributionRecord.get).invoke()

  /**
   * Polls until `f` answers, as a view's or consumer's effect must be waited for. A minute: on a loaded CI
   * runner a retry timer's attempt has taken longer than 20 s to land (PR #14's first run).
   */
  def eventually[A](what: String, within: FiniteDuration = 60.seconds)(f: => Option[A]): A =
    testKit.eventually(what, within)(f)

/**
 * One service per JVM for the suites that share it (build.sbt's grouping): the programme added as a
 * network, its documents fetched and its pseudonym key made.
 */
object SharedKit:
  lazy val kit: Kit =
    val k = Kit()
    sys.addShutdownHook(k.stop())
    k.addNetwork(): Unit
    k.makePseudonymKey()
    k

object Kit:
  /** Stands in for Keycloak: what is tested is what the route classes decide once a token is verified. */
  val authenticate: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")) match
      case None => AuthDecision.Unauthenticated("realm=\"eitheror\"")
      case Some(token) =>
        token.split('|').toList match
          case "staff" :: id :: roles :: Nil =>
            AuthDecision.Allow(Principal(id, None, None, false, roles.split(',').toSet, Map("iss" -> "https://auth.example/realms/eitheror"), Some("staff")))
          case "player" :: id :: Nil =>
            AuthDecision.Allow(Principal(id, None, None, false, Set.empty, Map("iss" -> "https://auth.example/realms/goldvibe"), Some("player")))
          case _ => AuthDecision.Unauthenticated("realm=\"eitheror\", error=\"invalid_token\"")
  }

/** The `Networks` view read by id, for the kit's waits. */
private object AttributionServiceViews:
  def network(views: com.thinkmorestupidless.ankka.runtime.ViewClient, id: String): Option[NetworkState] =
    views.forView(io.eitheror.casino.attribution.networks.Networks).get(id).filter(_.live)
