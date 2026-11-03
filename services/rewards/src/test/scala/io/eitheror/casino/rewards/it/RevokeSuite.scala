package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{AuditRecord, BonusCancelled, CasinoRewards, PlatformAudit}
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.{Json, RewardsRuntime}
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.clients.AwardTerms
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward.*
import io.eitheror.casino.rewards.steps.{FreeRounds, FreeRoundsFlow, FreeRoundsRequest}

import java.util.UUID

/**
 * An operator's revoke (FR-017; contracts/routes.md `POST /reward-grants/{rewardId}/revoke`) over the whole service:
 * the role, the mode, the reward, the wallet's forfeit or reclaim through `WalletStep`, free rounds at the provider,
 * and the revoke on the reward, the fact and the audit with the reason named and never written.
 */
class RevokeSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val Eur    = Currency.unsafe("EUR")
  private val Secret = "the player said something about their neighbour"
  private def unique(p: String) = s"$p-${UUID.randomUUID().toString.take(8)}"

  private def entity(id: String) = kit.components.forEventSourcedEntity(EntityId(id))
  private def reward(id: String) = entity(id).call(Reward.get).invoke()

  /** An active reward of a new player, its record at the fake: (reward, player). */
  private def active(freeRounds: Boolean = false): (String, String) =
    val (id, player) = (unique("rv-r"), unique("rv-p"))
    val terms = Option.when(freeRounds)(FreeRoundsTerms("g-1", "st8", 10, 20L, Destination.RealBalance))
    val opening = Opening(player, unique("o"), "Revoke", "MANUAL", PayloadType.BonusFunds, Decision(1000L, 10000L, 1000L, 10L, Eur),
      priority = Some(1L), source = Source.System, freeRounds = terms)
    entity(id).call(Reward.open).invoke(Open(opening, RewardStatus.Active, kit.now(), Some(WalletAward(Some(id), 10000L)))): Unit
    if !freeRounds then
      RewardsRuntime.wallet(kit.services).award(player, Eur, id, AwardTerms(opening.offerId, 1000L, 10L, 1000L, Some(1L), None)): Unit
    (id, player)

  private def revoke(id: String, fields: (String, Any)*)(as: Seq[(String, String)] = kit.asOperator()): Reply =
    kit.json("POST", kit.grantsPath(s"/$id/revoke"), Json.obj(fields*), as)

  private def audit(id: String): AuditRecord =
    kit.eventually("the revoke audited")(kit.facts(PlatformAudit).collectFirst {
      case r: AuditRecord if r.subjectId == id && r.action == "rewards.revoked" => r
    })

  test("only the three admin roles revoke; a player is refused by the ACL"):
    val (id, player) = active()
    val support = revoke(id, "mode" -> "forfeit")(kit.asOperator("brand_support"))
    assertEquals((support.status, support.str("error")), (403, "forbidden"))
    assertEquals(revoke(id, "mode" -> "forfeit")(kit.asPlayer(player)).status, 403)
    assertEquals(reward(id).status, RewardStatus.Active)
    assertEquals(kit.wallet.keys("awards/forfeit"), Vector.empty)

  test("a mode missing or unknown is INVALID_MODE before the reward is looked up; a reward not held is CLAIM_NOT_FOUND"):
    assertEquals(revoke("nope")().str("error"), "INVALID_MODE")
    val other = revoke("nope", "mode" -> "void")()
    assertEquals((other.status, other.str("error")), (400, "INVALID_MODE"))
    val missing = revoke(unique("rv-none"), "mode" -> "forfeit")()
    assertEquals((missing.status, missing.str("error")), (404, "CLAIM_NOT_FOUND"))

  test("a reward converted pending cap, or ended, is NO_ACTIVE_BONUS, and nothing is asked of the wallet"):
    val (converted, _) = active()
    entity(converted).call(Reward.convert).invoke(Convert(kit.now())): Unit
    val (ended, _) = active()
    entity(ended).call(Reward.forfeit).invoke(Forfeit(ForfeitReason.BonusExhausted, None, kit.now())): Unit
    for id <- Vector(converted, ended) do
      val r = revoke(id, "mode" -> "reclaim")()
      assertEquals((r.status, r.str("error")), (409, "NO_ACTIVE_BONUS"), id)
    assertEquals(kit.wallet.keys("awards/cancel") ++ kit.wallet.keys("awards/forfeit"), Vector.empty)

  test("forfeit: the wallet's forfeit, nothing clawed back, the reward revoked with the operator; the reason audited by name only"):
    val (id, player) = active()
    kit.wallet.realFor(player, "EUR", 4000L)
    kit.wallet.winningsSince(player, "EUR", id, 700L)
    val r = revoke(id, "mode" -> "forfeit", "reason" -> Secret)(kit.asOperator(subject = "op-7"))
    assertEquals((r.status, r.str("status"), r.str("mode")), (200, "revoked", "forfeit"), r.text)
    assertEquals((r.json.long("clawbackApplied"), r.json.long("newRealBalance")), (Some(0L), Some(4000L)))
    assertEquals(kit.wallet.keys("awards/forfeit"), Vector(s"$id/revoke-forfeit"))
    assertEquals(kit.wallet.keys("awards/cancel"), Vector.empty)
    val s = reward(id)
    assertEquals((s.status, s.terminationReason, s.revoke), (RewardStatus.Revoked, Some("forfeit"), Some(Revocation("op-7", RevokeMode.Forfeit))))
    val a = audit(id)
    assertEquals((a.actorId, a.reasonCode), ("op-7", Some("forfeit")))
    assertEquals(a.changes.find(_.field == "reason").map(c => (c.before, c.after)), Some((None, None)))
    val cancelled = kit.eventually("bonus_cancelled")(kit.facts(CasinoRewards).collectFirst { case f: BonusCancelled if f.bonusId == id => f })
    assertEquals((cancelled.reason, cancelled.amountCents.value), (Some("forfeit"), 0L))
    assert(!kit.factTexts(CasinoRewards).exists(_.contains(Secret)) && !kit.factTexts(PlatformAudit).exists(_.contains(Secret)))
    assert(!kit.logText.exists(_.contains(Secret)))

  test("reclaim: the wallet's cancel with requireBaseline, the clawback applied and recorded"):
    val (id, player) = active()
    kit.wallet.realFor(player, "EUR", 4000L)
    kit.wallet.winningsSince(player, "EUR", id, 700L)
    val r = revoke(id, "mode" -> "reclaim")()
    assertEquals((r.status, r.json.long("clawbackApplied"), r.json.long("newRealBalance")), (200, Some(700L), Some(3300L)), r.text)
    assertEquals(kit.wallet.calls("awards/cancel").flatMap(_.json).map(b => (b.text("key"), b.bool("requireBaseline"))),
      Vector((Some(s"$id/revoke-reclaim"), Some(true))))
    assertEquals(reward(id).status, RewardStatus.Revoked)
    assert(audit(id).changes.contains(io.eitheror.casino.contract.facts.AuditChange("clawback", None, Some("700"))))

  test("a reclaim of a record with no baseline is MISSING_BASELINE and stands for no other step: a forfeit, or the player's cancel, still goes"):
    val (id, player) = active()
    kit.wallet.noBaseline(player, "EUR", id)
    val refused = revoke(id, "mode" -> "reclaim")()
    assertEquals((refused.status, refused.str("error")), (409, "MISSING_BASELINE"))
    assertEquals(reward(id).status, RewardStatus.Active)
    val again = revoke(id, "mode" -> "reclaim")()
    assertEquals(again.str("error"), "MISSING_BASELINE")
    assertEquals(kit.wallet.keys("awards/cancel"), Vector(s"$id/revoke-reclaim"))
    val forfeit = revoke(id, "mode" -> "forfeit")()
    assertEquals((forfeit.status, forfeit.str("status")), (200, "revoked"), forfeit.text)
    assertEquals(reward(id).status, RewardStatus.Revoked)

  test("a wallet that does not answer is service_unavailable; asked again once it answers, the revoke is recorded, the record forfeited once"):
    val (id, player) = active()
    kit.wallet.routeAnswer("awards/forfeit", Answer(503, """{"error":"down"}"""))
    val first = revoke(id, "mode" -> "forfeit")()
    assertEquals((first.status, first.str("error")), (503, "service_unavailable"))
    assertEquals(reward(id).status, RewardStatus.Active)
    kit.wallet.clearRoute("awards/forfeit")
    val done = kit.eventually("revoked on a later ask") {
      Some(revoke(id, "mode" -> "forfeit")()).filter(_.status == 200)
    }
    assertEquals(done.str("status"), "revoked")
    assertEquals(reward(id).status, RewardStatus.Revoked)
    assert(kit.wallet.keys("awards/forfeit").forall(_ == s"$id/revoke-forfeit"))
    assertEquals(kit.wallet.open(player, "EUR"), Vector.empty)
    val repeat = revoke(id, "mode" -> "forfeit")()
    assertEquals((repeat.status, repeat.str("error")), (409, "NO_ACTIVE_BONUS"))

  test("free rounds: cancelled at the provider, then revoked, nothing clawed back; no wallet command"):
    val (id, player) = active(freeRounds = true)
    val r = revoke(id, "mode" -> "forfeit")()
    assertEquals((r.status, r.json.bool("freeSpins"), r.json.long("clawbackApplied")), (200, Some(true), Some(0L)), r.text)
    assertEquals(kit.provider.cancelCalls(id).size, 1)
    assertEquals(reward(id).status, RewardStatus.Revoked)
    assertEquals(kit.wallet.received.filter(_.path.contains(s"/wallets/$player/")), Vector.empty)
    val cancelled = kit.eventually("bonus_cancelled")(kit.facts(CasinoRewards).collectFirst { case f: BonusCancelled if f.bonusId == id => f })
    assertEquals(cancelled.reason, Some("free_spins_revoked"))

  test("free rounds the provider cannot be reached for are PROVIDER_UNAVAILABLE and stay live; refused or finished at the provider revokes all the same"):
    val (down, _) = active(freeRounds = true)
    kit.provider.down = Some(Answer(503, """{"error":"down"}"""))
    val r = revoke(down, "mode" -> "forfeit")()
    assertEquals((r.status, r.str("error")), (503, "PROVIDER_UNAVAILABLE"))
    assertEquals(reward(down).status, RewardStatus.Active)
    kit.provider.down = None
    val (refused, _) = active(freeRounds = true)
    kit.provider.cancelFor(refused, Answer(409, """{"error":"BONUS_FINISHED"}"""))
    assertEquals(revoke(refused, "mode" -> "reclaim")().str("status"), "revoked")
    assertEquals(reward(refused).status, RewardStatus.Revoked)

  test("free rounds no longer live are FREE_SPINS_FINISHED with the grant's status"):
    val (id, _) = active(freeRounds = true)
    entity(id).call(Reward.expire).invoke(Expire(ExpireReason.FreeSpinsExpiredUnused, kit.now())): Unit
    val r = revoke(id, "mode" -> "forfeit")()
    assertEquals((r.status, r.str("error"), r.str("grantStatus")), (409, "FREE_SPINS_FINISHED", "expired"))
    assertEquals(kit.provider.cancelCalls(id), Vector.empty)

  test("free rounds are cancelled through FreeRounds; unavailable then asked again once the provider answers"):
    val (id, _) = active(freeRounds = true)
    kit.provider.down = Some(Answer(503, """{"error":"down"}"""))
    assertEquals(revoke(id, "mode" -> "forfeit")().str("error"), "PROVIDER_UNAVAILABLE")
    kit.provider.down = None
    val r = revoke(id, "mode" -> "forfeit")()
    assertEquals((r.status, r.str("status")), (200, "revoked"), r.text)
    assertEquals(kit.provider.cancelCalls(id).size, 2)
    val flow = kit.components.forWorkflow(EntityId(id)).call(FreeRounds.status).invoke()
    assertEquals((flow.phase, flow.cancelled), (FreeRoundsFlow.Cancelled, Some("cancelled")))

  test("the provider's error on the cancel is PROVIDER_UNAVAILABLE"):
    val (id, _) = active(freeRounds = true)
    kit.provider.cancelFor(id, FakeGameProvider.status("error"))
    val r = revoke(id, "mode" -> "forfeit")()
    assertEquals((r.status, r.str("error")), (503, "PROVIDER_UNAVAILABLE"))
    assertEquals(reward(id).status, RewardStatus.Active)

  test("what free rounds accumulated is forfeited: nothing paid at the revoke, nor for a round after it"):
    val (id, player) = active(freeRounds = true)
    entity(id).call(Reward.roundSettled).invoke(RoundSettled("rd-1", 400L, kit.now())): Unit
    assertEquals(reward(id).freeRounds.map(_.tally.accumulated), Some(400L))
    assertEquals(revoke(id, "mode" -> "forfeit")().str("status"), "revoked")
    val late = entity(id).call(Reward.roundSettled).invoke(RoundSettled("rd-2", 300L, kit.now()))
    assertEquals((late.payment, late.skipped), (None, Some("not_live")))
    assertEquals(entity(id).call(Reward.finalise).invoke(Finalise(kit.now().plusSeconds(86400L * 60))).payment, None)
    Thread.sleep(300)
    assertEquals(kit.wallet.received.filter(_.path.contains(s"/wallets/$player/")), Vector.empty)

  test("free rounds still being confirmed: the revoke cancels them and the polling stops"):
    RewardsRuntime.wire(kit.settings.copy(pollEvery = java.time.Duration.ofMillis(200)), () => kit.now())
    val (id, player) = active(freeRounds = true)
    kit.provider.createStatus(id, "processing")
    kit.components.forWorkflow(EntityId(id)).call(FreeRounds.start).invoke(FreeRoundsRequest(id, player, "EUR", "g-1", 10, 20L, 30)): Unit
    kit.eventually("polling")(Option.when(kit.provider.polls(id).nonEmpty)(()))
    assertEquals(revoke(id, "mode" -> "forfeit")().str("status"), "revoked")
    assertEquals(reward(id).status, RewardStatus.Revoked)
    val polls = kit.provider.polls(id).size
    Thread.sleep(800)
    assertEquals(kit.provider.polls(id).size, polls)
