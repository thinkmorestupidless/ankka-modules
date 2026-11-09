package io.eitheror.casino.engagement.profile

import java.time.Instant

/** Suppression per channel (FR-012, research R14): each reason, their order, and no consent as none. */
class SuppressionSuite extends munit.FunSuite:
  private val now = Instant.parse("2026-11-01T12:00:00Z")

  private def consent(granted: Boolean) = Consent(granted, now, "registration")
  private val consented = ProfileState(
    playerId = "p-1",
    consents = Map("email" -> consent(true), "sms" -> consent(true))
  )
  private def all(reason: String) = Suppression.Verdict(Some(reason), Some(reason), Some(reason))

  test("a consented, active player is suppressed on no channel") {
    assertEquals(Suppression.of(consented), Suppression.Verdict(None, None, None))
  }

  test("account_closed: the account closed") {
    assertEquals(Suppression.of(consented.copy(status = "closed")), all("account_closed"))
  }

  test("self_excluded: an open self-exclusion case, or the self-excluded status") {
    val withCase = consented.copy(cases = Map("c-1" -> RgCase("self_exclusion", None)))
    assertEquals(Suppression.of(withCase), all("self_excluded"))
    assertEquals(Suppression.of(consented.copy(status = "self_excluded")), all("self_excluded"))
  }

  test("cooling_off: an open cooling-off case") {
    val p = consented.copy(cases = Map("c-1" -> RgCase("cool_off", Some(now.plusSeconds(86400)))))
    assertEquals(Suppression.of(p), all("cooling_off"))
  }

  test("a case of another type suppresses nothing") {
    val p = consented.copy(cases = Map("c-1" -> RgCase("deposit_limit", None)))
    assertEquals(Suppression.of(p), Suppression.Verdict(None, None, None))
  }

  test("operator: the messages consent withdrawn suppresses every channel") {
    val p = consented.copy(consents = consented.consents + ("messages" -> consent(false)))
    assertEquals(Suppression.of(p), all("operator"))
    val granted = consented.copy(consents = consented.consents + ("messages" -> consent(true)))
    assertEquals(Suppression.of(granted), Suppression.Verdict(None, None, None))
  }

  test("consent_withdrawn: a channel's own consent withdrawn suppresses that channel") {
    val p = consented.copy(consents = Map("email" -> consent(true), "sms" -> consent(false)))
    assertEquals(Suppression.of(p), Suppression.Verdict(None, Some("consent_withdrawn"), None))
  }

  test("fail-closed: a player with no consent fact is consented on no channel") {
    assertEquals(Suppression.of(ProfileState(playerId = "p-1")), all("consent_withdrawn"))
  }

  test("crm is consented when either email or SMS is") {
    val emailOnly = consented.copy(consents = Map("email" -> consent(true)))
    assertEquals(Suppression.of(emailOnly).crm, None)
    val smsOnly = consented.copy(consents = Map("sms" -> consent(true), "email" -> consent(false)))
    assertEquals(Suppression.of(smsOnly).crm, None)
    val neither = consented.copy(consents = Map("sms" -> consent(false), "email" -> consent(false)))
    assertEquals(Suppression.of(neither).crm, Some("consent_withdrawn"))
  }

  test("the first reason that holds is the one given") {
    val everything = ProfileState(
      status = "closed",
      cases = Map("a" -> RgCase("self_exclusion", None), "b" -> RgCase("cool_off", None)),
      consents = Map("messages" -> consent(false))
    )
    assertEquals(Suppression.of(everything), all("account_closed"))
    assertEquals(Suppression.of(everything.copy(status = "active")), all("self_excluded"))
    assertEquals(Suppression.of(everything.copy(status = "active", cases = everything.cases - "a")), all("cooling_off"))
    assertEquals(Suppression.of(everything.copy(status = "active", cases = Map.empty)), all("operator"))
    assertEquals(Suppression.of(everything.copy(status = "active", cases = Map.empty, consents = Map.empty)), all("consent_withdrawn"))
  }
