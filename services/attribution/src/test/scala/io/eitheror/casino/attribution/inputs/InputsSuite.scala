package io.eitheror.casino.attribution.inputs

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, InMemorySecretStore, LogCapturing}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits}

import java.time.Instant

/**
 * The shells of the five input consumers (T023): a family a consumer does not handle, or a fact it cannot
 * read, is ignored and calls nothing. The kit's client refuses every call, so a call would fail the test.
 */
class InputsSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-10-25T10:00:00Z")
  private val eur = Currency.unsafe("EUR")
  private val ten = MinorUnits.unsafe(1000)
  private def secrets =
    val s = InMemorySecretStore()
    s.put("network/programme/pseudonym-key", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
    s

  test("PlayersFacts ignores a login and an unreadable fact") {
    val kit = ConsumerTestKit.of(PlayersFacts, secrets = secrets)
    assertEquals(kit.onMessage(Some(PlayerLoggedIn("p-1", "s-1", at))).effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
  }

  test("RewardsFacts ignores a grant and an unreadable fact") {
    val kit = ConsumerTestKit.of(RewardsFacts, secrets = secrets)
    assertEquals(kit.onMessage(Some(BonusCompleted("p-1", "b-1", "Welcome", "o-1", "deposit_match", at))).effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
  }

  test("PaymentsFacts ignores an initiated payment and an unreadable fact") {
    val kit = ConsumerTestKit.of(PaymentsFacts, secrets = secrets)
    assertEquals(kit.onMessage(Some(PaymentInitiated("pay-1", "p-1", "psp", "t-1", ten, eur, at))).effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
  }

  test("WithdrawalsFacts ignores approved and cancelled") {
    val kit = ConsumerTestKit.of(WithdrawalsFacts, secrets = secrets)
    assertEquals(kit.onMessage(Some(WithdrawalApproved("p-1", "w-1", ten, eur, "staff-1", at))).effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(Some(WithdrawalCancelled("p-1", "w-1", ten, eur, "p-1", at))).effect, ConsumerEffect.Ignore)
  }

  test("MoneyFacts skips an unreadable fact") {
    assertEquals(ConsumerTestKit.of(MoneyFacts, secrets = secrets).onMessage(None).effect, ConsumerEffect.Ignore)
  }
