package io.eitheror.casino.kyc.record

import com.thinkmorestupidless.ankka.core.Serializer
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.kyc.KycCodecs
import io.eitheror.casino.kyc.KycCodecs.given
import io.eitheror.casino.kyc.policy.Profiles

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate}

/** The stored types round-trip, and an enum is written by its wire name so a renamed case keeps the journal. */
class ModelSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-10-30T12:00:00Z")

  private val state = KycState(
    playerId = "p-1",
    documents = Vector(
      Document("d-1", Category.IdentityFront, "subjects/p-1/kyc/identity_front-1.jpg", "image/jpeg", 10L, at,
        Uploader.support("op-1", "came by post"), Some(Review(Outcome.Rejected, Some(ReasonCode.DocumentUnclear), "op-2", at)))
    ),
    offers = Map("d-2" -> Offer("d-2", Category.Other, "application/pdf", "subjects/p-1/kyc/other-2.pdf", Uploader.Player, at)),
    status = Status.Rejected,
    mode = Mode.Manual,
    required = Vector(Category.Identity, Category.PaymentMethod),
    sof = Sof.Cleared,
    profile = ProfileFacts(Set(ProfileField.City), Some(Personal(LocalDate.parse("2008-01-15"))), Some(at)),
    deadline = Some(Deadline(at, migrated = true)),
    flags = Vector(Flag(FlagReason.LargeDeposit, at, Some(Dismissal("op-1", Personal("seen it"), at)))),
    deposits = Map("GBP" -> Deposits(500L, 3))
  )

  private val stateSerializer: Serializer[KycState] = KycCodecs.serializer[KycState]("record-state")
  private val eventSerializer: Serializer[KycEvent] = KycCodecs.serializer[KycEvent]("record-event")

  private def text[A](s: Serializer[A], a: A): String = new String(s.toBytes(a), UTF_8)

  test("the record's state round-trips"):
    val bytes = stateSerializer.toBytes(state)
    assertEquals(stateSerializer.fromBytes(bytes), state)

  test("enums are written by their wire names"):
    val json = text(stateSerializer, state)
    for name <- Vector("identity_front", "document_unclear", "rejected", "manual", "payment_method", "cleared", "large_deposit", "city") do
      assert(json.contains(s"\"$name\""), name)
    assert(!json.contains("IdentityFront"))

  test("every event round-trips"):
    val events: Vector[KycEvent] = Vector(
      KycEvent.StatusChanged(Status.Verified, Status.Unverified, Actor.System, "profile_changed", None, at),
      KycEvent.ProfileUpdated(Set(ProfileField.DateOfBirth), Some(Personal(LocalDate.parse("2008-01-15"))), true, at),
      KycEvent.Opened(state, at),
      KycEvent.RequiredChanged(Vector(Category.ProofOfAddress), Vector.empty, "op-1", at)
    )
    for e <- events do assertEquals(eventSerializer.fromBytes(eventSerializer.toBytes(e)), e)

  test("a command carrying the policy round-trips"):
    val s = KycCodecs.serializer[DepositCheck]("deposit-check")
    val c = DepositCheck("p-1", Profiles.Ukgc, Some("GBP"), 100L, at)
    assertEquals(s.fromBytes(s.toBytes(c)), c)
