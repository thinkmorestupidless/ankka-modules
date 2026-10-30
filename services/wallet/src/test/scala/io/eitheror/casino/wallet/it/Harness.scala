package io.eitheror.casino.wallet.it

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Caller, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{InMemoryPublisher, ProjectionRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import io.eitheror.casino.wallet.WalletService
import io.eitheror.casino.wallet.api.{RgAnswer, ResponsibleGambling}
import io.eitheror.casino.wallet.entity.DepositSums

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * `responsible-gambling` for the integration suites: allows by default; a test scripts a refusal or
 * silence (research R6). It records what it was asked, so a test can check the sums it was given.
 * `mayBet` is kept for the bet-workflow prototype (`it.proto`); the wallet itself asks only `mayDeposit`.
 */
final class FakeResponsibleGambling extends ResponsibleGambling:
  private val betAnswer     = AtomicReference[RgAnswer](RgAnswer.Allowed)
  private val depositAnswer = AtomicReference[RgAnswer](RgAnswer.Allowed)
  private val askedSums     = AtomicReference[Vector[DepositSums]](Vector.empty)

  def answerBets(a: RgAnswer): Unit     = betAnswer.set(a)
  def answerDeposits(a: RgAnswer): Unit = depositAnswer.set(a)
  def silent(): Unit =
    answerBets(RgAnswer.Unavailable("no answer within 500 ms"))
    answerDeposits(RgAnswer.Unavailable("no answer within 500 ms"))
  def allow(): Unit = { answerBets(RgAnswer.Allowed); answerDeposits(RgAnswer.Allowed) }
  def sumsAsked: Vector[DepositSums] = askedSums.get

  def mayBet(playerId: String, currency: String, amount: Long): RgAnswer = betAnswer.get
  def mayDeposit(playerId: String, currency: String, amount: Long, sums: DepositSums): RgAnswer =
    askedSums.updateAndGet(_ :+ sums): Unit
    depositAnswer.get

/** The whole wallet against a throwaway Postgres, its routes on a free port, its facts captured in memory. */
trait Harness extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  val brand     = "goldvibe"
  val rg        = FakeResponsibleGambling()
  val publisher = InMemoryPublisher()

  private var kit: AnkkaTestKit = null
  private var server: HttpServer = null
  /** Read on every request: a restart binds the server again, on a new port. */
  private def base: String = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("the server is not bound"))}"
  private val http              = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  def testKit: AnkkaTestKit = kit

  /** The service's components, the deposit workflow asking the fake; a suite adds a prototype's beside them. */
  def components: Seq[ComponentDescriptor] = WalletService.components(_ => rg)

  /** Other services by name, as `ankka.local-services.<name>`: a suite that runs the real client points one at a scripted server. */
  def localServices: Map[String, String] = Map.empty

  override def beforeAll(): Unit =
    server = HttpServer.at("127.0.0.1", 0)(WalletService.endpoints(brand, () => TestTokens.authenticate)*)
    kit = AnkkaTestKit.start(components, Seq(ProjectionRuntime.withPublisher(publisher), server), localServices = localServices)

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit = rg.allow()

  /** A request as a service of the project (the testkit's local caller header). */
  def send(method: String, path: String, body: Option[String] = None, as: Caller = Caller.Service("local", "game-provider")): (Int, String) =
    val (header, value) = kit.asCaller(as)
    val builder = JdkRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).header(header, value)
    body match
      case Some(json) => builder.header("Content-Type", "application/json").method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None       => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  /** A request with a token: `TestTokens.player` or `TestTokens.operator`. */
  def sendWithToken(method: String, path: String, token: String, body: Option[String] = None): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).header(TestTokens.Header, token)
    body match
      case Some(json) => builder.header("Content-Type", "application/json").method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None       => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  def wallet(player: String, currency: String = "EUR") = s"/v1/casino/brands/$brand/wallets/$player/$currency"

  /** Retries `read` until `done` holds of it or the deadline passes: views and consumers lag their source. */
  def eventually[A](read: => A, timeout: FiniteDuration = 30.seconds)(done: A => Boolean): A =
    val deadline = System.nanoTime() + timeout.toNanos
    var last     = read
    while !done(last) && System.nanoTime() < deadline do
      Thread.sleep(200)
      last = read
    assert(done(last), s"never held: $last")
    last

  /** The facts published to `topic` about `key`, as text. */
  def facts(topic: String, key: String): Seq[String] = publisher.publishedTo(topic).filter(_.recordKey.contains(key)).map(_.text)

  /** A JSON field's value as text, crude but enough to read a reply in a test. */
  def field(json: String, name: String): Option[String] =
    s""""$name"\\s*:\\s*("([^"]*)"|-?[0-9]+|true|false)""".r.findFirstMatchIn(json).map(m => Option(m.group(2)).getOrElse(m.group(1)))

/**
 * Tokens for the suites: a header the test authenticator reads as a verified token would be read
 * (issuer name, realm in `iss`, realm roles), so `Routes.player` and `Routes.operator` decide on the
 * same facts they decide on in a cluster. Never registered outside a test.
 */
object TestTokens:
  val Header = "X-Test-Token"

  def player(subject: String, realm: String = "casino-goldvibe"): String = s"$subject|player|$realm|"
  def operator(subject: String, roles: String = "brand_goldvibe"): String = s"$subject|staff|eitheror|$roles"

  val authenticate: com.thinkmorestupidless.ankka.http.Acl =
    import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Principal}
    Acl.Authenticate { context =>
      context.header(Header).map(_.split("\\|", -1)) match
        case Some(Array(sub, issuer, realm, roles)) =>
          // A player's `sub` is the realm's own user id; the player id travels in `eitheror_twin_id` (`Routes.playerId`).
          val player = issuer == "player"
          AuthDecision.Allow(Principal(if player then s"realm-user-$sub" else sub, roles = roles.split(',').filter(_.nonEmpty).toSet,
            claims = Map("iss" -> s"https://auth.test/realms/$realm") ++ Option.when(player)("eitheror_twin_id" -> sub), issuer = Some(issuer)))
        case _ => AuthDecision.Unauthenticated("realm=\"test\"")
    }
