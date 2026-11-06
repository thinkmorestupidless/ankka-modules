package io.eitheror.casino.engagement.profile

import com.thinkmorestupidless.ankka.core.Personal as Sealed
import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.engagement.{EngagementCodecs, TestKeyring}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate}

/** data-model.md's `Profile`: every family it reads, a repeat changing nothing, the reset and erasure. */
class ProfileSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-11-03T10:00:00Z")
  private val eur = Currency.unsafe("EUR")

  private def kit = KeyValueEntityTestKit.of(Profile, "p-1")

  private def registered(at: Instant = at) = f.PlayerRegistered("p-1", at, Some("aff-7"), None, None, Some("ZA"), Some("EUR"),
    Some("bronze"), at, givenName = Some(Personal("Ada")), familyName = Some(Personal("Lovelace")),
    dateOfBirth = Some(Personal("1990-01-02")), email = Some(Personal("ada@example.com")))

  private def deposit(tx: String, cents: Long, at: Instant) = f.DepositConfirmed("p-1", eur, tx, MinorUnits.unsafe(cents), s"ref-$tx", 1,
    SignedMinorUnits(5000L), MinorUnits.unsafe(700L), at)

  private def value(p: Option[Sealed[String]]) = ProfileState.reveal(p)

  test("a registration fills the profile, its personal fields sealed under the player") {
    TestKeyring.around { _ =>
      val r = kit.call(Profile.players)(PlayersFact(registered()))
      assert(r.replyValue.changed)
      val s = r.state
      assertEquals((s.playerId, s.registeredAt, s.country, s.currency, s.btag, s.vipTier),
        ("p-1", Some(at), Some("ZA"), Some("EUR"), Some("aff-7"), Some("bronze")))
      assertEquals((value(s.email), value(s.givenName), value(s.familyName), value(s.dateOfBirth)),
        (Some("ada@example.com"), Some("Ada"), Some("Lovelace"), Some("1990-01-02")))
      assertEquals(s.email.map(_.subject.value), Some("player/p-1"))
    }
  }

  test("the stored state holds no personal value in the clear") {
    TestKeyring.around { _ =>
      val s     = kit.call(Profile.players)(PlayersFact(registered())).state
      val bytes = new String(EngagementCodecs.serializer[ProfileState]("profile-state").toBytes(s), UTF_8)
      for v <- Seq("ada@example.com", "Ada", "Lovelace", "1990-01-02") do assert(!bytes.contains(v), s"$v in $bytes")
      assert(bytes.contains("\"$personal\""), bytes)
    }
  }

  test("a repeated or older fact changes nothing") {
    TestKeyring.around { _ =>
      val k = kit
      k.call(Profile.players)(PlayersFact(registered())): Unit
      assert(!k.call(Profile.players)(PlayersFact(registered())).replyValue.changed)
      val tier = f.VipTierChanged("p-1", Some("bronze"), "gold", "op", at.plusSeconds(60))
      assert(k.call(Profile.players)(PlayersFact(tier)).replyValue.changed)
      assert(!k.call(Profile.players)(PlayersFact(tier.copy(newTier = "silver", occurredAt = at.plusSeconds(30)))).replyValue.changed)
      assertEquals(k.call(Profile.get).replyValue.vipTier, Some("gold"))
    }
  }

  test("status, KYC, duplicate, consent, cases and limits follow their families") {
    TestKeyring.around { _ =>
      val k = kit
      def apply(fact: f.CasinoPlayersFact) = k.call(Profile.players)(PlayersFact(fact)).state
      assertEquals(apply(f.PlayerClosed("p-1", "active", "closed", "op", None, None, at)).status, "closed")
      assertEquals(apply(f.PlayerReopened("p-1", "closed", "active", "op", None, None, at.plusSeconds(1))).status, "active")
      assertEquals(apply(f.PlayerKycVerified("p-1", "pending", "verified", "op", None, None, at)).kycStatus, Some("verified"))
      assert(apply(f.PlayerDuplicateCheckEnded("p-1", "weak", at)).suspectedDuplicate)
      assertEquals(apply(f.PlayerConsentChanged("p-1", "email", true, "registration", at)).consents.get("email"),
        Some(Consent(true, at, "registration")))
      assertEquals(apply(f.RgCaseOpened("p-1", "c-1", "cool_off", "player", Some(at.plusSeconds(86400)), at)).cases.keySet, Set("c-1"))
      assertEquals(apply(f.RgCaseClosed("p-1", "c-1", "cool_off", "expired", at.plusSeconds(10))).cases, Map.empty[String, RgCase])
      assertEquals(apply(f.RgLimitApplied("p-1", "DEPOSIT_DAILY", Some("daily"), Some(5000L), "set", at, Some("EUR"))).limits.keySet,
        Set("DEPOSIT_DAILY"))
      assertEquals(apply(f.RgLimitApplied("p-1", "DEPOSIT_DAILY", Some("daily"), None, "removed", at.plusSeconds(5))).limits,
        Map.empty[String, Limit])
    }
  }

  test("a deposit counts once by its transaction, in the lifetime and the day, and leaves the balances") {
    TestKeyring.around { _ =>
      val k  = kit
      val r1 = k.call(Profile.money)(MoneyFact(deposit("t-1", 1250, at)))
      assertEquals((r1.replyValue.depositCountBefore, r1.replyValue.depositCountAfter), (0L, 1L))
      assert(!k.call(Profile.money)(MoneyFact(deposit("t-1", 1250, at))).replyValue.changed)
      val s = k.call(Profile.money)(MoneyFact(deposit("t-2", 750, at.plusSeconds(60)))).state
      assertEquals((s.depositCount, s.totalDepositCents, s.dailyDepositCount, s.depositDay, s.lastDepositAt),
        (2L, 2000L, 2, Some(LocalDate.parse("2026-11-03")), Some(at.plusSeconds(60))))
      assertEquals((s.realCents, s.restrictedCents), (Some(5000L), Some(700L)))
    }
  }

  test("a deposit after the 03:00 boundary starts the next day's count") {
    TestKeyring.around { _ =>
      val k = kit
      k.call(Profile.money)(MoneyFact(deposit("t-1", 100, Instant.parse("2026-11-04T02:59:00Z")))): Unit
      val s = k.call(Profile.money)(MoneyFact(deposit("t-2", 100, Instant.parse("2026-11-04T03:01:00Z")))).state
      assertEquals((s.dailyDepositCount, s.depositDay), (1, Some(LocalDate.parse("2026-11-04"))))
    }
  }

  test("the reset takes a count above zero from an earlier day to 0, and nothing else") {
    TestKeyring.around { _ =>
      val k = kit
      k.call(Profile.money)(MoneyFact(deposit("t-1", 100, at))): Unit
      assert(!k.call(Profile.resetDepositDay)(LocalDate.parse("2026-11-03")).replyValue)
      val r = k.call(Profile.resetDepositDay)(LocalDate.parse("2026-11-04"))
      assert(r.replyValue)
      assertEquals((r.state.dailyDepositCount, r.state.depositDay), (0, Some(LocalDate.parse("2026-11-04"))))
      assert(!k.call(Profile.resetDepositDay)(LocalDate.parse("2026-11-05")).replyValue)
    }
  }

  test("a payment's start is remembered by its id, once") {
    TestKeyring.around { _ =>
      val k = kit
      val i = f.PaymentInitiated("pay-1", "p-1", "paycorp", "card", MinorUnits.unsafe(2500), eur, at)
      assertEquals(k.call(Profile.payments)(PaymentsFact(i)).replyValue.payment, Some(RecentPayment("pay-1", 2500, "EUR", "paycorp", "card")))
      assert(!k.call(Profile.payments)(PaymentsFact(i)).replyValue.changed)
      val failed = f.PaymentFailed("pay-1", "p-1", "paycorp", "05", "declined", at.plusSeconds(5))
      assertEquals(k.call(Profile.payments)(PaymentsFact(failed)).replyValue.payment.map(_.amountCents), Some(2500L))
    }
  }

  test("erasure takes every personal field and marks the profile") {
    TestKeyring.around { _ =>
      val k = kit
      k.call(Profile.players)(PlayersFact(registered())): Unit
      val s = k.call(Profile.erase).state
      assertEquals((s.email, s.givenName, s.familyName, s.dateOfBirth, s.erased), (None, None, None, None, true))
      assertEquals(s.country, Some("ZA"))
    }
  }
