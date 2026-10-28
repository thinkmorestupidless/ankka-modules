package io.eitheror.casino.rg.it

import io.eitheror.casino.rg.Json
import io.eitheror.casino.rg.it.Callers.Read
import io.eitheror.casino.rg.it.Callers.Read.*

import java.time.Duration
import java.util.UUID
import scala.concurrent.duration.*

/**
 * T023, T026: every answer each check gives, read by each caller's own decoder ([[Callers]]), so a change of
 * shape here that a caller would misread fails here rather than in that caller's suite. Where a caller reads
 * an answer as unavailable on purpose, the test says so (R6: the loss limit's refusal and `unavailable`).
 */
class CallersSuite extends munit.FunSuite:
  override val munitTimeout = 3.minutes

  private lazy val kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def player() = s"p-${UUID.randomUUID().toString.take(8)}"

  private def mayDeposit(p: String, amount: Long, used: Long = 0) = kit.json("POST", kit.servicePath("/may-deposit"),
    Json.obj("playerId" -> p, "currency" -> "EUR", "amount" -> amount, "last24h" -> used, "last7d" -> used, "last30d" -> used), kit.asService("wallet"))
  private def mayBet(p: String, amount: Long, losses: Option[Long] = None) = kit.json("POST", kit.servicePath("/may-bet"),
    Json.obj((Seq("playerId" -> p, "currency" -> "EUR", "amount" -> amount) ++
      losses.toSeq.flatMap(l => Seq("lossLast24h" -> l, "lossLast7d" -> l, "lossLast30d" -> l)))*), kit.asService("game-provider"))
  private def mayLogIn(p: String) = kit.json("POST", kit.servicePath("/may-log-in"), Json.obj("playerId" -> p), kit.asService("players"))
  private def mayPlay(p: String)  = kit.request("GET", kit.servicePath(s"/$p/may-play"), None, kit.asService("game-provider"))

  private def onBreak(p: String) =
    assertEquals(kit.json("PUT", kit.playerPath(s"/$p/cooling-off"), Json.obj("until" -> kit.now().plus(Duration.ofDays(2)).toString), kit.asPlayer(p)).status, 200)
  private def excluded(p: String) = assertEquals(kit.request("PUT", kit.playerPath(s"/$p/permanent-exclusion"), None, kit.asPlayer(p)).status, 200)
  private def limit(p: String, t: String, v: String, c: Option[String] = Some("EUR")) =
    assertEquals(kit.json("PUT", kit.playerPath(s"/$p/limits/$t"), Json.obj("value" -> v, "currency" -> c), kit.asPlayer(p)).status, 200)

  test("may-deposit, as the wallet and players read it"):
    val (free, limited, resting, gone) = (player(), player(), player(), player())
    limit(limited, "DEPOSIT_DAILY", "10000")
    onBreak(resting)
    excluded(gone)
    val answers = Vector(mayDeposit(free, 100), mayDeposit(limited, 4_001, 6_000), mayDeposit(resting, 100), mayDeposit(gone, 100))
    assertEquals(answers.map(a => Callers.wallet(Some(a))),
      Vector(Allowed, Refused("over_limit"), Refused("cooling_off"), Refused("excluded")))
    assertEquals(answers.map(a => Callers.playersDeposit(Some(a))),
      Vector(Allowed, Refused("over_limit"), Refused("cooling_off"), Refused("excluded")))

  test("may-bet, as game-provider and the wallet read it; a loss limit's refusal and unavailable are read as unavailable and refuse (R6)"):
    val (free, resting, gone, lossy, unsummed) = (player(), player(), player(), player(), player())
    onBreak(resting)
    excluded(gone)
    limit(lossy, "LOSS_DAILY", "1000")
    limit(unsummed, "LOSS_DAILY", "1000")
    val answers = Vector(mayBet(free, 100), mayBet(resting, 100), mayBet(gone, 100), mayBet(lossy, 600, Some(500)), mayBet(unsummed, 100))
    assertEquals(answers.map(_.str("decision")), Vector("allowed", "cooling_off", "excluded", "spending_limit", "unavailable"))
    val expected: Vector[Read] = Vector(Allowed, Refused("cooling_off"), Refused("excluded"))
    assertEquals(answers.take(3).map(a => Callers.gameProviderBet(Some(a))), expected)
    assertEquals(answers.take(3).map(a => Callers.wallet(Some(a))), expected)
    assert(answers.drop(3).forall(a => Callers.gameProviderBet(Some(a)).isInstanceOf[Unavailable] && Callers.wallet(Some(a)).isInstanceOf[Unavailable]))

  test("may-log-in, as players reads it: a break with its end, an exclusion"):
    val (free, resting, gone) = (player(), player(), player())
    onBreak(resting)
    excluded(gone)
    assertEquals(Vector(free, resting, gone).map(p => Callers.playersLogIn(Some(mayLogIn(p)))),
      Vector(Allowed, Refused("cooling_off"), Refused("excluded")))

  test("may-play, as game-provider reads it: 200, or 403 with the reason"):
    val (free, resting, gone) = (player(), player(), player())
    onBreak(resting)
    excluded(gone)
    assertEquals(Vector(free, resting, gone).map(p => Callers.gameProviderPlay(Some(mayPlay(p)))),
      Vector(Allowed, Refused("cooling_off"), Refused("self_excluded")))

  test("a request a check cannot read is 400, and every caller reads that as unavailable"):
    val bad = kit.json("POST", kit.servicePath("/may-deposit"), Json.obj("playerId" -> "p"), kit.asService("wallet"))
    assertEquals(bad.status, 400)
    val reads = Vector(Callers.wallet(Some(bad)), Callers.playersDeposit(Some(bad)), Callers.playersLogIn(Some(bad)), Callers.gameProviderBet(Some(bad)))
    assert(reads.forall(_.isInstanceOf[Unavailable]), reads.toString)
