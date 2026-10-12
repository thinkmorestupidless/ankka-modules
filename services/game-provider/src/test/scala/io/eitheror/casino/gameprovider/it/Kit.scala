package io.eitheror.casino.gameprovider.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.{EntityId, Metadata}
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.eitheror.casino.contract.facts.{CasinoPlayers, CasinoPlayersFact, CasinoRounds, RoundSettled}
import io.eitheror.casino.gameprovider.{GameProviderService, Json, TestKeys, api, players}
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.api.Settings
import io.eitheror.casino.gameprovider.catalogue.{CatalogueReads, RefreshRuntime}
import io.eitheror.casino.gameprovider.config.KeyDocuments
import io.eitheror.casino.gameprovider.provider.st8.{St8Adapter, St8Signature}
import io.eitheror.casino.gameprovider.rounds.{Round, RoundState}
import io.eitheror.casino.gameprovider.tickets.{Ticket, TicketState}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.security.PrivateKey
import java.time.{Duration, Instant}
import scala.concurrent.duration.*

/** An HTTP answer from the service. */
final case class Reply(status: Int, text: String):
  def json: JsonNode = Json.parse(text).getOrElse(throw AssertionError(s"not JSON ($status): $text"))
  def word: String   = json.textOr("status", "")

/**
 * The whole service against a throwaway Postgres (ankka's integration kit), with the wallet, the
 * neighbours and ST8 as in-process fakes, an in-memory broker for `casino.players` and `casino.rounds`,
 * and a stand-in for Keycloak: a bearer token `player|<id>` is a player of this brand, `staff|<id>|<roles>`
 * an operator. The recordings' test key is the brand's provider public key once `configureSt8` has run.
 */
