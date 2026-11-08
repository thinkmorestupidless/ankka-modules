package io.eitheror.casino.engagement.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.MinorUnits
import io.eitheror.casino.engagement.consumers.Sends
import io.eitheror.casino.engagement.profile.Suppression
import io.eitheror.casino.engagement.send.{Outcome, SendState}

import java.time.Instant
import java.util.UUID

/**
 * features/engagement/suppression.feature (US3; FR-011, FR-012). The marketing class trigger is a deposit (its track
 * and its transactional are both marketing), the service class one a failed withdrawal. No trigger of FR-006 or FR-014
 * is a closure confirmation (the original sends none: `player_closed` is identify-only), so a closed player is refused
 * every trigger (research, *Found during implementation*). The player has deposited once before, so a deposit is not
 * their first.
 */
class SuppressionFeatures extends EngagementFeatures("suppression.feature"):
  private var keys    = Vector.empty[String]
  private var caseId  = ""
  private var consent: f.PlayerConsentChanged = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    keys = Vector.empty
    caseId = ""
    consent = null

  private def address           = s"$player@example.com"
  private def sendsTo           = kit.crm.sends.count(_.path("to").asText == address)
  private def identifies        = kit.crm.identifies(player).map(kit.crm.attributes)
  private def text(a: Map[String, JsonNode], k: String) = a.get(k).map(_.asText)

  private def audits(action: String) =
    kit.facts(f.PlatformAudit).collect { case a: f.AuditRecord if a.subjectId == player && a.action == action => a }

  /** A marketing class trigger: a deposit, its send's key kept. */
  private def marketing(): String =
    val d = deposit()
    publishMoney(d)
    val k = sendKey("deposit_confirmed", d)
    keys :+= k
    k

  /** A service class trigger: a failed withdrawal, its send's key kept. */
  private def service(): String =
    val w = f.WithdrawalFailed(player, s"po-${UUID.randomUUID().toString.take(8)}", MinorUnits.unsafe(4000L), eur,
      "provider_declined", Instant.now())
    kit.publish(f.CasinoWithdrawals, w.key, w)
    val k = s"withdrawal_failed/${Sends.factId(f.CasinoWithdrawals, w)}"
    keys :+= k
    k

  private def openCase(): Unit =
    caseId = s"case-${UUID.randomUUID().toString.take(8)}"
    players(f.RgCaseOpened(player, caseId, Suppression.SelfExclusionCase, "player", None, Instant.now().minusSeconds(60)))

  private def only: SendState = settled(keys.last)

  Given("a player of brand {string}") { (slug: String) =>
    assertEquals(kit.slug, slug)
    register()
    money(deposit(at = Instant.now().minusSeconds(1800)))
  }

  // Scenarios: an open case.

  Given("the player has an open case of exclusion or break") { () => openCase() }

  When("a marketing class trigger fires for them") { () => marketing(): Unit }

  When("a service class trigger fires for them") { () => service(): Unit }

  Then("no send is made") { () =>
    only: Unit
    assertEquals(sendsTo, 0)
  }

  Then("no track is made") { () => assertEquals(kit.crm.tracks(player), Vector.empty) }

  Then("the identify still says the player is suppressed, with the reason") { () =>
    val a = eventually("the suppressed identify")(identifies.lastOption)
    assertEquals((text(a, "marketing_suppressed"), text(a, "suppression_reason")), (Some("true"), Some(Suppression.SelfExcluded)))
  }

  Then("the send record says {string} with the reason") { (outcome: String) =>
    assertEquals((only.outcome, only.reason), (outcome, Some(Suppression.SelfExcluded)))
  }

  Then("the refusal is an audit record") { () =>
    eventually("the refusal audited")(audits("engagement.send.refused").headOption): Unit
  }

  Then("the send is made") { () =>
    assertEquals(only.outcome, Outcome.Accepted)
    assertEquals(kit.crm.tracks(player).map(_.path("name").asText), Vector("withdrawal_failed"))
    assertEquals(sendsTo, 1)
  }

  // Scenario: consent withdrawn.

  Given("the player withdrew email marketing consent") { () =>
    consent = f.PlayerConsentChanged(player, "email", false, "preferences", Instant.now())
  }

  When("they are next identified") { () => publishPlayers(consent) }

  Then("{string} is {string} with the time and the source") { (name: String, value: String) =>
    val a = eventually(s"the identify with $name")(identifies.find(a => text(a, name).contains(value)))
    assert(a.contains("email_consent_at"), s"the time consent was given: $a")
    assertEquals(text(a, "consent_source"), Some("preferences"))
  }

  Then("a marketing class email for them is refused") { () =>
    marketing()
    assertEquals((only.outcome, only.reason), (Outcome.MarketingSuppressed, Some(Suppression.ConsentWithdrawn)))
    assertEquals(sendsTo, 0)
  }

  // Scenario: a closed player.

  Given("the player is closed") { () =>
    players(f.PlayerClosed(player, "active", "closed", "player", None, None, Instant.now().minusSeconds(60)))
  }

  When("any trigger but the closure confirmation fires for them") { () =>
    marketing()
    service(): Unit
  }

  Then("it is refused with {string}") { (outcome: String) =>
    assertEquals(keys.map(settled(_)).map(_.outcome), Vector(outcome, outcome))
    assertEquals((kit.crm.tracks(player), sendsTo), (Vector.empty, 0))
  }

  // Scenario: the exclusion ending.

  // The real flow: responsible-gambling opens the case and has players set the status; at the end it closes the case
  // and has players lift the exclusion, which players publishes as the player back to active.
  Given("the player's exclusion is ending") { () =>
    openCase()
    val at = Instant.now().minusSeconds(60)
    players(f.PlayerSelfExcluded(player, "active", "self_excluded", "system", None, None, at, None, at))
  }

  When("the {string} fact arrives") { (family: String) =>
    assertEquals(family, "rg_case_closed")
    publishPlayers(f.RgCaseClosed(player, caseId, Suppression.SelfExclusionCase, "expired", Instant.now()))
    publishPlayers(f.PlayerReinstated(player, "self_excluded", "active", "system", None, Some("exclusion_lifted"), Instant.now()))
  }

  Then("the player is identified as not suppressed") { () =>
    val a = eventually("the identify after the case")(identifies.find(a => text(a, "marketing_suppressed").contains("false")))
    assertEquals(text(a, "suppression_reason"), None)
  }

  Then("marketing class sends resume, subject to consent") { () =>
    marketing()
    assertEquals(only.outcome, Outcome.Accepted)
    assertEquals(kit.crm.tracks(player).map(_.path("name").asText), Vector("deposit_confirmed"))
    assertEquals(sendsTo, 1)
  }
