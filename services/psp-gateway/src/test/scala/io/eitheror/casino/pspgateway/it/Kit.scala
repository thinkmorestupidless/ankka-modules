package io.eitheror.casino.pspgateway.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.pspgateway.{GatewayRuntime, Json, PspGatewayService, Settings}
import io.eitheror.casino.pspgateway.sessions.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*

/** An HTTP answer from the service. */
final case class Reply(status: Int, text: String, contentType: String = ""):
  def json: JsonNode = Json.parse(text).getOrElse(throw AssertionError(s"not JSON ($status): $text"))
  def str(field: String): String = Json.parse(text).flatMap(n => Json.text(n)(field)).getOrElse("")

/**
 * The whole service against a throwaway Postgres (ankka's integration kit), with `merchant` and the
 * brands' `payouts` as in-process fakes, and a stand-in for Keycloak: `staff|<subject>|<roles>|<level>|<scope id>`
 * is a payments operator, as merchant's kit mints it. `merchant` and `payouts` are callers by identity.
 * The provider base URLs are what the suite names: `psp-stub`'s ports when it runs, none otherwise.
 */
final class Kit(baseUrls: Map[String, String] = Map.empty, val port: Int = Kit.freePort(), val stub: Option[PspStub] = None,
    redriveInterval: JDuration = Settings("").redriveInterval):
  val merchant = FakeMerchant()
  val payouts  = FakePayouts()
  @volatile var clockOffset: JDuration = JDuration.ZERO
  def now(): Instant = Instant.now().plus(clockOffset)

  /** The kit's own address is the service's `PUBLIC_URL`, so its port is chosen before the server binds it. */
  val settings: Settings = Settings(s"http://127.0.0.1:$port", baseUrls, redriveInterval = redriveInterval, project = "local")
  GatewayRuntime.wire(settings, () => now())

  private val server = HttpServer.at("127.0.0.1", port)(PspGatewayService.endpoints(settings, () => Kit.authenticate, () => now())*)
  private val timers = TimerRuntime(200.millis)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    PspGatewayService.descriptors,
    Seq(ProjectionRuntime(), timers, server),
    localServices = Map("merchant" -> merchant.address, "payouts" -> payouts.address)
  )
  PspGatewayService.started(timers, settings, testKit.service.viewClient)

  def components = testKit.componentClient

  def base: String = settings.publicUrl

  def stop(): Unit = { testKit.stop(); merchant.stop(); payouts.stop() }

  /** The service stopped and started again on the same database: every entity rebuilt from the journal. */
  def restart(): Unit =
    testKit.restartService()
    PspGatewayService.started(timers, settings, testKit.service.viewClient)

  def reset(): Unit = { merchant.reset(); payouts.reset(); stub.foreach(_.reset()); clockOffset = JDuration.ZERO }

  def psp: PspStub = stub.getOrElse(throw AssertionError("this kit runs without psp-stub: use Kit.withStub()"))

  def advance(d: JDuration): Unit = clockOffset = clockOffset.plus(d)

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def request(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil,
      contentType: String = "application/json"): Reply =
    val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(60))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(t => HttpRequest.BodyPublishers.ofString(t)))
    if body.isDefined then b.header("Content-Type", contentType)
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(r.statusCode, r.body, r.headers.firstValue("content-type").orElse(""))

  private def caller(c: Caller): Seq[(String, String)] = Seq(testKit.asCaller(c))
  def asMerchant: Seq[(String, String)]             = caller(Caller.Service("payments", "merchant"))
  def asPayouts(brand: String): Seq[(String, String)] = caller(Caller.Service(brand, "payouts"))
  def asService(project: String, name: String): Seq[(String, String)] = caller(Caller.Service(project, name))
  /** A browser or a provider through the installation's ingress, with no identity. */
  def asBrowser: Seq[(String, String)]  = caller(Caller.Gateway)
  def asProvider: Seq[(String, String)] = asBrowser

  def asOperator(level: String = "platform", scopeId: String = "*", roles: String = "platform_admin", subject: String = "op-1"): Seq[(String, String)] =
    asBrowser :+ ("Authorization" -> s"Bearer staff|$subject|$roles|$level|$scopeId")

  /** A staff token on an identity route: what a `403` is for. */
  def asStaff: Seq[(String, String)] = asOperator()

  // ── reading the service's state ─────────────────────────────────────────────────────────────────

  def deposit(paymentId: String): DepositState =
    components.forEventSourcedEntity(EntityId(paymentId)).call(DepositSession.get).invoke()

  def payout(requestId: String): PayoutState =
    components.forEventSourcedEntity(EntityId(requestId)).call(PayoutSession.get).invoke()

  // ── the scenarios' steps ────────────────────────────────────────────────────────────────────────

  /** The credential `merchant` holds for the sub-brand: the one psp-stub accepts unless given. */
  def credential(subBrandId: String, provider: Provider, values: Map[String, String] = Map.empty): Unit =
    merchant.credential(subBrandId, provider.wire, if values.isEmpty then PspStub.credentials(provider) else values)

  def terminal(id: String, props: Map[String, String]): Unit = merchant.terminal(id, props)

  /** 004's `gateway.md` initiate body, as `merchant` sends it, with `fields` replacing any of it. */
  def initiateBody(paymentId: String, provider: Provider, fields: (String, Any)*): String =
    val (method, currency) = PspStub.Usual(provider)
    val base = Vector[(String, Any)]("paymentId" -> paymentId, "psp" -> provider.wire, "amount" -> "2500",
      "currency" -> currency, "method" -> method, "subBrandId" -> "sb-1", "customerId" -> "c-1",
      "terminalProperties" -> Json.obj(), "customerEmail" -> "player@example.com", "customerFirstName" -> "Ada",
      "customerLastName" -> "Lovelace", "customerCountry" -> "CA", "customerIp" -> "203.0.113.7", "customerPhone" -> "+15550100",
      "customerProvince" -> "ON", "customerCity" -> "Toronto", "customerAddress" -> "1 King St", "customerPostCode" -> "M5H 1A1")
    val keys = fields.map(_._1).toSet
    Json.render(Json.obj((base.filterNot(f => keys(f._1)) ++ fields)*))

  def initiate(paymentId: String, provider: Provider, fields: (String, Any)*): Reply =
    request("POST", "/v1/psp/hpp/initiate", Some(initiateBody(paymentId, provider, fields*)), asMerchant)

  def session(paymentId: String): Reply = request("GET", s"/v1/psp/sessions/$paymentId", None, asMerchant)

  /**
   * What the player's browser does with a relay page (DreamzPay, EuPaymentz): POSTs the stored form to
   * the provider. Absent for a provider that redirects outright; the provider's answer otherwise.
   */
  def relay(paymentId: String): Option[Int] =
    for
      i      <- deposit(paymentId).initiation
      action <- i.providerData.get("relayAction")
      form   <- i.providerData.get(io.eitheror.casino.pspgateway.deposits.Initiate.RelayForm)
    yield
      val r = HttpRequest.newBuilder(URI.create(action)).timeout(java.time.Duration.ofSeconds(30))
        .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build()
      http.send(r, HttpResponse.BodyHandlers.discarding()).statusCode

  // ── payouts ─────────────────────────────────────────────────────────────────────────────────────

  /** The terminal of `brand` paying out through `provider`, as `merchant`'s brand listing names it. */
  def payoutTerminal(brand: String, provider: Provider): String = s"$brand-${provider.path}-out"

  /** `merchant`'s brand listing: a withdrawal terminal per payout provider, and one deposit-only. */
  def payoutTerminals(brand: String, subBrandId: String = "sb-1"): Unit =
    val out = Kit.PayoutProviders.map { p =>
      Json.obj("id" -> payoutTerminal(brand, p), "subBrandId" -> subBrandId, "gateway" -> p.wire, "scheme" -> Kit.PayoutMethod(p)._1,
        "name" -> s"${p.wire} payouts", "status" -> "ACTIVE", "supportsDeposit" -> false, "supportsWithdrawal" -> true,
        "requiresPayoutDestination" -> (p == Provider.Cozeni || p == Provider.Morphosis),
        "properties" -> Json.objOf(if p == Provider.Cozeni || p == Provider.Morphosis then Map("supportedCurrencies" -> "USDT,BTC") else Map.empty))
    }
    val depositOnly = Json.obj("id" -> s"$brand-deposits", "subBrandId" -> subBrandId, "gateway" -> "DREAMZPAY", "scheme" -> "VISA",
      "name" -> "cards", "status" -> "ACTIVE", "supportsDeposit" -> true, "supportsWithdrawal" -> false)
    merchant.brandTerminals(brand, out :+ depositOnly)

  /** contracts/payouts.md's body for `provider`, with `fields` replacing any of it. */
  def withdrawalBody(brand: String, requestId: String, provider: Provider, fields: (String, Any)*): String =
    val (method, currency) = Kit.PayoutMethod(provider)
    val details: Vector[(String, Any)] = provider match
      case Provider.UnityFinance => Vector("iban" -> "DE89370400440532013000", "firstName" -> "Ada", "lastName" -> "Lovelace")
      case Provider.Gigadat      => Vector("email" -> "player@example.com", "mobile" -> "+15550100", "firstName" -> "Ada",
          "lastName" -> "Lovelace", "ip" -> "203.0.113.7")
      case _                     => Vector("coin" -> "USDT", "address" -> "TXyz0000000000000000000000000000")
    val base = Vector[(String, Any)]("requestId" -> requestId, "brand" -> brand, "subBrandId" -> "sb-1",
      "terminalId" -> payoutTerminal(brand, provider), "amount" -> "5000", "currency" -> currency, "method" -> method,
      "customerId" -> "player-1") ++ details
    val keys = fields.map(_._1).toSet
    Json.render(Json.obj((base.filterNot(f => keys(f._1)) ++ fields)*))

  def withdraw(brand: String, requestId: String, provider: Provider, fields: (String, Any)*): Reply =
    request("POST", "/v1/payments/withdrawals", Some(withdrawalBody(brand, requestId, provider, fields*)), asPayouts(brand))

  def withdrawal(brand: String, requestId: String): Reply =
    request("GET", s"/v1/payments/withdrawals/$requestId", None, asPayouts(brand))

  /** The reference psp-stub knows the session by (its README's `/__webhook` reference). */
  def stubReference(paymentId: String, provider: Provider): String = provider match
    case Provider.T365      => paymentId.replace("-", "")
    case Provider.Morphosis => deposit(paymentId).claim.flatMap(_.generated.get("token")).getOrElse(paymentId)
    case _                  => paymentId

  def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = testKit.eventually(what, within)(f)

