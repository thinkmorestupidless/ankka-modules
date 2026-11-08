package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, EventSourcedTestKit}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal as Marked
import io.eitheror.casino.reporting.{Recorded, Settings, TestKeyring}
import io.eitheror.casino.reporting.Facts.*
import io.eitheror.casino.reporting.intake.PlayerLogEvent.*

import java.nio.charset.StandardCharsets.UTF_8

/** T022: `PlayerLog`'s folds of status and KYC, the email as `Personal`, the test flag, RG and KYC documents; `players-facts`. */
class PlayerLogSuite extends munit.FunSuite:
  private def kit = EventSourcedTestKit.of(PlayerLog, PlayerLog.id(1, "p-1"))
  private def record(k: EventSourcedTestKit[PlayerLog, PlayerLogState, PlayerLogEvent], fact: f.CasinoPlayersFact) =
    k.call(PlayerLog.record)(PlayerFact(FactId.of(f.CasinoPlayers, fact), fact))

  private def registered(test: Option[Boolean] = None) = f.PlayerRegistered("p-1", at, None, None, None, Some("ZA"), Some("EUR"), None, at,
    email = Some(Marked("ada@example.com")), givenName = Some(Marked("Ada")), testAccount = test)

  test("a registration: when, where, the currency, the email under player/p-1, the test flag") {
    TestKeyring.around { _ =>
      val k = kit
      record(k, registered(Some(true))): Unit
      val s = k.currentState
      assertEquals((s.registeredAt, s.country, s.currency, s.testAccount, s.status, s.kycStatus), (Some(at), Some("ZA"), Some("EUR"), true, "active", "unverified"))
      assertEquals(Personals.reveal(s.email), Some("ada@example.com"))
      assertEquals(s.email.map(_.subject.value), Some("player/p-1"))
    }
  }

  test("the email is sealed in the stored event; no other personal value is kept") {
    TestKeyring.around { _ =>
      val event = record(kit, registered()).events.head
      val bytes = String(PlayerLog.eventSerializer.toBytes(event), UTF_8)
      assert(!bytes.contains("ada@example.com"), bytes)
      assert(!bytes.contains("Ada"), bytes)
    }
  }

  test("status: the last new_value by time; an older fact changes nothing") {
    val k = kit
    record(k, f.PlayerSuspended("p-1", "active", "suspended", "operator", Some("staff-1"), Some("aml"), at)): Unit
    assertEquals(k.currentState.status, "suspended")
    record(k, f.PlayerReinstated("p-1", "suspended", "active", "operator", None, None, at.minusSeconds(60))): Unit
    assertEquals(k.currentState.status, "suspended")
    val selfEx = f.PlayerSelfExcluded("p-1", "suspended", "self_excluded", "player", None, None, at.plusSeconds(60), Some(at.plusSeconds(86400)), at.plusSeconds(60))
    val e = record(k, selfEx).events.head.asInstanceOf[StatusChanged]
    assertEquals((e.family, e.from, e.to, e.until), ("player_self_excluded", "suspended", "self_excluded", Some(at.plusSeconds(86400))))
    record(k, f.PlayerClosed("p-1", "self_excluded", "closed", "operator", None, None, at.plusSeconds(120))): Unit
    record(k, f.PlayerReopened("p-1", "closed", "active", "operator", None, None, at.plusSeconds(180))): Unit
    assertEquals(k.currentState.status, "active")
  }

  test("KYC: the last of verified, rejected and pending; a document's decision with its submission's time") {
    val k = kit
    record(k, f.PlayerKycPending("p-1", "unverified", "pending", at)): Unit
    assertEquals(k.currentState.kycStatus, "pending")
    record(k, f.PlayerKycSubmitted("p-1", "doc-1", "identity", at.plusSeconds(10))): Unit
    assertEquals(k.currentState.submissions.keySet, Set("doc-1"))
    val d = record(k, f.PlayerKycDocumentReviewed("p-1", "doc-1", "identity", "approved", None, "staff-1", at.plusSeconds(99))).events.head
    assertEquals(d, KycDocumentDecided(d.factId, "p-1", "doc-1", "identity", Some(at.plusSeconds(10)), at.plusSeconds(99), "approved", None))
    assertEquals(k.currentState.submissions, Map.empty)
    record(k, f.PlayerKycVerified("p-1", "pending", "verified", "operator", None, None, at.plusSeconds(100))): Unit
    assertEquals(k.currentState.kycStatus, "verified")
    val unseen = record(k, f.PlayerKycDocumentReviewed("p-1", "doc-9", "address", "rejected", Some("blurred"), "staff-1", at.plusSeconds(200))).events.head
    assertEquals(unseen.asInstanceOf[KycDocumentDecided].submitted, None)
  }

  test("the test flag changes; a login is the last login") {
    val k = kit
    record(k, registered()): Unit
    record(k, f.PlayerTestAccountChanged("p-1", testAccount = true, "staff-1", at.plusSeconds(5))): Unit
    assert(k.call(PlayerLog.testAccount).replyValue)
    record(k, f.PlayerLoggedIn("p-1", "s-2", at.plusSeconds(50))): Unit
    record(k, f.PlayerLoggedIn("p-1", "s-1", at.plusSeconds(20))): Unit
    assertEquals(k.currentState.lastLoginAt, Some(at.plusSeconds(50)))
  }

  test("an email change replaces the email") {
    TestKeyring.around { _ =>
      val k = kit
      record(k, registered()): Unit
      record(k, f.PlayerEmailChanged("p-1", Marked("ada@lovelace.dev"), at.plusSeconds(5))): Unit
      assertEquals(Personals.reveal(k.currentState.email), Some("ada@lovelace.dev"))
    }
  }

  test("RG cases and limits are recorded as events; a family the reports do not read records none; a repeat none") {
    val k = kit
    val opened = f.RgCaseOpened("p-1", "rg-1", "cool_off", "player", Some(at.plusSeconds(86400)), at)
    assertEquals(record(k, opened).events.map(_.getClass.getSimpleName), Vector("RgCaseOpened"))
    assertEquals(record(k, f.RgCaseClosed("p-1", "rg-1", "cool_off", "expired", at.plusSeconds(86400))).events.map(_.getClass.getSimpleName), Vector("RgCaseClosed"))
    assertEquals(record(k, f.RgLimitApplied("p-1", "deposit", Some("daily"), Some(5000L), "player", at, Some("EUR"))).events.map(_.getClass.getSimpleName), Vector("RgLimitApplied"))
    assert(!record(k, f.PlayerConsentChanged("p-1", "email", consented = true, "registration", at)).persisted)
    assert(!record(k, opened).persisted)
  }

  test("players-facts sends each fact to its generation's player") {
    val r   = Recorded()
    val kit = ConsumerTestKit.of(PlayersFacts(Settings("goldvibe")), client = r.client)
    assertEquals(kit.onMessage(Some(f.PlayerLoggedIn("p-1", "s-1", at))).effect, ConsumerEffect.Done)
    assertEquals(r.calls.map(c => (c.component, c.entity, c.method)), Vector(("player-log", "g1/p-1", "record")))
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
  }
