package io.eitheror.casino.rg.features

import io.eitheror.casino.contract.facts.{CasinoSessions, GameSessionEnded}
import io.eitheror.casino.rg.Json.*
import io.eitheror.casino.rg.it.{Callers, Reply}
import io.eitheror.casino.rg.it.Callers.Read

import java.time.{Duration, Instant, ZoneOffset}
import java.time.temporal.ChronoUnit

/**
 * features/responsible-gambling/play.feature (US3, FR-008 to FR-011): sign-in through `may-log-in`, the
 * launch through `may-play`, the bet through `may-bet`, each read as its caller reads it ([[Callers]]).
 * "Cannot be reached" is the callers' rule (002, 003, 008), held here through their decoders.
 */
class PlayFeatures extends RgFeatures("play.feature"):

  private var until: Instant = Instant.EPOCH
  private var reads: Vector[Read] = Vector.empty

  /** Moves the clock to midday UTC when a scenario's minutes would otherwise cross midnight. */
  private def awayFromMidnight(): Unit =
    val now = kit.now().atOffset(ZoneOffset.UTC)
    if now.getHour < 3 then kit.advance(Duration.between(now, now.withHour(12).truncatedTo(ChronoUnit.HOURS)))

  private def sessionLimit(minutes: String): Unit =
    assertEquals(kit.json("PUT", kit.operatorPath(s"/$player/limits/SESSION_MINUTES"), json("value" -> minutes), kit.asOperator()).status, 200)

  Given("the player is on a break") { () =>
    until = kit.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS)
    assertEquals(setBreak(until).status, 200)
  }

  When("they sign in") { () => last = mayLogIn() }

  Then("the sign-in is refused with the date the break ends") { () =>
    assertEquals((last.str("decision"), Instant.parse(last.str("endsAt"))), ("cooling_off", until))
  }

  Then("the brand's player records and the realm refuse alike") { () =>
    // Both go through players' one reading of may-log-in (008 RealmRoutes and SessionRoutes).
    assertEquals(Callers.playersLogIn(Some(last)), Read.Refused("cooling_off"))
  }

  Given("the player is self-excluded") { () => assertEquals(excludePermanently().status, 200) }

  Then("the sign-in is refused as permanently excluded") { () => assertEquals(last.str("decision"), "excluded") }

  Then("they are directed to support") { () =>
    // The wording is players' (008); what it rests on is an exclusion, not a break with an end.
    assertEquals(Callers.playersLogIn(Some(last)), Read.Refused("excluded"))
    assertEquals(last.str("endsAt"), "")
  }

  When("the game provider asks whether they may play") { () => last = mayPlay() }

  Then("the answer is cooling off with the end of the break") { () =>
    assertEquals((last.status, last.str("reason"), Instant.parse(last.str("coolingOffUntil"))), (403, "cooling_off", until))
  }

  Then("the launch is refused") { () => assertEquals(Callers.gameProviderPlay(Some(last)), Read.Refused("cooling_off")) }

  Given("a session limit of {string} minutes and sessions totalling {string} minutes started today, open ones counted to now") {
    (limit: String, used: String) =>
      awayFromMidnight()
      sessionLimit(limit)
      startSession("gs-open", kit.now().minus(Duration.ofMinutes(used.toLong)).minusSeconds(5))
      counted(kit.now(), "gs-open")
  }

  Then("the answer is session limit reached, with {string} and {string}") { (limit: String, used: String) =>
    assertEquals((last.status, last.str("reason"), last.json.long("limitMinutes"), last.json.long("usedMinutes")),
      (403, "session_limit_reached", Some(limit.toLong), Some(used.toLong)), last.text)
  }

  Given("a session limit of {string} minutes and sessions totalling {string} minutes started today") { (limit: String, used: String) =>
    awayFromMidnight()
    sessionLimit(limit)
    startSession("gs-open", kit.now().minus(Duration.ofMinutes(used.toLong)).minusSeconds(5))
    counted(kit.now(), "gs-open")
  }

  Then("the answer is may play") { () =>
    assertEquals((last.status, Callers.gameProviderPlay(Some(last))), (200, Read.Allowed), last.text)
  }

  Given("the player started a session yesterday and has played nothing today") { () =>
    awayFromMidnight()
    sessionLimit("60")
    val yesterday = kit.now().truncatedTo(ChronoUnit.DAYS).minus(Duration.ofHours(2))
    startSession("gs-yesterday", yesterday)
    kit.publish(CasinoSessions, player, GameSessionEnded(player, "gs-yesterday", "game-1", 90, yesterday.plus(Duration.ofMinutes(90))))
    counted(yesterday, "gs-yesterday")
  }

  Then("yesterday's minutes do not count") { () =>
    assertEquals((last.status, last.json.long("minutesUsed")), (200, Some(0L)), last.text)
  }

  // ── the bet ─────────────────────────────────────────────────────────────────────────────────────

  When("the game provider debits a bet") { () => last = mayBet(100) }

  private var walletAnswer: Reply = null

  Then("the wallet asks responsible gambling") { () => walletAnswer = mayBet(100, as = "wallet") }

  Then("the bet is refused as locked, with the balance") { () =>
    // game-provider answers ST8 `player_locked` with the balance for any of these (003); the wallet is never debited.
    assert(Callers.gameProviderBet(Some(last)).isInstanceOf[Read.Refused], last.text)
    assert(Callers.wallet(Some(walletAnswer)).isInstanceOf[Read.Refused], walletAnswer.text)
  }

  // ── unreachable ─────────────────────────────────────────────────────────────────────────────────

  Given("responsible gambling cannot be reached") { () => () }

  When("the brand's player records asks it") { () => reads = Vector(Callers.playersLogIn(None), Callers.playersDeposit(None)) }
  When("the game provider asks it") { () => reads = Vector(Callers.gameProviderPlay(None), Callers.gameProviderBet(None)) }
  When("the wallet asks it") { () => reads = Vector(Callers.wallet(None)) }

  Then("the answer is unavailable") { () => assert(reads.forall(_.isInstanceOf[Read.Unavailable]), reads.toString) }

  Then("the caller refuses") { () => assert(!reads.contains(Read.Allowed)) }

  // ── a break that ended ──────────────────────────────────────────────────────────────────────────

  Given("the player's break has ended") { () =>
    assertEquals(setBreak(kit.now().plus(Duration.ofHours(2))).status, 200)
    kit.advance(Duration.ofHours(3))
  }

  When("they sign in, launch a game and place a bet") { () =>
    reads = Vector(Callers.playersLogIn(Some(mayLogIn())), Callers.gameProviderPlay(Some(mayPlay())), Callers.gameProviderBet(Some(mayBet(100))))
  }

  Then("nothing refuses them for the break") { () => assertEquals(reads, Vector.fill(3)(Read.Allowed)) }
