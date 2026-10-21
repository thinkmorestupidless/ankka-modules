package io.eitheror.casino.pspgateway.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.sessions.Provider
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

/** Pins testcontainers' `SELF` type, which Scala otherwise infers as `Nothing`. */
private final class StubContainer(image: ImageFromDockerfile) extends GenericContainer[StubContainer](image)

/**
 * `psp-stub` (tests/stubs/psp-stub, T006) as a testcontainer: the nine providers on their ports, each
 * sending its webhooks to the kit's address through `host.testcontainers.internal`. The image is built
 * from `tests/` once per JVM, and the container is started once per gateway port.
 */
final class PspStub private (container: StubContainer):
  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def url(provider: Provider): String =
    s"http://${container.getHost}:${container.getMappedPort(PspStub.Ports(provider))}"

  /** Every base URL setting the gateway reads, at the container's ports (psp-stub's README). */
  def baseUrls: Map[String, String] =
    def u(p: Provider) = url(p)
    Map(
      "DREAMZPAY_BASE_URL"        -> u(Provider.DreamzPay),
      "UNITYFINANCE_PAY_URL"      -> u(Provider.UnityFinance),
      "UNITYFINANCE_API_URL"      -> s"${u(Provider.UnityFinance)}/v1",
      "T365_BASE_URL"             -> u(Provider.T365),
      "COZENI_BASE_URL"           -> u(Provider.Cozeni),
      "EUPAYMENTZ_BASE_URL"       -> u(Provider.EuPaymentz),
      "GIGADAT_BASE_URL"          -> u(Provider.Gigadat),
      "BOUNCEMONEY_BASE_URL"      -> u(Provider.BounceMoney),
      "MORPHOSIS_BASE_URL"        -> u(Provider.Morphosis),
      "DIGITARIUM_SANDBOX_URL"    -> u(Provider.Digitarium),
      "DIGITARIUM_PRODUCTION_URL" -> u(Provider.Digitarium)
    )

  /** One request to the provider's stub as it is: the stub's status and body. */
  def call(provider: Provider, method: String, path: String, body: Option[String], contentType: String = "application/json"): (Int, String) =
    val b = HttpRequest.newBuilder(URI.create(url(provider) + path)).timeout(java.time.Duration.ofSeconds(60))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString(_)))
    if body.isDefined then b.header("Content-Type", contentType)
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    (r.statusCode, r.body)

  /** The provider's outcome from now on (sticky), with the control's other fields; the stub's answer. */
  def control(provider: Provider, outcome: String, fields: (String, Any)*): JsonNode =
    val (status, text) = call(provider, "POST", "/__control", Some(Json.render(Json.obj((("outcome" -> outcome) +: fields)*))))
    if status != 200 then throw AssertionError(s"psp-stub ${provider.path} refused the control $outcome: $status $text")
    Json.parse(text).getOrElse(Json.obj())

  /** Sends the provider's webhook for `reference` now, once: the gateway's status and body as the stub saw them. */
  def webhook(provider: Provider, reference: String, overrides: (String, Any)*): (Int, JsonNode) =
    val (status, text) = call(provider, "POST", "/__webhook", Some(Json.render(Json.obj((("reference" -> reference) +: overrides)*))))
    val answer         = Json.parse(text).getOrElse(Json.obj("text" -> text))
    // The stub answers 200 with what the gateway answered it; its own 4xx/5xx say it sent nothing.
    if status != 200 then throw AssertionError(s"psp-stub ${provider.path} sent no webhook for $reference: $status $text")
    (Option(answer.get("status")).map(_.asInt).getOrElse(0), Option(answer.get("body")).getOrElse(Json.obj()))

  /** Every request the provider received, oldest first. */
  def received(provider: Provider): Vector[JsonNode] =
    val (_, text) = call(provider, "GET", "/__received", None)
    Json.parse(text).map {
      case a if a.isArray => a.elements.asScala.toVector
      case o              => Option(o.get("received")).filter(_.isArray).map(_.elements.asScala.toVector).getOrElse(Vector.empty)
    }.getOrElse(Vector.empty)

  /** Back to `success` on every provider, as a suite expects at its start. */
  def reset(): Unit = Provider.values.foreach(control(_, "success"))

  def stop(): Unit = container.stop()

