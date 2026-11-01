package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward.*

import java.time.Instant
import java.util.UUID

/** The publishers wired: a reward's activation and revoke reach `casino.rewards` and `platform.audit`, the reason on neither. */
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
