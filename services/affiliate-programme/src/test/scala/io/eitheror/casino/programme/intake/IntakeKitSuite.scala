package io.eitheror.casino.programme.intake

import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}
import io.eitheror.casino.contract.facts as contract
import io.eitheror.casino.contract.money.{Currency, MinorUnits}
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.brands.*
import io.eitheror.casino.programme.links.CreateLink
import io.eitheror.casino.programme.links.Link

import java.time.Instant

class IntakeKitSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-11-01T10:00:00Z")
  private val eur = Currency.unsafe("EUR")

  private def registration(id: String = "r-1", affiliate: String = "aff-1", link: String = "lnk-1") =
    contract.Registration(1, id, at, "goldvibe", "programme", affiliate, link, Some("summer"), Vector("s1"), Some("c-1"), "ps-1", Some("MT"))
  private def firstDeposit(id: String, amount: Long) =
    contract.FirstDeposit(1, id, at, "goldvibe", "programme", "aff-1", "lnk-1", None, Vector.empty, None, "ps-1", eur, MinorUnits.unsafe(amount), "d")
  private val click = contract.Click(1, "c-1", at, "goldvibe", "programme", "aff-1", "lnk-1", None, Vector.empty, Some("c-1"), Some("MT"), Some("mobile"))

  /** A brand, an active member on a CPA plan with a 2,000 minimum, and the member's live link. */
  private def world(status: Option[String] = Some("active")) =
    val calls = KitCalls()
    calls.brandOf("goldvibe").call(BrandProgramme.seed)(SeedBrand("goldvibe", at)): Unit
    val plan = Plan.idOf("goldvibe", "CPA")
    calls.planOf("goldvibe", plan).call(Plan.createPlan)(CreatePlan("goldvibe", "CPA", Plan.Cpa, 5_000, "EUR", 0, 2_000, false, "m", at)): Unit
    calls.linkOf("lnk-1").call(Link.createLink)(CreateLink("lnk-1", "aff-1", "goldvibe", None, None, Map.empty, Vector.empty, None, None, "aff-1", at)): Unit
    val m = calls.membershipOf("aff-1", "goldvibe")
    status.foreach { s =>
      m.call(Membership.applyFor)(Apply("aff-1", "goldvibe", Application(at, None, "form"))): Unit
      if s != "pending" then m.call(Membership.approve)(Approve(plan, "m", at)): Unit
      if s == "suspended" then m.call(Membership.suspend)(Suspend("m", Membership.Operator, at)): Unit
    }
    calls
  private def intake(calls: KitCalls) = Intake(calls, () => at.plusSeconds(1))

  test("a fact on another brand's channel is a mismatch, counted, and nothing is claimed") {
    val calls = world()
    assertEquals(intake(calls).handle(registration(), "topic:silvervibe", "silvervibe"), Received.Mismatched)
    assertEquals(calls.brandOf("silvervibe").call(BrandProgramme.get).replyValue.mismatches, 1L)
    assert(calls.inbox.isEmpty)
  }

  test("a fact claimed before, on any channel, is a duplicate, counted") {
    val calls = world()
    assertEquals(intake(calls).handle(registration(), "topic:goldvibe", "goldvibe"), Received.Recorded("bound"))
    assertEquals(intake(calls).handle(registration(), "webhook:goldvibe", "goldvibe"), Received.Duplicate)
    assertEquals(calls.brandOf("goldvibe").call(BrandProgramme.get).replyValue.duplicates, 1L)
  }

  test("a click is recorded against its link, not a player") {
    val calls = world()
    assertEquals(intake(calls).handle(click, "topic:goldvibe", "goldvibe"), Received.Recorded("click"))
    assertEquals(calls.clicks.map(_.factId).toVector, Vector("c-1"))
    assert(calls.players.isEmpty)
  }

  test("a first deposit is recorded with the plan's minimum; its outcome is kept on the inbox") {
    val calls = world()
    intake(calls).handle(registration(), "topic:goldvibe", "goldvibe"): Unit
    assertEquals(intake(calls).handle(firstDeposit("d-1", 1_500), "topic:goldvibe", "goldvibe"), Received.Recorded("deposit"))
    assertEquals(intake(calls).handle(firstDeposit("d-2", 2_000), "topic:goldvibe", "goldvibe"), Received.Recorded("first-deposit"))
    assertEquals(calls.inboxOf("goldvibe", "d-2").call(FactInbox.get).replyValue.outcome, Some("first-deposit"))
  }

  test("a fact for a player with no attribution is unattributed") {
    assertEquals(intake(world()).handle(firstDeposit("d-1", 2_000), "topic:goldvibe", "goldvibe"), Received.Recorded("unattributed:no_attribution"))
  }

  test("a revoked link is unattributed revoked_link, before the player is touched") {
    val calls = world()
    intake(calls).handle(registration(), "topic:goldvibe", "goldvibe"): Unit
    calls.linkOf("lnk-1").call(Link.revoke)(at): Unit
    assertEquals(intake(calls).handle(firstDeposit("d-1", 2_000), "topic:goldvibe", "goldvibe"), Received.Recorded("unattributed:revoked_link"))
    assertEquals(calls.playerOf("goldvibe", "ps-1").call(io.eitheror.casino.programme.players.PlayerAttribution.get).replyValue.guard, None)
  }

  test("an affiliate who is not a member is unattributed not_a_member; a suspended one still records") {
    val pending = world(Some("pending"))
    assertEquals(intake(pending).handle(registration(), "topic:goldvibe", "goldvibe"), Received.Recorded("unattributed:not_a_member"))
    assert(pending.players.get("goldvibe/ps-1").forall(_.call(io.eitheror.casino.programme.players.PlayerAttribution.get).replyValue.binding.isEmpty))
    val suspended = world(Some("suspended"))
    assertEquals(intake(suspended).handle(registration(), "topic:goldvibe", "goldvibe"), Received.Recorded("bound"))
  }

  test("the brand's consumer skips a fact it cannot read") {
    val kit = ConsumerTestKit.of(BrandTopic("goldvibe"))
    kit.onMessage(None): Unit
  }
