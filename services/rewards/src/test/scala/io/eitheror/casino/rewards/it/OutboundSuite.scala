package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.sdk.{ServiceClient, ServiceClients}
import io.eitheror.casino.rewards.{Json, RewardsRuntime}
import io.eitheror.casino.rewards.clients.{Called, GameProvider, Players, Programme, ProgrammeDocument, Wallet}

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * SC-006: the service calls `wallet`, `game-provider` and `players` by name, and the programme's code document at the
 * one address it is given, and nothing else: no affiliate system, no Customer.io, no Kafka of its own. Its facts
 * reach a topic through the runtime's producer, as every service's.
 *
 * Two halves, each covering what the other cannot. The sources: a neighbour is reached only through `Neighbour`, which
 * is built only for the three settings names, and a network client exists only in `Programme`. The running service:
 * a life that reaches every neighbour, with every service client and the programme's fetch recorded, names the three
 * and the configured address only. What the runtime itself reaches (the token issuer's keys, the telemetry
 * collector, the broker and the database) is the platform's, the same for every service, and not counted here.
 */
class OutboundSuite extends munit.FunSuite:
  private lazy val kit   = SharedKit.kit
  private lazy val paths = ClaimPaths(kit)
  override def munitTimeout = 300.seconds
  override def beforeEach(context: BeforeEach): Unit = kit.reset()
  override def afterEach(context: AfterEach): Unit   = kit.reset()

  // ── the sources ───────────────────────────────────────────────────────────────────────────────

  private val root: Path = Paths.get(sys.props.getOrElse("casino.root", "../.."), "services", "rewards")
  private val main: Path = root.resolve("src/main/scala/io/eitheror/casino/rewards")

  /** Every main source, by its path under the package, with comments removed so a doc naming a thing is not a use. */
  private lazy val sources: Map[String, String] =
    Files.walk(main).iterator.asScala.filter(_.toString.endsWith(".scala")).map { p =>
      main.relativize(p).toString -> Files.readString(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "")
    }.toMap

  private def filesUsing(pattern: String): Set[String] =
    val r = pattern.r
    sources.collect { case (f, text) if r.findFirstIn(text).isDefined => f }.toSet

  test("the sources: a service client is asked for a neighbour only in Neighbour, built only for the three settings names"):
    assert(sources.size > 20, sources.keys.toString)
    assertEquals(filesUsing("""\bservices\("""), Set("clients/Neighbour.scala"))
    val built = sources.values.flatMap("""(?<!class )\bNeighbour\(([^)]*)\)""".r.findAllMatchIn(_).map(_.group(1).trim)).toSet
    assertEquals(built, Set("services, settings.wallet", "services, settings.gameProvider", "services, settings.players"))

  test("the sources: a network client only in Programme; no Kafka or Customer.io client, nor one in the build"):
    assertEquals(filesUsing("""java\.net\.http|\bHttpClient\b|java\.net\.(Socket|URL|HttpURLConnection)\b|openConnection|\bnew Socket\b"""),
      Set("clients/Programme.scala"))
    assertEquals(filesUsing("""(?i)import\s+[\w.]*(kafka|customerio|customer\.io)"""), Set.empty[String])
    assertEquals(filesUsing("""(?i)customer\.io|customerio\.com"""), Set.empty[String])
    val build = Files.readString(root.resolve("build.sbt"))
    assert(!build.toLowerCase.contains("kafka"), "build.sbt names a Kafka dependency")

  // ── the running service ───────────────────────────────────────────────────────────────────────

  /** Every service client asked for, by name, through the factories the service's components use. */
  private final class Recording(under: ServiceClients, seen: ConcurrentLinkedQueue[String]) extends ServiceClients:
    def apply(name: String): ServiceClient = { seen.add(name); under(name) }
    def apply(project: String, name: String): ServiceClient = { seen.add(s"$project/$name"); under(project, name) }

  test("a life reaching every neighbour: wallet, game-provider and players by name, the programme at its address, and nothing else"):
    val names = ConcurrentLinkedQueue[String]()
    val urls  = ConcurrentLinkedQueue[String]()
    val s     = kit.settings
    RewardsRuntime.wallet = services => Wallet.http(Recording(services, names), s)
    RewardsRuntime.gameProvider = services => GameProvider.http(Recording(services, names), s)
    RewardsRuntime.players = services => Players.http(Recording(services, names), s)
    RewardsRuntime.programme = _ =>
      val under = Programme.http(s)
      new Programme:
        def fetch(url: String): Called[ProgrammeDocument] = { urls.add(url); under.fetch(url) }

    // players: the duplicate check reads the brand's switch
    val p = paths.newPlayer("ob-p")
    paths.checked(p)
    // wallet: a free bonus claimed, then reclaimed by an operator
    val bonus = paths.active(p, paths.holdingActive(p)).rewardId
    val r1 = kit.json("POST", kit.grantsPath(s"/$bonus/revoke"), Json.obj("mode" -> "reclaim"), kit.asOperator())
    assertEquals(r1.status, 200, r1.text)
    // game-provider: free rounds awarded by an operator, then revoked
    val game = paths.unique("g")
    kit.provider.game(game, "free_spins")
    kit.provider.spinValues("EUR", "0.10", "0.20")
    val award = kit.json("POST", kit.grantsPath(s"/$p"), Json.obj("type" -> "FREE_SPINS", "gameId" -> game, "provider" -> "st8",
      "spinCount" -> 10, "spinValue" -> 20L), kit.asOperator())
    assertEquals(award.status, 200, award.text)
    val r2 = kit.json("POST", kit.grantsPath(s"/${award.str("grantId")}/revoke"), Json.obj("mode" -> "forfeit"), kit.asOperator())
    assertEquals(r2.status, 200, r2.text)
    // the programme: the code document refreshed by its timer
    kit.programme.documentFor(kit.slug, System.nanoTime(), kit.now())
    kit.eventually("the document fetched")(Option.when(!urls.isEmpty)(()))

    assertEquals(names.asScala.toSet, Set("wallet", "game-provider", "players"))
    assertEquals(urls.asScala.toSet, s.codeDocumentUrl.toSet)
    Vector("wallet" -> kit.wallet, "game-provider" -> kit.provider, "players" -> kit.players, "the programme" -> kit.programme)
      .foreach((n, f) => assert(f.received.nonEmpty, s"$n was not called"))
