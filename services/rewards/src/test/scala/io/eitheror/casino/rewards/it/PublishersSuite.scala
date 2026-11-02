package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward.*
import io.eitheror.casino.rewards.reward.RoundSettled as RewardRound

import java.time.Instant
import java.util.UUID

/**
 * The publishers wired (SC-004): a grant driven through its life is each transition once on `casino.rewards`, with
 * its source, code and tag and a `ce-id` of `<reward>/<seq>`; a transition applied twice is one fact; an operator's
 * activation and revoke reach `platform.audit`, the reason on neither topic.
 */
class PublishersSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  test("an operator's award is bonus_granted and audited; its revoke bonus_cancelled and audited, the reason named only"):
    val id = s"pub-${UUID.randomUUID().toString.take(8)}"
    val at = Instant.now()
    val o  = Opening("pub-p", "o-1", "Welcome", "DEPOSIT_MATCH", PayloadType.BonusFunds, Decision(5000L, 0L, 0L, 0L, Currency.unsafe("EUR")),
      source = Source.OperatorManual, by = Some("op-9"))
    val reward = kit.components.forEventSourcedEntity(EntityId(id))
    reward.call(Reward.open).invoke(Open(o, RewardStatus.Available, at)): Unit
    reward.call(Reward.activate).invoke(Activate(Some(WalletAward(None, 0L)), at)): Unit
    reward.call(Reward.revoke).invoke(Revoke(RevokeMode.Forfeit, "op-9", Some(Personal("phoned in")), 0L, at)): Unit
    val granted = kit.eventually("bonus_granted")(kit.facts(CasinoRewards).collectFirst { case g: BonusGranted if g.bonusId == id => g })
    assertEquals((granted.playerId, granted.source), ("pub-p", Some("operator_manual")))
    kit.eventually("bonus_cancelled")(kit.facts(CasinoRewards).collectFirst { case c: BonusCancelled if c.bonusId == id => c }): Unit
    val audits = kit.eventually("two audit records")(
      Option(kit.facts(PlatformAudit).collect { case r: AuditRecord if r.subjectId == id => r.action }).filter(_.size == 2))
    assertEquals(audits.toSet, Set("rewards.awarded", "rewards.revoked"))
    assert(!(kit.factTexts(CasinoRewards) ++ kit.factTexts(PlatformAudit)).exists(_.contains("phoned in")))

  private def facts = RewardFacts(kit)

  /** The reward's facts once `last` is published, and a second for anything late to arrive. */
  private def settled(id: String, last: String): Vector[RewardFact] =
    facts.awaitFamily(id, last): Unit
    Thread.sleep(1000)
    facts.of(id)

  private def assertOnce(id: String, all: Vector[RewardFact], families: Vector[String], o: Opening): Unit =
    assertEquals(all.map(_.family), families, all.toString)
    all.foreach(f => assertEquals(RewardFacts.origin(f.fact), (Some(o.source.wire), o.code, o.tag), f.toString))
    all.foreach(f => assertEquals(f.fact.key, o.playerId))
    val ids = all.flatMap(_.ceId)
    assertEquals(ids.size, all.size, all.toString)
    assertEquals(ids.distinct, ids)
    assert(ids.forall(_.matches(s"\\Q$id\\E/\\d+")), ids.toString)

  test("a grant of funds through its life: granted and completed once each with source, code and tag; nothing for the rest"):
    val at = kit.now()
    val o  = facts.funds(s"pub-${UUID.randomUUID().toString.take(8)}")
    val id = facts.open(o, RewardStatus.Available)
    val r  = facts.reward(id)
    r.call(Reward.select).invoke(Select(at)): Unit
    r.call(Reward.deselect).invoke(Deselect(at)): Unit
    r.call(Reward.select).invoke(Select(at)): Unit
    r.call(Reward.refuseAward).invoke(RefuseAward("BONUS_ALREADY_ACTIVE", at)): Unit
    r.call(Reward.activate).invoke(Activate(Some(WalletAward(Some(id), 10000L)), at)): Unit
    r.call(Reward.switch).invoke(Switch(1, Some(5L), at)): Unit
    r.call(Reward.convert).invoke(Convert(at)): Unit
    // The close after a conversion is the same completion (research R11): nothing more is published.
    r.call(Reward.close).invoke(Close(CloseReason.CapExpired, at)): Unit
    assertEquals(r.call(Reward.get).invoke().status, RewardStatus.Closed)
    assertOnce(id, settled(id, "bonus_completed"), Vector("bonus_granted", "bonus_completed"), o)

  test("a grant of free rounds through its life: granted, settled and completed once each; a late round settled again"):
    val at = kit.now()
    val o  = facts.freeRounds(s"pub-${UUID.randomUUID().toString.take(8)}", count = 2)
    val id = facts.open(o, RewardStatus.Available)
    val r  = facts.reward(id)
    r.call(Reward.activate).invoke(Activate(None, at)): Unit
    r.call(Reward.providerStatus).invoke(ProviderStatus("active", at)): Unit
    r.call(Reward.roundSettled).invoke(RewardRound("rd-1", 100L, at)): Unit
    r.call(Reward.roundSettled).invoke(RewardRound("rd-1", 100L, at)): Unit
    r.call(Reward.roundSettled).invoke(RewardRound("rd-2", 50L, at)): Unit
    facts.awaitFamily(id, "bonus_completed"): Unit
    r.call(Reward.roundSettled).invoke(RewardRound("rd-3", 30L, at)): Unit
    r.call(Reward.roundSettled).invoke(RewardRound("rd-3", 30L, at)): Unit
    val all = kit.eventually("the late round")(Some(facts.of(id)).filter(_.count(_.family == "free_spins_settled") == 2))
    Thread.sleep(1000)
    assertOnce(id, facts.of(id), Vector("free_spins_granted", "free_spins_settled", "bonus_completed", "free_spins_settled"), o)
    val paid = all.collect { case RewardFact(s: FreeSpinsSettled, _) => s.winCents.value }
    assertEquals(paid, Vector(150L, 30L))

  test("a transition applied twice publishes one fact"):
    val at = kit.now()
    val o  = facts.funds(s"pub-${UUID.randomUUID().toString.take(8)}")
    val id = facts.open(o, RewardStatus.Active)
    val r  = facts.reward(id)
    r.call(Reward.activate).invoke(Activate(Some(WalletAward(Some(id), 10000L)), at)): Unit
    r.call(Reward.forfeit).invoke(Forfeit(ForfeitReason.BonusExhausted, Some(1000L), at)): Unit
    r.call(Reward.forfeit).invoke(Forfeit(ForfeitReason.BonusExhausted, Some(1000L), at)): Unit
    assertOnce(id, settled(id, "bonus_forfeited"), Vector("bonus_granted", "bonus_forfeited"), o)
