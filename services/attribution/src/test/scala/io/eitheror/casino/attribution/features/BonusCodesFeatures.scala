package io.eitheror.casino.attribution.features

import io.eitheror.casino.attribution.Binding
import io.eitheror.casino.attribution.record.Record
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.{AttributionFact, BonusCodeEntered, CasinoRewards, Deposit, PaymentCaptured, PaymentsPayments,
  PlayerAttributionFact, Registration}
import io.eitheror.casino.contract.money.{Currency, MinorUnits}

import java.time.{Duration as JDuration, Instant}

/** features/attribution/bonus-codes.feature (US2): a code binds as a tag would, at registration or within its window. */
class BonusCodesFeatures extends AttributionFeatures("bonus-codes.feature"):

  private var code: String   = null
  private var link: String   = null
  private var player: String = null
  private var entry: Instant = null
  private var bound: Option[Binding] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    code = null; link = null; player = null; entry = null; bound = None

  private def list(affiliate: String): Unit =
    code = id("CODE").toUpperCase
    link = id(s"lnk-$affiliate")
    kit.network.codes = kit.network.codes.updated(code, "offer" -> kit.network.mint(affiliate, link))
    kit.network.codesVersion += 1
    kit.moveClock(kit.now.plusSeconds(kit.network.cacheSeconds + 1))
    kit.refreshDocuments(): Unit

  private def record: Record.State = kit.eventually("the record")(Some(kit.record(player)).filter(_.registeredAt.isDefined))
  private def binding: Binding     = kit.eventually("bound")(kit.record(player).binding)
  private def pseudonym = Pseudonyms.derive(Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName("programme")).get), player)
  private def facts: Vector[AttributionFact] =
    kit.factsOn("programme").collect { case f: PlayerAttributionFact if f.pseudonym == pseudonym => f }

  Given("a network the brand knows") { () => assert(kit.network.id == "programme") }

  Given("a bonus code the network issued to an affiliate") { () => list("aff-7") }
  When("a visitor registers with the bonus code and no tag") { () => player = kit.bindPlayer(None, bonusCode = Some(code)) }
  Then("the player is bound to that affiliate and link as a tag would bind them, with no click id") { () =>
    val b = binding
    assertEquals((b.affiliate, b.link, b.clickId, b.touch), ("aff-7", link, None, Binding.RegistrationCode))
  }
  Then("a {string} fact is emitted") { (kind: String) =>
    assertEquals(kind, "registration")
    kit.eventually("the registration")(facts.collectFirst { case r: Registration => r })
  }

  Given("a player who registered organically") { () =>
    player = kit.bindPlayer(None, at = kit.now.minus(JDuration.ofDays(3)))
    assertEquals(record.binding, None)
  }
  When("they enter an affiliate's bonus code within the bonus code window counted from registration") { () =>
    list("aff-8")
    entry = kit.now
    kit.publish(CasinoRewards, BonusCodeEntered(player, code, entry))
  }
  Then("the player is bound to that affiliate") { () =>
    assertEquals((binding.affiliate, binding.touch), ("aff-8", Binding.LaterCode))
  }
  Then("a {string} fact is emitted dated to the binding") { (kind: String) =>
    assertEquals(kind, "registration")
    val r = kit.eventually("the registration")(facts.collectFirst { case r: Registration => r })
    assertEquals(r.occurredAt, entry)
  }
  Then("the player's later facts are attributed") { () =>
    val pay = id("pay")
    kit.publish(PaymentsPayments, PaymentCaptured(pay, player, "psp", MinorUnits.unsafe(2500), Currency.unsafe("EUR"), kit.now))
    val d = kit.eventually("the deposit")(facts.collectFirst { case d: Deposit if d.reference == pay => d })
    assertEquals((d.affiliate, d.link), ("aff-8", link))
  }

  Given("a player already bound to one affiliate") { () =>
    player = kit.bindPlayer(Some(kit.network.mint("aff-1", id("first-lnk"))))
    bound = Some(binding)
  }
  When("they enter another affiliate's bonus code") { () =>
    list("aff-9")
    kit.publish(CasinoRewards, BonusCodeEntered(player, code, kit.now))
  }
  Then("the code's reward applies as the brand's reward rules say") { () =>
    // The reward is `rewards`' to apply; attribution neither grants nor refuses it.
    kit.eventually("the code's touch")(Some(kit.record(player)).filter(_.touches.nonEmpty))
  }
  Then("the binding does not change") { () => assertEquals(kit.record(player).binding, bound) }
