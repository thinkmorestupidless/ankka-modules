package io.eitheror.casino.players.facts

import com.thinkmorestupidless.ankka.testkit.ConsumerTestKit
import io.eitheror.casino.contract.facts.{Registration as _, VipTierChanged as VipTierFact, GameSessionEnded as GameSessionEndedFact, *}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.{PlayersRuntime, Settings}
import io.eitheror.casino.players.fraud.FraudCaseEvent
import io.eitheror.casino.players.inbox.{MessageEvent, MessageOrigin, NoteState}
import io.eitheror.casino.players.player.*
import io.eitheror.casino.players.player.PlayerEvent.*
import io.eitheror.casino.players.sessions.{GameSessionEvent, PlayerSessionEvent}

import java.time.Instant

/** contracts/facts.md's three tables, event in and fact out (T017). */
class PublishersSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-25T10:00:00Z")

  override def beforeAll(): Unit = PlayersRuntime.wire(Settings(brand = "goldvibe"), () => at)

  private val profile = Profile(Map("firstName" -> Personal("Ada"), "lastName" -> Personal("Lovelace"), "country" -> Personal("GB"),
    "dateOfBirth" -> Personal("1990-01-02")))
  private val registration = Registration(Some("a_1b_2c_"), Some("click-1"), Some("WELCOME"))

  private def players(event: PlayerEvent, seq: Long = 7) =
    ConsumerTestKit.of(PlayersPublisher).onMessage(event, subject = "p-1", sequenceNumber = seq)

  test("each published player event is one fact, keyed and subjected by the player, its ce-id the event's id") {
    val cases: Vector[(PlayerEvent, CasinoPlayersFact)] = Vector(
      Registered("p-1", profile, Personal("a@b.test"), "EUR", Consents(), registration, at) ->
        PlayerRegistered("p-1", at, Some("a_1b_2c_"), Some("click-1"), Some("WELCOME"), Some("GB"), Some("EUR"), None, at),
      Suspended(Status.Active, "review", Some("op-1"), at) -> PlayerSuspended("p-1", "active", "suspended", "operator", Some("op-1"), Some("review"), at),
      Reinstated(Some("op-1"), Some("cleared"), at) -> PlayerReinstated("p-1", "suspended", "active", "operator", Some("op-1"), Some("cleared"), at),
      SelfExcluded(Status.Active, Some(at.plusSeconds(60)), at) ->
        PlayerSelfExcluded("p-1", "active", "self_excluded", "system", None, None, at, Some(at.plusSeconds(60)), at),
      Closed(Status.Active, Actor.Player, Some("p-1"), "player_request", at) ->
        PlayerClosed("p-1", "active", "closed", "player", Some("p-1"), Some("player_request"), at),
      Reopened("op-1", "asked", at) -> PlayerReopened("p-1", "closed", "active", "operator", Some("op-1"), Some("asked"), at),
      VipTierChanged(Some("silver"), "gold", Some("op-1"), at) -> VipTierFact("p-1", Some("silver"), "gold", "op-1", at),
      ConsentChanged("messages", false, "operator", Some("op-1"), at) -> PlayerConsentChanged("p-1", "messages", false, "operator", at),
      DuplicateFlagged("c-1", Set("p-2"), Set("identity", "device"), at) -> PlayerFraudFlagged("p-1", "c-1", "device,identity", at),
      DuplicateCleared("c-1", Some("op-1"), at) -> PlayerFraudCleared("p-1", "c-1", at)
    )
    cases.foreach { (event, fact) =>
      val out = players(event)
      assertEquals(out.payloads, Vector(fact), event.toString)
      assertEquals(out.recordKeys, Vector(Some("p-1")), event.toString)
      assertEquals(out.messages.head.metadata.get("ce-id"), Some("player/p-1/7"), event.toString)
    }
  }

  test("the same event twice is the same ce-id") {
    val e = Suspended(Status.Active, "r", None, at)
    assertEquals(players(e).messages.head.metadata.get("ce-id"), players(e).messages.head.metadata.get("ce-id"))
  }

  test("a profile edit names the changed fields and carries every completeness field, personal ones through the marker") {
    val out = players(ProfileEdited(profile, Set("firstName", "country"), at))
    assertEquals(out.payloads, Vector(PlayerProfileUpdated("p-1", Vector("country", "firstName"), Some(Personal("Ada")), Some(Personal("Lovelace")),
      Some(Personal("1990-01-02")), None, None, None, None, Some("GB"), at)))
    val dob = players(DateOfBirthCorrected(Personal("1990-01-03"), "op-1", "typo", at)).payloads.head.asInstanceOf[PlayerProfileUpdated]
    assertEquals((dob.changedFields, dob.dateOfBirth.map(_.reveal)), (Vector("dateOfBirth"), Some("1990-01-03")))
  }

  test("the events contracts/facts.md does not publish produce nothing") {
    Seq[PlayerEvent](EmailVerified(at), EmailAdded(Personal("x@y.test"), at), CookieDecisionRecorded(Personal("d"), CookieDecision(true, true, "v", "t", at), at),
      FavouriteAdded("g-1", at), Erased(at), StatusImported(Status.Blocked, at), SignedIn(at), KycVerified(at))
      .foreach(e => assertEquals(players(e).messages, Vector.empty, e.toString))
  }

  test("messages and logins publish on casino.players; sessions on casino.sessions with the personal fields marked") {
    val msg = ConsumerTestKit.of(MessagesPublisher)
    assertEquals(msg.onMessage(MessageEvent.Created("p-1", "bonus", MessageOrigin.Operator, Personal("s"), Personal("b"), None, at), "m-1", 1).payloads,
      Vector(PlayerMessageCreated("p-1", "m-1", "bonus", at)))
    assertEquals(msg.onMessage(MessageEvent.Read(at, "p-1"), "m-1", 2).payloads, Vector(PlayerMessageRead("p-1", "m-1", at)))
    assertEquals(msg.onMessage(MessageEvent.Deleted(at, "p-1"), "m-1", 3).payloads, Vector(PlayerMessageDeleted("p-1", "m-1", at)))
    assertEquals(msg.onMessage(MessageEvent.Delivered(at), "m-1", 4).messages, Vector.empty)

    val opened = PlayerSessionEvent.Opened("p-1", "rs-1", Personal("192.0.2.44"), Personal("ua"), Personal("fp"), at)
    val ended  = PlayerSessionEvent.Ended("forced", at, "p-1")
    assertEquals(ConsumerTestKit.of(LoginsPublisher).onMessage(opened, "ps-1", 1).payloads, Vector(PlayerLoggedIn("p-1", "ps-1", at)))
    assertEquals(ConsumerTestKit.of(LoginsPublisher).onMessage(ended, "ps-1", 2).payloads, Vector(PlayerLoggedOut("p-1", "ps-1", "forced", at)))
    val started = ConsumerTestKit.of(PlayerSessionsPublisher).onMessage(opened, "ps-1", 1)
    assertEquals(started.payloads, Vector(PlayerSessionStarted("p-1", "ps-1", Personal("192.0.2.44"), Personal("ua"), at)))
    assertEquals(started.recordKeys, Vector(Some("p-1")))
    assertEquals(ConsumerTestKit.of(PlayerSessionsPublisher).onMessage(ended, "ps-1", 2).payloads, Vector(PlayerSessionEnded("p-1", "ps-1", "forced", at)))
    val games = ConsumerTestKit.of(GameSessionsPublisher)
    assertEquals(games.onMessage(GameSessionEvent.Opened("p-1", "ps-1", "g-1", "EUR", false, at), "gs-1", 1).payloads,
      Vector(GameSessionStarted("p-1", "gs-1", "g-1", "ps-1", at)))
    assertEquals(games.onMessage(GameSessionEvent.Ended("limit", at, "p-1", "g-1", 17), "gs-1", 2).payloads,
      Vector(GameSessionEndedFact("p-1", "gs-1", "g-1", 17, at, Some("limit"))))
  }

  test("an audit record per operator action, no personal value, the field named for a date of birth, a note or a message") {
    val audit = ConsumerTestKit.of(AuditPublisher)
    val suspend = audit.onMessage(Suspended(Status.Active, "review", Some("op-1"), at), "p-1", 3).payloads.head.asInstanceOf[AuditRecord]
    assertEquals((suspend.action, suspend.actorId, suspend.subjectId, suspend.reasonCode, suspend.brand, suspend.service),
      ("suspend", "op-1", "p-1", Some("review"), "goldvibe", "players"))
    val dob = audit.onMessage(DateOfBirthCorrected(Personal("1990-01-03"), "op-1", "typo", at), "p-1", 4)
    assertEquals(dob.payloads.head.asInstanceOf[AuditRecord].changes, Vector(AuditChange("dateOfBirth", None, None)))
    assert(!dob.messages.head.text.contains("1990-01-03"), dob.messages.head.text)
    assertEquals(audit.onMessage(Closed(Status.Active, Actor.Player, Some("p-1"), "x", at), "p-1", 5).messages, Vector.empty,
      "a player's own close is not an operator action")

    val note = ConsumerTestKit.of(NoteAudit).onMessage(NoteState("p-1", Personal("secret text"), "op-1", Some(at)), "n-1", 1)
    assertEquals(note.payloads.head.asInstanceOf[AuditRecord].action, "note")
    assert(!note.messages.head.text.contains("secret text"))
    val message = ConsumerTestKit.of(MessageAudit)
      .onMessage(MessageEvent.Created("p-1", "c", MessageOrigin.Operator, Personal("subj"), Personal("the body"), Some("op-1"), at), "m-1", 1)
    assert(!message.messages.head.text.contains("the body"))
    assertEquals(ConsumerTestKit.of(MessageAudit).onMessage(MessageEvent.Created("p-1", "c", MessageOrigin.Crm, Personal("s"), Personal("b"), None, at), "m-2", 1).messages,
      Vector.empty)
    assertEquals(ConsumerTestKit.of(FraudCaseAudit).onMessage(FraudCaseEvent.Reviewed("op-1", at), "c-1", 2).payloads.head.asInstanceOf[AuditRecord].action,
      "fraud-review")
  }
