package io.eitheror.casino.kyc.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{CasinoPlayers, PlayerKycReviewRequired, PlayerRegistered}
import io.eitheror.casino.kyc.record.{Deadline, FlagReason, KycRecord, KycState, Open}

import java.time.{Duration, Instant}

/**
 * features/kyc/window.feature (US5): a registration read from `casino.players` sets the deadline, the timer
 * `kyc-window-<player>` fires at it, and `players`' suspend route (the kit's `FakePlayers`) is told when
 * the policy suspends. A timer is made due by the kit rather than waited for.
 */
class WindowFeatures extends KycFeatures("window.feature"):

  private var registeredAt: Instant  = null
  private var before: String         = ""
  private var after: String          = ""
  private var afterAt: Instant       = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    registeredAt = null; before = ""; after = ""; afterAt = null

  // ── the scene ─────────────────────────────────────────────────────────────────────────────────

  private def policy(fields: (String, Any)*): Unit =
    val r = kit.json("PUT", kit.operatorPath("/policy"), json(fields*), kit.asOperator())
    assertEquals(r.status, 200, r.text)

  private def timer(of: String = player) = s"kyc-window-$of"

  /** `player_registered` on `casino.players`, as `players` publishes it; the registration time kept. */
  private def register(of: String = player): Instant =
    val at = kit.now()
    kit.publish(CasinoPlayers, of, PlayerRegistered(of, at, None, None, None, Some("MT"), Some("EUR"), None, at))
    at

  private def deadlineOf(of: String = player): Option[Deadline] = state(of).deadline

  /** Registered under a window, the deadline and its timer in place. */
  private def registeredWithDeadline(): Unit =
    registeredAt = register()
    eventually("the deadline")(deadlineOf())
    eventually("the window's timer")(Option.when(kit.timerExists(timer()))(()))

  /** The clock past the deadline and the timer due. */
  private def lapse(of: String = player): Unit =
    val d = deadlineOf(of).getOrElse(fail("no deadline"))
    val past = Duration.between(kit.now(), d.at).plusMinutes(1)
    if !past.isNegative then kit.advance(past)
    kit.fireTimer(timer(of))

  private def reviewRequired: Vector[PlayerKycReviewRequired] =
    kit.facts(CasinoPlayers).collect { case f: PlayerKycReviewRequired if f.playerId == player => f }

  // ── registration ──────────────────────────────────────────────────────────────────────────────

  Given("a brand with a {string} day verification window") { (days: String) => policy("kycWindowDays" -> days.toInt) }

  Given("a brand with no verification window") { () => policy("kycWindowDays" -> null) }

  When("a player registers") { () => registeredAt = register() }

  Then("the player's deadline is {string} days from registration") { (days: String) =>
    val d = eventually("the deadline")(deadlineOf())
    assertEquals(d.at, registeredAt.plus(Duration.ofDays(days.toLong)))
    eventually("the window's timer")(Option.when(kit.timerExists(timer()))(()))
  }

  Then("the player has no deadline") { () =>
    // Another player registered after this one, read in order: once theirs is seen, this one was read too.
    policy("kycWindowDays" -> 3)
    val witness = newPlayer()
    register(witness)
    eventually("the witness's deadline")(deadlineOf(witness))
    assertEquals(deadlineOf(), None)
    assert(!kit.timerExists(timer()))
  }

  // ── the window lapses ─────────────────────────────────────────────────────────────────────────

  Given("a deadline passed on a player still unverified and a KYC policy that suspends on expiry") { () =>
    policy("kycWindowDays" -> 3, "autoSuspendOnWindowExpiry" -> true)
    registeredWithDeadline()
  }

  Given("a deadline passed on a player still unverified and a KYC policy that does not suspend on expiry") { () =>
    policy("kycWindowDays" -> 3, "autoSuspendOnWindowExpiry" -> false)
    registeredWithDeadline()
  }

  Given("a player the migration carried with a deadline already passed, unverified, under a policy that suspends on expiry") { () =>
    policy("kycWindowDays" -> 3, "autoSuspendOnWindowExpiry" -> true)
    val past = kit.now().minus(Duration.ofDays(2))
    kit.components.forEventSourcedEntity(EntityId(player)).call(KycRecord.open)
      .invoke(Open(KycState(playerId = player, deadline = Some(Deadline(past, migrated = true))), kit.now())): Unit
    eventually("the window's timer")(Option.when(kit.timerExists(timer()))(()))
  }

  Given("a player verified before their deadline") { () =>
    policy("kycWindowDays" -> 3, "autoSuspendOnWindowExpiry" -> true)
    registeredWithDeadline()
    assertEquals(overrideStatus("verified").status, 200)
  }

  When("the timer fires") { () => lapse() }

  When("the migration's timer fires") { () => kit.fireTimer(timer()) }

  Then("the brand's player records are told to suspend the player with reason {string}, once") { (reason: String) =>
    val call = eventually("the suspend")(kit.players.callsFor(player).headOption)
    val body = io.eitheror.casino.kyc.Json.parse(call.body).get
    assertEquals(body.get("reason").asText, reason)
    assertEquals(Instant.parse(body.get("deadline").asText), deadlineOf().get.at)
    eventually("the suspension recorded")(Option.when(deadlineOf().exists(_.suspensionRequested))(()))
    kit.fireTimer(timer()) // a run again, were the timer still there, asks nothing more
    Thread.sleep(1500)
    assertEquals(kit.players.callsFor(player).size, 1)
  }

  Then("a {string} fact is published with reason {string}") { (name: String, reason: String) =>
    assertEquals(name, "player_kyc_review_required")
    eventually(s"$name $reason")(reviewRequired.find(_.reason == reason))
  }

  Then("the player is flagged for review with reason {string}") { (reason: String) =>
    eventually(s"the $reason flag")(FlagReason.of(reason).flatMap(state().openFlag))
  }

  Then("the player is not suspended") { () =>
    eventually("the window handled")(Option.when(deadlineOf().exists(_.handled))(()))
    Thread.sleep(1500)
    assertEquals(kit.players.callsFor(player), Vector.empty)
  }

  Then("nothing happens") { () =>
    eventually("the timer gone")(Option.when(!kit.timerExists(timer()))(()))
    assertEquals(kit.players.callsFor(player), Vector.empty)
    assertEquals(state().flags, Vector.empty)
    assertEquals(reviewRequired, Vector.empty)
  }

  // ── a changed window ──────────────────────────────────────────────────────────────────────────

  Given("a KYC policy whose verification window is changed") { () =>
    policy("kycWindowDays" -> 3)
    before = player
    registeredAt = register(before)
    eventually("the first deadline")(deadlineOf(before))
  }

  When("it is saved") { () =>
    policy("kycWindowDays" -> 10)
    after = newPlayer()
    afterAt = register(after)
  }

  Then("players registered before keep their deadline") { () =>
    assertEquals(deadlineOf(before).map(_.at), Some(registeredAt.plus(Duration.ofDays(3))))
  }

  Then("players registered after get the new one") { () =>
    assertEquals(eventually("the later deadline")(deadlineOf(after)).at, afterAt.plus(Duration.ofDays(10)))
  }