object Kit:
  val PayoutProviders: Vector[Provider] = Vector(Provider.UnityFinance, Provider.Cozeni, Provider.Gigadat, Provider.Morphosis)

  /** A method and currency each payout provider pays out in. */
  val PayoutMethod: Map[Provider, (String, String)] = Map(
    Provider.UnityFinance -> ("BANK_TRANSFER", "EUR"),
    Provider.Cozeni       -> ("USDT", "USD"),
    Provider.Gigadat      -> ("ETO", "CAD"),
    Provider.Morphosis    -> ("USDT", "USD")
  )

  /** A kit whose providers are psp-stub's, its webhooks sent back to the kit. */
  def withStub(redriveInterval: JDuration = Settings("").redriveInterval): Kit =
    val port = freePort()
    val stub = PspStub.start(port)
    Kit(stub.baseUrls, port, Some(stub), redriveInterval)

  def freePort(): Int =
    val s = java.net.ServerSocket(0)
    try s.getLocalPort finally s.close()

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
          case _ => AuthDecision.Unauthenticated("realm=\"eitheror\", error=\"invalid_token\"")
  }

/** One kit for every suite in the test JVM that neither stops a fake nor moves the clock. */
object SharedKit:
  lazy val kit: Kit =
    val k = Kit.withStub()
    sys.addShutdownHook(k.stop())
    k
