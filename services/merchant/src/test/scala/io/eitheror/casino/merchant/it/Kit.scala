package io.eitheror.casino.merchant.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.merchant.{Json, MerchantService, Settings}
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.deposits.{Backoff, DepositFlow, DepositRuntime, DepositWorkflow}
import io.eitheror.casino.merchant.hierarchy.*
import io.eitheror.casino.merchant.payments.{Payment, PaymentState}
import io.eitheror.casino.merchant.routing.*
import io.eitheror.casino.merchant.security.{Hmac, Secrets}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration as JDuration, Instant}
import java.util.UUID
import scala.concurrent.duration.*

/** An HTTP answer from the service. */
final case class Reply(status: Int, text: String):
  def json: JsonNode = Json.parse(text).getOrElse(throw AssertionError(s"not JSON ($status): $text"))
  def str(field: String): String = Json.parse(text).flatMap(_.text(field)).getOrElse("")

/** A sub-brand made through the entities, ready to take deposits: its parents, its credential and its live tree. */
final case class Ready(holdingId: String, companyId: String, subBrandId: String, brand: Option[String], credential: CredentialRecord)

/**
 * The whole service against a throwaway Postgres (ankka's integration kit), with `psp-gateway` and the
 * brand's wallet as in-process fakes (research R3, R5), an in-memory broker for `payments.merchants`,
 * a short backoff, and a stand-in for Keycloak: `staff|<subject>|<roles>|<level>|<scope id>` is a
 * payments operator. `ingest` and `psp-gateway` are callers by identity (research R4).
 */
final class Kit(baseSettings: Settings = Settings(), val backoff: Backoff = Backoff(50.millis, 400.millis)):
  val gateway     = FakeGateway()
  val wallet      = FakeWallet()
  val keycloak    = FakeKeycloak()
  val invitations = RecordingInvitations()
  val broker  = InMemoryBroker()
  @volatile var clockOffset: JDuration = JDuration.ZERO
  def now(): Instant = Instant.now().plus(clockOffset)

  val settings: Settings = baseSettings.copy(keycloakUrl = keycloak.address, operatorConsoleUrl = "https://payments.example")
  private val timers = TimerRuntime(200.millis)
  private val server = HttpServer.at("127.0.0.1", 0)(MerchantService.endpoints(settings, () => Kit.authenticate, () => now())*)

  rewire()

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    MerchantService.descriptors,
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server),
    localServices = Map("psp-gateway" -> gateway.address, "wallet" -> wallet.address)
  )

  // The realm's admin client's credentials, as the installation writes them (research R10).
  Secrets(testKit.secrets).putMap(Secrets.KeycloakAdmin, Map("username" -> "admin", "password" -> "admin"))
  MerchantService.started(timers, settings)

  def components = testKit.componentClient
  def views      = testKit.service.viewClient

  /** Runs the realm's sync now, as its recurring timer would. */
  def syncOperators(): Unit = timers.timerScheduler.createSingleTimer(s"sync-now-${UUID.randomUUID()}", 0.millis,
    io.eitheror.casino.merchant.operators.OperatorSync.sync.deferred)

  /** Points the process-wide wiring at this kit's settings, clock and backoff. */
  def rewire(s: Settings = settings): Unit =
    MerchantService.wire(s, () => now())
    DepositRuntime.backoff = backoff
    io.eitheror.casino.merchant.operators.OperatorsRuntime.invitations = (_, _) => invitations

  def base: String = s"http://127.0.0.1:${server.boundPort.get}"

  def stop(): Unit =
    testKit.stop(); gateway.stop(); wallet.stop(); keycloak.stop()

  def reset(): Unit =
    gateway.reset(); wallet.reset(); invitations.reset(); clockOffset = JDuration.ZERO; rewire()

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def request(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil): Reply =
    val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(30))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(t => HttpRequest.BodyPublishers.ofString(t)))
    if body.isDefined then b.header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(r.statusCode, r.body)

  private def caller(c: Caller): Seq[(String, String)] = Seq(testKit.asCaller(c))
  def asIngest: Seq[(String, String)]  = caller(Caller.Service("payments", "ingest"))
  def asGateway: Seq[(String, String)] = caller(Caller.Service("payments", "psp-gateway"))
  def asService(project: String, name: String): Seq[(String, String)] = caller(Caller.Service(project, name))
  /** A browser through the installation's ingress: what an operator's or a player's request is (research R1). */
  def asBrowser: Seq[(String, String)] = caller(Caller.Gateway)

  /** A payments operator's token: roles, and a level with its entity (decision 0050). */
  def asOperator(level: String = "platform", scopeId: String = "*", roles: String = "platform_admin", subject: String = "op-1"): Seq[(String, String)] =
    asBrowser :+ ("Authorization" -> s"Bearer staff|$subject|$roles|$level|$scopeId")

  // ── setup through the entities, as an operator would have made it ───────────────────────────────

  def subBrand(brand: Option[String] = Some("acme"), webhookSecret: Option[String] = None, credentialName: String = "main",
      provider: String = "DREAMZPAY", deploy: Boolean = true, tree: Option[Tree] = None, scheme: String = "VISA"): Ready =
    val run = UUID.randomUUID().toString.take(8)
    val (h, c, s) = (s"h-$run", s"c-$run", s"sb-$run")
    val at = now()
    val op = Actor.operator("kit")
    components.forEventSourcedEntity(EntityId(h)).call(Holding.create).invoke(CreateOrg(s"Holding $run", None, op, at)): Unit
    components.forEventSourcedEntity(EntityId(c)).call(Company.create).invoke(CreateOrg(s"Company $run", Some(h), op, at)): Unit
    val t = if brand.isDefined then SubBrandType.Casino else SubBrandType.Payments
    components.forEventSourcedEntity(EntityId(s)).call(SubBrand.create).invoke(CreateSubBrand(c, h, s"Sub-brand $run", t, brand, op, at)): Unit
    val credId  = s"cr-$run"
    val secrets = Map("secureKey" -> s"sk-$run") ++ webhookSecret.map(Secrets.WebhookSecret -> _)
    Secrets(testKit.secrets).putMap(Secrets.credential(credId), secrets)
    val record = components.forEventSourcedEntity(EntityId(s)).call(SubBrand.registerCredential)
      .invoke(RegisterCredential(credId, provider, credentialName, "sandbox", Some(scheme), secrets.keys.toVector, op, at)).record
    if deploy then deployTree(Level.SubBrand, s, tree.getOrElse(Kit.catchAll(credentialName)))
    Ready(h, c, s, brand, record)

  def deployTree(level: Level, id: String, tree: Tree): Unit =
    val e = components.forEventSourcedEntity(RoutingTree.id(level, id))
    e.call(RoutingTree.saveDraft).invoke(SaveDraft(tree, Actor.operator("kit"), now())): Unit
    e.call(RoutingTree.deploy).invoke(TreeChange(Actor.operator("kit"), now())): Unit

  // ── the cashier, as ingest and the gateway call it ──────────────────────────────────────────────

  def session(r: Ready, amount: Long = 5000, currency: String = "EUR", customerId: Option[String] = None, extra: Seq[(String, Any)] = Nil): Reply =
    val customer = customerId.getOrElse(r.brand.fold(s"p-${UUID.randomUUID().toString.take(6)}")(b => s"$b:p-${UUID.randomUUID().toString.take(6)}"))
    request("POST", "/v1/cashier/sessions", Some(Json.render(Json.obj((Seq("subBrandId" -> r.subBrandId, "transactionId" -> s"tx-${UUID.randomUUID()}",
      "amount" -> amount, "currency" -> currency, "country" -> "GB", "customerId" -> customer, "redirectUrl" -> "https://acme.example/return",
      "customerEmail" -> "player@example.com") ++ extra)*))), asIngest)

  def submit(token: String, terminal: String = "main", extra: Seq[(String, Any)] = Nil): Reply =
    request("POST", s"/v1/cashier/sessions/$token/submit", Some(Json.render(Json.obj((("selectedTerminalEntityId" -> terminal) +: extra)*))), asIngest)

  /** A session submitted to the fake gateway: the payment id. */
  def payment(r: Ready, amount: Long = 5000, currency: String = "EUR", customerId: Option[String] = None): String =
    val s = session(r, amount, currency, customerId)
    assertOk(s)
    val p = submit(s.str("session_token"))
    assertOk(p)
    p.str("paymentId")

  def capture(paymentId: String, body: String = """{"outcome":"success"}""", headers: Seq[(String, String)] = Nil): Reply =
    request("POST", s"/v1/payments/$paymentId/capture", Some(body), asGateway ++ headers)

  def signed(body: String, secret: String, age: Long = 0): Seq[(String, String)] =
    Seq(Hmac.SignatureHeader -> Hmac.sign(secret, body), Hmac.TimestampHeader -> (now().getEpochSecond - age).toString)

  def fail(paymentId: String, body: (String, Any)*): Reply =
    request("POST", s"/v1/payments/$paymentId/fail", Some(Json.render(Json.obj(body*))), asGateway)

  def read(paymentId: String): Reply = request("GET", s"/v1/cashier/payments/$paymentId", None, asIngest)

  def paymentState(paymentId: String): PaymentState = components.forEventSourcedEntity(EntityId(paymentId)).call(Payment.get).invoke()
  def flow(paymentId: String): DepositFlow = components.forWorkflow(EntityId(paymentId)).call(DepositWorkflow.status).invoke()

  def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = testKit.eventually(what, within)(f)

  private def assertOk(r: Reply): Unit = if r.status != 200 then throw AssertionError(s"${r.status}: ${r.text}")

