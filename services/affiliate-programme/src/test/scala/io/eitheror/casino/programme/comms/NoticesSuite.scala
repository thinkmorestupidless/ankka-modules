package io.eitheror.casino.programme.comms

import io.eitheror.casino.programme.accounts.{Application, Membership}

import java.time.Instant

class NoticesSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-01T10:00:00Z")
  private val who = ("aff-2", "goldvibe")

  test("an application tells the applicant, and the parent when it came through them") {
    val n = Notices.ofMembership(Membership.Applied("aff-2", "goldvibe", Application(at, None, "invite", Some("aff-1"))), who, "https://p")
    assertEquals(n.map(x => (x.affiliate, x.kind, x.email)), Vector(
      ("aff-2", "APPLICATION_SUBMITTED", Some("AffiliateApplicationReceived")),
      ("aff-1", "SUB_AFFILIATE_PENDING", Some("AffiliateSubPending"))))
  }

  test("approval and rejection tell the applicant, with the dashboard and the reason") {
    assertEquals(Notices.ofMembership(Membership.Approved("p", "m", at), who, "https://p").map(_.data), Vector(Map("dashboard_url" -> "https://p")))
    assertEquals(Notices.ofMembership(Membership.ApplicationRejected(Some("no site"), "m", at), who, "https://p").map(_.data), Vector(Map("reason" -> "no site")))
  }

  test("the notification's id is the change's, the same on every delivery") {
    val n = Notices.ofMembership(Membership.Approved("p", "m", at), who, "x").head
    assertEquals(Notices.idOf("aff-2/goldvibe", n, at), Notices.idOf("aff-2/goldvibe", n, at))
    assertNotEquals(Notices.idOf("aff-2/goldvibe", n, at), Notices.idOf("aff-2/goldvibe", n, at.plusSeconds(1)))
  }

  test("nothing personal goes into what is stored: the email holds the affiliate's id only") {
    val n = Notices.ofMembership(Membership.Applied("aff-2", "goldvibe", Application(at, None, "form")), who, "x").head
    assertEquals(SendEmail(n.affiliate, n.brand, n.email.get, n.data), SendEmail("aff-2", "goldvibe", "AffiliateApplicationReceived", Map.empty))
  }
