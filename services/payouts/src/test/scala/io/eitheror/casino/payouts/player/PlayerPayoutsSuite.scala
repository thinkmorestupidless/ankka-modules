package io.eitheror.casino.payouts.player

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.limits.Counted
import io.eitheror.casino.payouts.player.PlayerEvent.*

import java.time.Instant

/** data-model.md's `PlayerPayouts` table, row by row. */
class PlayerPayoutsSuite extends munit.FunSuite:
  private val at   = Instant.parse("2026-10-15T12:00:00Z")
  private val Eur  = Currency.unsafe("EUR")
  private val Iban = "DE89370400440532013000"
  private type Kit = EventSourcedTestKit[PlayerPayouts, PlayerState, PlayerEvent]

  private def fresh: Kit = EventSourcedTestKit.of(PlayerPayouts, "p-1")
  private def named: Kit =
    val kit = fresh
    kit.call(PlayerPayouts.seeNames)(SeeNames(Personal("José"), Personal("Müller"), at)): Unit
    kit
  private def save(kit: Kit, id: String, default: Boolean = false, first: String = "jose", iban: String = Iban) =
    kit.call(PlayerPayouts.saveDestination)(SaveDestination(id, first, "MULLER", iban, "main", default, at))

  test("each KYC status is recorded, and the player is known; the same status again writes nothing"):
    val kit = fresh
    assertEquals(kit.currentState.known, false)
    val r = kit.call(PlayerPayouts.seeKyc)(SeeKyc(Kyc.Verified, at))
    assertEquals(r.events, Vector[PlayerEvent](KycSeen(Kyc.Verified, at)))
    assertEquals((kit.currentState.known, kit.currentState.kyc), (true, Kyc.Verified))
    assertEquals(kit.call(PlayerPayouts.seeKyc)(SeeKyc(Kyc.Verified, at)).events, Vector.empty)

  test("names, email and closure are recorded; repeated, nothing"):
    val kit = fresh
    kit.call(PlayerPayouts.seeNames)(SeeNames(Personal("Ana"), Personal("Silva"), at)): Unit
    kit.call(PlayerPayouts.seeEmail)(SeeEmail(Personal("ana@example.test"), at)): Unit
    kit.call(PlayerPayouts.seeClosed)(SeeClosed(at)): Unit
    assertEquals(kit.currentState.names, Some(ProfileName(Personal("Ana"), Personal("Silva"))))
    assertEquals(kit.currentState.email.map(_.reveal), Some("ana@example.test"))
    assertEquals(kit.currentState.closed, true)
    assertEquals(kit.call(PlayerPayouts.seeNames)(SeeNames(Personal("Ana"), Personal("Silva"), at)).events, Vector.empty)
    assertEquals(kit.call(PlayerPayouts.seeEmail)(SeeEmail(Personal("ana@example.test"), at)).events, Vector.empty)
    assertEquals(kit.call(PlayerPayouts.seeClosed)(SeeClosed(at)).events, Vector.empty)

  test("a deposit adds its gateway and scheme; a redelivery writes nothing; no scheme is recorded as none"):
    val kit = fresh
    assertEquals(kit.call(PlayerPayouts.seeDeposit)(SeeDeposit("UnityFinance", Some("CARD_VISA"), at)).events,
      Vector[PlayerEvent](DepositSeen("unityfinance", Some("CARD_VISA"))))
    assertEquals(kit.call(PlayerPayouts.seeDeposit)(SeeDeposit("unityfinance", Some("CARD_VISA"), at)).events, Vector.empty)
    kit.call(PlayerPayouts.seeDeposit)(SeeDeposit("cozeni", None, at)): Unit
    assertEquals(kit.currentState.deposits, Map("unityfinance" -> Set("CARD_VISA"), "cozeni" -> Set.empty[String]))

  test("SaveDestination: an unknown player is refused"):
    assertEquals(save(fresh, "d-1").replyValue.refusal, Some("PLAYER_NOT_FOUND"))

  test("SaveDestination: INVALID_IBAN before the profile, then PROFILE_INCOMPLETE, then the name match"):
    val unnamed = fresh
    unnamed.call(PlayerPayouts.seeKyc)(SeeKyc(Kyc.Verified, at)): Unit
    assertEquals(save(unnamed, "d-1", iban = "DE00370400440532013000").replyValue.refusal, Some("INVALID_IBAN"))
    assertEquals(save(unnamed, "d-1").replyValue.refusal, Some("PROFILE_INCOMPLETE"))
    assertEquals(save(named, "d-1", first = "Joseph").replyValue.refusal, Some("PAYOUT_DESTINATION_AML_BLOCKED"))

  test("SaveDestination: the first is the default whatever was asked; a later one only when asked, unsetting the other"):
    val kit = named
    assertEquals(save(kit, "d-1").replyValue.destination.map(_.default), Some(true))
    assertEquals(save(kit, "d-2").replyValue.destination.map(_.default), Some(false))
    save(kit, "d-3", default = true): Unit
    assertEquals(kit.currentState.destinations.map(d => d.id -> d.default), Vector("d-1" -> false, "d-2" -> false, "d-3" -> true))
    assertEquals(kit.currentState.destination("d-1").map(_.iban.reveal), Some(Iban))

  test("SaveDestination repeated under its id answers the saved destination and writes nothing"):
    val kit = named
    save(kit, "d-1"): Unit
    val again = save(kit, "d-1", first = "someone else")
    assertEquals((again.events.toVector, again.replyValue.refusal, again.replyValue.destination.map(_.id)), (Vector.empty[PlayerEvent], None, Some("d-1")))

  test("SetDefault: not found, deleted, already the default, or made the default"):
    val kit = named
    save(kit, "d-1"): Unit
    save(kit, "d-2"): Unit
    assertEquals(kit.call(PlayerPayouts.setDefault)(SetDefault("nope", at)).replyValue.refusal, Some("DESTINATION_NOT_FOUND"))
    assertEquals(kit.call(PlayerPayouts.setDefault)(SetDefault("d-1", at)).replyValue.refusal, Some("ALREADY_DEFAULT"))
    kit.call(PlayerPayouts.setDefault)(SetDefault("d-2", at)): Unit
    assertEquals(kit.currentState.destinations.map(d => d.id -> d.default), Vector("d-1" -> false, "d-2" -> true))
    kit.call(PlayerPayouts.deleteDestination)(DeleteDestination("d-1", at)): Unit
    assertEquals(kit.call(PlayerPayouts.setDefault)(SetDefault("d-1", at)).replyValue.refusal, Some("DESTINATION_DELETED"))

  test("DeleteDestination blanks it, keeps it deleted, and promotes no other default"):
    val kit = named
    save(kit, "d-1"): Unit
    save(kit, "d-2"): Unit
    kit.call(PlayerPayouts.deleteDestination)(DeleteDestination("d-1", at)): Unit
    val d = kit.currentState.destination("d-1").get
    assertEquals((d.active, d.default, d.iban.reveal, d.givenName.reveal), (false, false, "", ""))
    assertEquals(kit.currentState.activeDestinations.map(d => d.id -> d.default), Vector("d-2" -> false))
    assertEquals(kit.call(PlayerPayouts.deleteDestination)(DeleteDestination("d-1", at)).replyValue.refusal, Some("DESTINATION_DELETED"))
    assertEquals(kit.call(PlayerPayouts.deleteDestination)(DeleteDestination("x", at)).replyValue.refusal, Some("DESTINATION_NOT_FOUND"))

  test("CountRequest is idempotent by id; Uncount stops it counting"):
    val kit = fresh
    val r   = Counted("r-1", 5000L, Eur, at)
    kit.call(PlayerPayouts.countRequest)(CountRequest(r, at)): Unit
    assertEquals(kit.call(PlayerPayouts.countRequest)(CountRequest(r, at)).events, Vector.empty)
    kit.call(PlayerPayouts.uncount)(Uncount("r-1", at)): Unit
    assertEquals(kit.currentState.recent, Vector(r.copy(counts = false)))
    assertEquals(kit.call(PlayerPayouts.uncount)(Uncount("r-1", at)).events, Vector.empty)

  test("requests older than 32 days are dropped at the next command"):
    val kit = fresh
    kit.call(PlayerPayouts.countRequest)(CountRequest(Counted("old", 1L, Eur, at), at)): Unit
    val later = at.plus(java.time.Duration.ofDays(33))
    kit.call(PlayerPayouts.countRequest)(CountRequest(Counted("new", 2L, Eur, later), later)): Unit
    assertEquals(kit.currentState.recent.map(_.id), Vector("new"))

  test("Erase blanks every Personal value and keeps the rest; later names are not recorded"):
    val kit = named
    kit.call(PlayerPayouts.seeKyc)(SeeKyc(Kyc.Verified, at)): Unit
    kit.call(PlayerPayouts.seeEmail)(SeeEmail(Personal("j@example.test"), at)): Unit
    save(kit, "d-1"): Unit
    kit.call(PlayerPayouts.seeDeposit)(SeeDeposit("gigadat", Some("ETO"), at)): Unit
    kit.call(PlayerPayouts.erase)(Erase(at)): Unit
    val s = kit.currentState
    assertEquals(s.names.map(n => (n.givenName.reveal, n.familyName.reveal)), Some(("", "")))
    assertEquals(s.email.map(_.reveal), Some(""))
    assertEquals(s.destinations.map(d => (d.id, d.iban.reveal, d.label.reveal)), Vector(("d-1", "", "")))
    assertEquals((s.kyc, s.deposits, s.erased), (Kyc.Verified, Map("gigadat" -> Set("ETO")), true))
    assertEquals(kit.call(PlayerPayouts.seeNames)(SeeNames(Personal("José"), Personal("Müller"), at)).events, Vector.empty)
    assertEquals(kit.call(PlayerPayouts.erase)(Erase(at)).events, Vector.empty)
