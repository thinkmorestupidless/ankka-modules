package io.eitheror.casino.rewards.it

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.clients.*

import java.time.{Duration, Instant}

/** The plumbing: the service starts against Postgres, the broker carries facts, every fake answers through the clients. */
class KitSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit

  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val eur = Currency.unsafe("EUR")

  test("the service starts with its components and endpoints, and its timers' table is there"):
    assert(kit.baseUrl.startsWith("http://127.0.0.1:"))
    assert(kit.tables.contains("ankka_timers"))
    assert(!kit.timerExists("codes-nobody"))

  test("a published fact is on the broker"):
    val at = Instant.now()
    kit.publish(CasinoPlayers, "kit-p1", PlayerRegistered("kit-p1", at, None, None, None, None, None, None, at,
      email = Some(Personal("ana@example.test"))))
    kit.eventually("published")(kit.facts(CasinoPlayers).collectFirst { case f: PlayerRegistered if f.playerId == "kit-p1" => f })

  test("the wallet, game-provider and players answer through the service's own clients, by name"):
    val wallet = RewardsRuntime.wallet(kit.services)
    wallet.award("kit-p2", eur, "kit-r1", AwardTerms("offer-1", 1000L, 30L, 1000L, None, None)) match
      case Called.Done(a) => assertEquals(a.recordId, Some("kit-r1"))
      case other          => fail(s"not awarded: $other")
    assertEquals(wallet.restrictedFunds("kit-p2", eur).toString.contains("kit-r1"), true)
    kit.provider.game("sweet-bonanza", "free_spins")
    assertEquals(RewardsRuntime.gameProvider(kit.services).game("sweet-bonanza"), Called.Done(ProviderGame("sweet-bonanza", Vector("free_spins"))))
    kit.players.multiAccountDetection = false
    assertEquals(RewardsRuntime.players(kit.services).brandConfig(), Called.Done(BrandConfig(false)))
    assertEquals((kit.wallet.keys("awards"), kit.players.brandConfigReads.size), (Vector("kit-r1"), 1))

  test("a fake that is down is not an answer"):
    kit.wallet.down = Some(Answer(503, "{}"))
    assert(RewardsRuntime.wallet(kit.services).balance("kit-p3", eur) match
      case Called.Unreachable(_) => true
      case _                     => false)

  test("the programme's code document is read at the kit's address"):
    kit.programme.documentFor(kit.slug, 3L, Instant.EPOCH, ("SPRING", "offer-2", "tag"))
    val url = RewardsRuntime.settings.codeDocumentUrl.getOrElse(fail("the kit names no document"))
    assertEquals(RewardsRuntime.programme(kit.services).fetch(url),
      Called.Done(ProgrammeDocument(kit.slug, 3L, Some(Instant.EPOCH), Vector(ProgrammeCode("SPRING", "offer-2", "tag")))))

  test("the clock is the kit's to move"):
    val before = RewardsRuntime.now()
    kit.advance(Duration.ofDays(3))
    assert(!RewardsRuntime.now().isBefore(before.plus(Duration.ofDays(3))))
    kit.setClock(Instant.parse("2030-01-01T00:00:00Z"))
    assert(Duration.between(Instant.parse("2030-01-01T00:00:00Z"), RewardsRuntime.now()).abs.toSeconds < 5)
