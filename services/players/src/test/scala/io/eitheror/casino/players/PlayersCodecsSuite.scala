package io.eitheror.casino.players

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.thinkmorestupidless.ankka.core.Serializer
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.config.*
import io.eitheror.casino.players.fraud.*
import io.eitheror.casino.players.inbox.*
import io.eitheror.casino.players.player.*
import io.eitheror.casino.players.registration.*
import io.eitheror.casino.players.sessions.*

import java.time.Instant

/** Every stored type round-trips under its manifest; a `Personal` field is written by the marker's codec (T006). */
class PlayersCodecsSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-25T10:00:15.123Z")

  private def roundTrip[A](a: A, s: Serializer[A]): Unit = assertEquals(s.fromBytes(s.toBytes(a)), a)

  private val player = PlayerState(
    playerId = "p-1", status = Status.Active,
    trail = Vector(AccountEvent("registered", "", Status.Pending, Actor.Player, Some("p-1"), None, at)),
    profile = Profile(Map("firstName" -> Personal("Ada"), "dateOfBirth" -> Personal("1990-01-02"))), locked = Set("dateOfBirth"),
    currency = "EUR", emails = Vector(EmailAddress(Personal("ada@example.test"), verified = true, primary = true, addedAt = at)),
    consents = Consents(Consent(true, Some(at), Some("registration"))),
    cookieDecisions = Map("d-1" -> CookieDecision(true, false, "v1", "t", at)), favourites = Set("g-1"), vipTier = Some("gold"),
    fraud = Some(FraudFlag(Vector("c-1"), Set("p-2"), Set("identity"), at)), sessionLimitMinutes = Some(60),
    todaysGameSessions = Vector(DaySession("gs-1", at, Some(at))), openPlayerSessions = Set("ps-1"),
    registration = Registration(Some("a_1"), None, Some("BONUS"), Some(Personal("192.0.2.44")), Some("GB"), Some(Personal("fp"))),
    verificationEmail = VerificationEmail(Some(at)), suspension = Some(Suspension("r", at)), closedAt = Some(at),
    erasure = Some(ErasureFiling("corr", at)), withdrawalsOpen = Set("w-1"))

  test("the player's state and every event round-trip") {
    roundTrip(player, PlayerEntity.stateSerializer)
    import PlayerEvent.*
    Seq[PlayerEvent](
      Registered("p-1", player.profile, Personal("ada@example.test"), "EUR", player.consents, player.registration, at),
      EmailVerified(at), VerificationEmailSent(at), SignInRefused(Status.Suspended, at), GameSessionOpened("gs-1", at),
      SessionLimitSet(None, at), ProfileEdited(player.profile, Set("firstName"), at),
      DateOfBirthCorrected(Personal("1990-01-03"), "op-1", "typo", at), EmailChanged(Personal("b@example.test"), at),
      ConsentChanged("sms", true, "player", None, at), CookieDecisionRecorded(Personal("d-1"), CookieDecision(true, true, "v", "t", at), at),
      Suspended(Status.Active, "r", Some("op-1"), at), Closed(Status.Active, Actor.Player, None, "x", at),
      VipTierChanged(None, "gold", None, at), DuplicateFlagged("c-1", Set("p-2"), Set("device"), at),
      ErasureFiled("corr", at, at), Erased(at), StatusImported(Status.Blocked, at)
    ).foreach(e => roundTrip(e, PlayerEntity.eventSerializer))
  }

  test("the other entities' states and events round-trip") {
    roundTrip(PlayerSessionState("p-1", "rs-1", Some(at), None, Personal("192.0.2.44"), Personal("ua"), Personal("fp"), "active",
      Vector("gs-1")), PlayerSessionEntity.stateSerializer)
    roundTrip(PlayerSessionEvent.Ended("forced", at), PlayerSessionEntity.eventSerializer)
    roundTrip(GameSessionState("p-1", "ps-1", "g-1", "EUR", demo = true, Some(at), Some(at), Some(3), "ended", Some("limit")),
      GameSessionEntity.stateSerializer)
    roundTrip(GameSessionEvent.Opened("p-1", "ps-1", "g-1", "EUR", false, at), GameSessionEntity.eventSerializer)
    roundTrip(MessageState("p-1", "bonus", "crm", Personal("s"), Personal("b"), Some(at), None, Some(at), None, true),
      MessageEntity.stateSerializer)
    roundTrip(MessageEvent.Created("p-1", "c", "operator", Personal("s"), Personal("b"), Some("op-1"), at), MessageEntity.eventSerializer)
    roundTrip(NoteState("p-1", Personal("text"), "op-1", Some(at), pinned = true), NoteEntity.stateSerializer)
    roundTrip(FraudCaseState(Set("p-1"), Set("identity"), "open", Some(at)), FraudCaseEntity.stateSerializer)
    roundTrip(FraudCaseEvent.Dismissed("op-1", at), FraudCaseEntity.eventSerializer)
    roundTrip(FraudCaseEvent.Dismissed("op-1", at, Personal("notes")), FraudCaseEntity.eventSerializer)
    roundTrip(FraudCaseEvent.NotesErased(at), FraudCaseEntity.eventSerializer)
    roundTrip(LookupIndexState(Set("p-1", "p-2")), LookupIndexEntity.stateSerializer)
    roundTrip(BrandConfig("Gold", features = Map("x" -> true), retentionDays = Some(30)), BrandConfigEntity.stateSerializer)
    roundTrip(CurrencyConfig(Vector("EUR"), Some("EUR")), CurrencyConfigEntity.stateSerializer)
    roundTrip(AllowList(Set("GB")), AllowListEntity.stateSerializer)
  }

  test("a Personal field is written as the marker's codec writes it, and a time as ISO-8601") {
    val text = String(PlayerEntity.stateSerializer.toBytes(player), "UTF-8")
    val marked = writeToString(Personal("ada@example.test"))(using Personal.personalText)
    assert(text.contains(s"\"address\":$marked"), text)
    assert(text.contains("\"addedAt\":\"2026-10-25T10:00:15.123Z\""), text)
    assertEquals(PlayerEntity.stateSerializer.manifest, "players.player-state")
  }