object Kit:
  /** A tree whose root names one terminal. */
  def catchAll(terminal: String): Tree = Tree("root", Map("root" -> Node("root", None, Some(Vector(terminal)))))

  /** Stands in for Keycloak: what is tested is what the payments operator ACL decides once a token is verified. */
  val authenticate: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")) match
      case None => AuthDecision.Unauthenticated("realm=\"eitheror\"")
      case Some(token) =>
        token.split('|').toList match
          case "staff" :: subject :: roles :: level :: scope :: Nil =>
            val claims = Map("iss" -> "https://auth.example/realms/eitheror", Routes.ScopeLevelClaim -> level) ++
              Option.when(scope.nonEmpty && scope != "*")(Routes.ScopeIdClaim -> scope)
            AuthDecision.Allow(Principal(subject, None, None, false, roles.split(',').filter(_.nonEmpty).toSet, claims, Some(Routes.StaffIssuer)))
          case "player" :: subject :: Nil =>
            AuthDecision.Allow(Principal(subject, None, None, false, Set("player"), Map("iss" -> "https://auth.example/realms/casino-acme"), Some(Routes.PlayerIssuer)))
          case _ => AuthDecision.Unauthenticated("realm=\"eitheror\", error=\"invalid_token\"")
  }

/** One kit for every suite in the test JVM: one Postgres, one service, the fakes. */
object SharedKit:
  @volatile private var started = false

  lazy val kit: Kit =
    val k = Kit()
    sys.addShutdownHook(k.stop())
    started = true
    k

  def restore(): Unit = if started then kit.rewire()