object PspStub:
  val Ports: Map[Provider, Int] = Map(
    Provider.BounceMoney  -> 18101,
    Provider.Cozeni       -> 18102,
    Provider.DreamzPay    -> 18103,
    Provider.EuPaymentz   -> 18104,
    Provider.Gigadat      -> 18105,
    Provider.Morphosis    -> 18106,
    Provider.T365         -> 18107,
    Provider.UnityFinance -> 18108,
    Provider.Digitarium   -> 18109
  )

  /** A method and currency each provider takes, for the suites that do not care which. */
  val Usual: Map[Provider, (String, String)] = Map(
    Provider.DreamzPay    -> ("VISA", "EUR"),
    Provider.UnityFinance -> ("VISA", "EUR"),
    Provider.T365         -> ("PIX", "BRL"),
    Provider.Cozeni       -> ("USDT", "USD"),
    Provider.EuPaymentz   -> ("VISA", "EUR"),
    Provider.Gigadat      -> ("INTERAC", "CAD"),
    Provider.BounceMoney  -> ("OPEN_BANKING", "EUR"),
    Provider.Digitarium   -> ("BASIC_CARD", "EUR"),
    Provider.Morphosis    -> ("USDT", "USD")
  )

  /** An Ed25519 key in PKCS8 PEM, BounceMoney's request-signing key; the stub verifies none unless told its public half. */
  private lazy val ed25519Pem: String = freshEd25519Pem()

  /** Another Ed25519 key, as a rotation brings. */
  def freshEd25519Pem(): String =
    val key = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate
    "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(key.getEncoded) +
      "\n-----END PRIVATE KEY-----\n"

  /**
   * The credentials the stub's defaults accept (each provider's README): its signing secrets are the
   * original's test values, so a webhook the stub signs verifies under these.
   */
  def credentials: Map[Provider, Map[String, String]] = Map(
    Provider.DreamzPay    -> Map("memberId" -> "m-1", "totype" -> "casino", "secureKey" -> "dreamzpay-test-key"),
    Provider.UnityFinance -> Map("signKey" -> "unityfinance-signing-secret", "checkoutId.VISA" -> "co-visa",
      "userId" -> "uf-user", "withdrawalApiKey" -> "uf-key", "accountId" -> "uf-account", "purseId" -> "uf-purse"),
    Provider.T365         -> Map("apiKey" -> "t365-key", "midId" -> "mid-1", "secretKey" -> "t365-stub-secret"),
    Provider.Cozeni       -> Map("accountId" -> "cz-account", "apiKey" -> "cz-key", "merchantId" -> "cz-merchant"),
    Provider.EuPaymentz   -> Map("accountId" -> "eu-account", "password" -> "eu-password", "passphrase" -> "sk-test"),
    Provider.Gigadat      -> Map("campaignId" -> "camp-1", "accessToken" -> "gd-access", "securityToken" -> "gd-security",
      "siteUrl" -> "https://casino.example", "environment" -> "sandbox"),
    Provider.BounceMoney  -> Map("keyId" -> "bm-key", "privateKey" -> ed25519Pem, "signingSecret" -> "test-signing-secret-unit-only"),
    Provider.Digitarium   -> Map("apiKey" -> "dg-key", "signingKey" -> "test-signing-key-unit-only"),
    Provider.Morphosis    -> Map("user" -> "mp-user", "pass" -> "mp-pass", "account" -> "mp-account")
  )

  private lazy val image: ImageFromDockerfile =
    val tests = Paths.get(sys.props.getOrElse("casino.root", "../..")).resolve("tests")
    // The context is tests/'s module, as the Dockerfile expects: go.mod and the stubs, nothing else.
    ImageFromDockerfile("psp-stub-it", false)
      .withFileFromPath("go.mod", tests.resolve("go.mod"))
      .withFileFromPath("stubs", tests.resolve("stubs"))
      .withFileFromPath("Dockerfile", tests.resolve("stubs/psp-stub/Dockerfile"))

  /** A stub whose webhooks reach a gateway on this machine at `gatewayPort`. */
  def start(gatewayPort: Int): PspStub =
    Testcontainers.exposeHostPorts(gatewayPort)
    val c = StubContainer(image)
      .withExposedPorts(Ports.values.toSeq.sorted.map(Int.box)*)
      .withEnv("PSP_STUB_GATEWAY_URL", s"http://host.testcontainers.internal:$gatewayPort")
      .waitingFor(Wait.forHttp("/healthz").forPort(18101))
    c.start()
    PspStub(c)
