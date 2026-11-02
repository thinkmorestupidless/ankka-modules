package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{CasinoRewards, FreeSpinsGranted, PlayerRegistered}
import io.eitheror.casino.rewards.{Json, RewardsRuntime}
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.api.FreeRoundsGrants
import io.eitheror.casino.rewards.api.FreeRoundsGrants.{How, Result}
import io.eitheror.casino.rewards.offers.{Offer, OfferType}
import io.eitheror.casino.rewards.reward.*

import java.time.temporal.ChronoUnit

/**
 * The grant of free rounds (FR-023; task T054) over the whole service: the spin value for the player's currency, the
 * evaluation, G1's game check and the spin-value check in the original's order, then the reward opened `ACTIVE` and
 * `FreeRounds` asked, the answer given once the provider answered the create.
 */
class FreeRoundsGrantSuite extends munit.FunSuite:
  private lazy val kit   = SharedKit.kit
  private lazy val paths = ClaimPaths(kit)
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def env = FreeRoundsGrants.Env(kit.components, RewardsRuntime.gameProvider(kit.services), RewardsRuntime.wallet(kit.services),
    () => kit.now(), kit.settings.requestBound)

  private def reward(id: String) = kit.components.forEventSourcedEntity(EntityId(id)).call(Reward.get).invoke()

  /** A free-rounds offer of 10 rounds on an eligible game, at 0.20 the provider offers in euros, `fields` overriding. */
  private def spins(fields: (String, Any)*): Offer =
    val game = paths.unique("game")
    kit.provider.game(game, "free_spins")
    kit.provider.spinValues("EUR", "0.10", "0.20")
    paths.offerOf(paths.offer((Vector[(String, Any)]("name" -> paths.unique("Spins"), "type" -> "FREE_SPINS", "currency" -> "EUR",
      "gameId" -> game, "gameName" -> "Book", "provider" -> "st8", "spinCount" -> 10, "spinValue" -> 20L) ++ fields)*))

  private def perCurrency(): Offer =
    val game = paths.unique("game")
    kit.provider.game(game, "free_spins")
    kit.provider.spinValues("GBP", "0.25")
    paths.offerOf(paths.offer("name" -> paths.unique("Spins"), "type" -> "FREE_SPINS", "gameId" -> game, "provider" -> "st8",
      "spinCount" -> 10, "spinConfigurations" -> Json.arr(Json.obj("currency" -> "GBP", "spinValue" -> 25L))))

  private def give(player: String, offer: Offer, how: How = How.Claim(Source.CustomerioWebhook)): Result =
    FreeRoundsGrants.give(env, player, offer, how)

  private def registered(player: String, currency: String): Unit =
    paths.players(player, PlayerRegistered(player, kit.now(), None, None, None, Some("GB"), Some(currency), None, kit.now()))
    paths.awaitRecord(player, "registered")(_.currency.contains(currency)): Unit

  test("granted: the reward ACTIVE with the offer's rounds, created at the provider, the answer after the create"):
    val offer  = spins("freeSpinsExpiryDays" -> 7)
    val player = paths.newPlayer()
    val result = give(player, offer)
    val id = result match
      case Result.Granted(id, status) => assertEquals(status, "active"); id
      case other                      => fail(s"not granted: $other")
    val s = reward(id)
    assertEquals((s.status, s.offerId, s.faceValue, s.source), (RewardStatus.Active, offer.id, 0L, Source.CustomerioWebhook))
    assertEquals(s.freeRounds.map(f => (f.terms.gameId, f.terms.spinCount, f.terms.spinValue, f.terms.gameName)),
      Some((offer.gameId.get, 10, 20L, Some("Book"))))
    assertEquals(s.expiresAt, s.grantedAt.map(_.plus(7, ChronoUnit.DAYS)))
    val created = kit.provider.createCalls.flatMap(_.json).filter(_.text("grantId").contains(id))
    assertEquals(created.map(_.long("durationSeconds")), Vector(Some(7L * 86400L)))
    assert(paths.record(player).rewards.get(id).exists(_.claim.isDefined), "the claim is reserved on the player's record")
    val fact = kit.eventually("free_spins_granted")(kit.facts(CasinoRewards).collectFirst { case f: FreeSpinsGranted if f.bonusId == id => f })
    assertEquals(fact.rounds, 10)

  test("processing is answered once the create answers, and the provider is polled until it confirms"):
    RewardsRuntime.wire(kit.settings.copy(pollEvery = java.time.Duration.ofMillis(200)), () => kit.now())
    val offer = spins()
    kit.provider.createAnswer = Some(FakeGameProvider.status("processing"))
    give(paths.newPlayer(), offer) match
      case Result.Granted(id, status) =>
        assertEquals(status, "processing")
        kit.eventually("polled")(Option.when(kit.provider.polls(id).nonEmpty)(()))
        kit.provider.statusFor(id, "active")
        kit.eventually("confirmed")(Option.when(reward(id).freeRounds.flatMap(_.providerStatus).contains("active"))(()))
      case other => fail(s"not granted: $other")

  test("not configured at the provider is playable"):
    val offer = spins()
    kit.provider.createAnswer = Some(FakeGameProvider.status("not_configured"))
    assert(give(paths.newPlayer(), offer).isInstanceOf[Result.Granted])

  test("rejected at the create: provider_rejected naming the grant, the reward cancelled provider_failed"):
    val offer  = spins()
    val player = paths.newPlayer()
    kit.provider.createAnswer = Some(FakeGameProvider.status("rejected"))
    give(player, offer) match
      case Result.ProviderRejected(id) =>
        val s = reward(id)
        assertEquals((s.status, s.terminationReason), (RewardStatus.Cancelled, Some("provider_failed")))
        kit.provider.createAnswer = None
        // Not a claim (FR-023): the offer may be given again, once the cancel has reached the player's record.
        kit.eventually("given again")(Some(give(player, offer)).collect { case g: Result.Granted => g }): Unit
      case other => fail(s"not rejected: $other")

  test("the spin value per currency: the player's currency unknown is player_currency_unresolved, one not configured names it"):
    val offer = perCurrency()
    val nobody = paths.newPlayer()
    assertEquals(give(nobody, offer), Result.Refused("player_currency_unresolved"))
    val euro = paths.newPlayer()
    registered(euro, "EUR")
    assertEquals(give(euro, offer), Result.Refused("currency_not_configured", Vector("playerCurrency" -> "EUR")))
    val pound = paths.newPlayer()
    registered(pound, "GBP")
    give(pound, offer) match
      case Result.Granted(id, _) =>
        assertEquals((reward(id).currency, reward(id).freeRounds.map(_.terms.spinValue)), ("GBP", Some(25L)))
        assertEquals(kit.provider.createCalls.flatMap(_.json).filter(_.text("grantId").contains(id)).map(b => (b.text("spinValue"), b.text("currency"))),
          Vector((Some("0.25"), Some("GBP"))))
      case other => fail(s"not granted: $other")
    assertEquals(kit.provider.createCalls.flatMap(_.json).count(b => b.text("playerId").exists(Set(nobody, euro))), 0)

  test("a game no longer eligible is game_not_eligible; one the catalogue cannot be asked about is unavailable"):
    val offer = spins()
    kit.provider.game(offer.gameId.get)
    assertEquals(give(paths.newPlayer(), offer), Result.Refused("game_not_eligible"))
    kit.provider.down = Some(Answer(503, """{"error":"down"}"""))
    assertEquals(give(paths.newPlayer(), offer), Result.Unavailable)
    kit.provider.down = None

  test("a spin value the provider does not offer is refused with its values in minor units; not configured or unreachable goes ahead"):
    val offer = spins()
    kit.provider.spinValues("EUR", "0.50", "0.10")
    assertEquals(give(paths.newPlayer(), offer), Result.Refused("spin_value_not_offered", Vector("allowedValues" -> Vector(50L, 10L))))
    kit.provider.spinValuesAnswer = Some(FakeGameProvider.status("not_configured"))
    assert(give(paths.newPlayer(), offer).isInstanceOf[Result.Granted])
    kit.provider.spinValuesAnswer = Some(Answer(503, """{"error":"down"}"""))
    assert(give(paths.newPlayer(), offer).isInstanceOf[Result.Granted])

  test("a decline is answered before the provider is asked, and reserves nothing"):
    val offer  = spins("maxClaimsPerPlayer" -> 1)
    val player = paths.newPlayer()
    assert(give(player, offer).isInstanceOf[Result.Granted])
    kit.provider.clearReceived()
    assertEquals(give(player, offer), Result.Declined("MAX_CLAIMS_REACHED"))
    assertEquals(kit.provider.received, Vector.empty)
    assertEquals(paths.record(player).rewards.count(_._2.offerId == offer.id), 1)

  test("an operator's rounds with no offer: type MANUAL, opened by the operator, nothing evaluated"):
    val base = spins()
    val offer = base.copy(id = FreeRoundsGrants.manualOfferId(), name = "Manual rounds", offerType = OfferType.FreeSpins,
      maxClaimsPerPlayer = Some(1))
    val player = paths.newPlayer()
    for _ <- 1 to 2 do
      give(player, offer, How.Manual("op-9")) match
        case Result.Granted(id, _) =>
          val s = reward(id)
          assertEquals((s.offerType, s.source, s.openedBy, s.offerId), ("MANUAL", Source.OperatorManual, Some("op-9"), offer.id))
        case other => fail(s"not granted: $other")

  test("an offer that is not free rounds is refused before anything is asked"):
    val offer = paths.offerOf(paths.noDeposit())
    kit.provider.clearReceived()
    assertEquals(give(paths.newPlayer(), offer), Result.Refused("invalid_payload_type"))
    assertEquals(kit.provider.received, Vector.empty)