final class Kit(val brand: String = "goldvibe", realMoney: Boolean = false, beforeStart: Kit => Unit = _ => ()):
  val wallet     = FakeWallet()
  val neighbours = FakeNeighbours(brand)
  val st8        = St8Stub()
  /** A second provider speaking ST8's protocol under the name `acme`; `nova` is a third, never configured. */
  val acme       = St8Stub()
  val broker     = InMemoryBroker()
  @volatile var clock: Instant = Instant.parse("2026-10-09T10:00:00Z")

  val settings = Settings(brand, realMoney, Vector(St8Adapter(), St8Adapter("acme"), St8Adapter("nova")), () => Kit.authenticate(brand),
    keyDocuments = KeyDocuments.http(), now = () => clock)

  private val timers = TimerRuntime(200.millis)
  private val server = HttpServer.at("127.0.0.1", 0)(GameProviderService.endpoints(settings)*)

  GameProviderService.wire(settings, timers)
  RefreshRuntime.sleep = _ => ()
  beforeStart(this)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    GameProviderService.descriptors,
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server),
    localServices = Map("wallet" -> wallet.address, "players" -> neighbours.address, "kyc" -> neighbours.address,
      "responsible-gambling" -> neighbours.address, "rewards" -> neighbours.address)
  )
  RefreshRuntime.views = Some(testKit.service.viewClient)

  def base: String = s"http://127.0.0.1:${server.boundPort.get}"

  /** Points the process-wide refresh wiring back at this kit, after another kit in the same JVM took it. */
  def rewire(): Unit =
    GameProviderService.wire(settings, timers)
    RefreshRuntime.sleep = _ => ()
    RefreshRuntime.views = Some(testKit.service.viewClient)

  def stop(): Unit =
    testKit.stop(); wallet.stop(); neighbours.stop(); st8.stop(); acme.stop()

  /** Clears the fakes and every cache, between scenarios. The database keeps what earlier scenarios wrote; ids are fresh per scenario. */
  def reset(): Unit =
    wallet.reset(); neighbours.reset(); st8.reset(); acme.reset()
    settings.configurations(testKit.secrets).invalidate()
    CatalogueReads.shared(testKit.service.viewClient, brand, testKit.componentClient).invalidate()
    api.SpinValues.clear()

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def request(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil): Reply =
    val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(30))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(t => HttpRequest.BodyPublishers.ofString(t)))
    if body.isDefined then b.header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(r.statusCode, r.body)

  def asPlayer(player: String): Seq[(String, String)]  = Seq("Authorization" -> s"Bearer player|$player")
  def asOperator(op: String = "op-1"): Seq[(String, String)] = Seq("Authorization" -> s"Bearer staff|$op|brand_$brand")

  // ── The provider ────────────────────────────────────────────────────────────────────────────────

  /** Writes provider `st8`'s configuration through the operator route: the stub's address and the test key. */
  def configureSt8(keyDocument: Boolean = false, provider: String = "st8", stub: St8Stub = st8): Reply =
    configure(provider, stub, keyDocument)

  def configureAcme(): Reply = configure("acme", acme, keyDocument = false)

  def configure(provider: String, stub: St8Stub, keyDocument: Boolean): Reply =
    val keyField = if keyDocument then "providerKeyDocumentUrl" -> s"${stub.address}/keys" else "providerPublicKey" -> TestKeys.testPublicPem
    request("PUT", s"/v1/casino/backoffice/$brand/game-provider/providers/$provider",
      Some(Json.render(Json.obj("apiUrl" -> stub.address, "site" -> brand, "operatorPrivateKey" -> TestKeys.testPrivatePem, keyField,
        "jurisdiction" -> "MT", "lobbyUrl" -> "https://goldvibe.example/casino", "depositUrl" -> "https://goldvibe.example/cashier"))),
      asOperator())

  /** A callback as the provider sends it, signed with the test key unless told otherwise. */
  def callback(name: String, body: JsonNode | String, key: PrivateKey = TestKeys.testPrivate, path: Option[String] = None,
      signature: Option[String] = None): Reply =
    val text = body match
      case n: JsonNode => Json.render(n)
      case s: String   => s
    val sig = signature.getOrElse(St8Signature.sign(text, key))
    request("POST", path.getOrElse(s"/v1/casino/brands/$brand/st8/$name"), Some(text), Seq(St8Signature.Header -> sig).filter(_._2.nonEmpty))

  // ── Setup the way the original's test-control routes did, through the components ───────────────

  /** A session ticket for a player and game, issued and checked, as `/st8/test/register-token` did. */
  def sessionTicket(player: String, game: String = "book-of-ra", currency: String = "EUR", grant: Option[String] = None,
      token: String = java.util.UUID.randomUUID().toString): String =
    val t = testKit.componentClient.forKeyValueEntity(EntityId(token))
    t.call(Ticket.issue).invoke(io.eitheror.casino.gameprovider.tickets.IssueTicket("st8", player, game, Some("slots"), s"gs-$token", currency, grant, false, clock)): Unit
    t.call(Ticket.check).invoke(io.eitheror.casino.gameprovider.tickets.CheckTicket(None, clock)): Unit
    testKit.componentClient.forKeyValueEntity(EntityId(player)).call(players.KnownPlayer.makeKnown)
      .invoke(players.MakeKnown("st8", Some(currency), clock)): Unit
    token

  def ticket(token: String): TicketState = testKit.componentClient.forKeyValueEntity(EntityId(token)).call(Ticket.get).invoke()

  def round(roundId: String, provider: String = "st8"): RoundState =
    testKit.componentClient.forEventSourcedEntity(EntityId(Round.entityId(provider, roundId))).call(Round.get).invoke()

  /** Publishes a players fact on `casino.players`, as `players` would. */
  def publishPlayers(fact: CasinoPlayersFact): Unit =
    val bytes = CasinoPlayers.serializer.toBytes(fact)
    scala.concurrent.Await.result(broker.publish(CasinoPlayers.name, Some(fact.key), bytes,
      Metadata.empty.set(Metadata.CeSubject, fact.key).set(Metadata.CeType, CasinoPlayers.contractName)), 10.seconds): Unit

  /** What `round-facts` published on `casino.rounds`. */
  def roundsPublished: Vector[RoundSettled] =
    broker.publishedTo(CasinoRounds.name).toVector.map(d => CasinoRounds.serializer.fromBytes(d.message.payload)).collect { case r: RoundSettled => r }

  def roundsMessages = broker.publishedTo(CasinoRounds.name).toVector

  /** Polls until `f` answers, as a view's or consumer's effect must be waited for. */
  def eventually[A](what: String, within: FiniteDuration = 15.seconds)(f: => Option[A]): A =
    testKit.eventually(what, within)(f)

  def money(json: (String, Any)*): JsonNode = Json.obj(json*)

object Kit:
  /** Stands in for Keycloak: what is tested is what the route classes decide once a token is verified. */
  def authenticate(brand: String): Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")) match
      case None => AuthDecision.Unauthenticated("realm=\"casino\"")
      case Some(token) =>
        token.split('|').toList match
          case "player" :: id :: Nil =>
            AuthDecision.Allow(Principal(id, None, None, false, Set("player"), Map("iss" -> s"https://auth.example/realms/casino-$brand"), Some("player")))
          case "staff" :: id :: roles :: Nil =>
            AuthDecision.Allow(Principal(id, None, None, false, roles.split(',').toSet, Map("iss" -> "https://auth.example/realms/eitheror"), Some("staff")))
          case _ => AuthDecision.Unauthenticated("realm=\"casino\", error=\"invalid_token\"")
  }
