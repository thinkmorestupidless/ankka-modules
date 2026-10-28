package io.eitheror.casino.players.port

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import io.eitheror.casino.players.port.WireTranslation.Context

/** contracts/wire.md: one test per row, and a path the tables do not name passes through. */
class WireTranslationSuite extends munit.FunSuite:

  private val mapper = ObjectMapper()
  private def json(s: String): JsonNode = mapper.readTree(s)
  private val ctx = Context("goldvibe", Some("p-1"))
  private def path(method: String, p: String) = WireTranslation.path(method, p, ctx)

  test("a path recorded at the original's brand is served at the replay's") {
    assertEquals(path("POST", "/v1/casino/players/spinvibe/register"), "/v1/casino/players/goldvibe/register")
    assertEquals(path("GET", "/v1/casino/sessions/spinvibe?playerId=p-2"), "/v1/casino/sessions/goldvibe/players/p-2")
    assertEquals(path("PATCH", "/v1/casino/spinvibe/players/p-2/suspend"), "/v1/casino/backoffice/goldvibe/players/p-2/suspend")
    assertEquals(path("GET", "/v1/casino/backoffice/spinvibe/fraud-cases?status=OPEN"), "/v1/casino/backoffice/goldvibe/fraud-cases?status=OPEN")
    assertEquals(path("GET", "/v1/casino/brands/spinvibe/merge-tags"), "/v1/casino/backoffice/goldvibe/merge-tags")
  }

  test("/me/ routes name the player") {
    assertEquals(path("GET", "/v1/casino/players/goldvibe/me/activity-history?page=2"), "/v1/casino/players/goldvibe/p-1/activity-history?page=2")
    assertEquals(path("GET", "/v1/casino/players/goldvibe/me/recently-played"), "/v1/casino/players/goldvibe/p-1/recently-played")
    assertEquals(path("POST", "/v1/casino/players/goldvibe/me/favourites/g-9"), "/v1/casino/players/goldvibe/p-1/favourites/g-9")
  }

  test("the address list and add name the player; verify and an address's routes are unchanged") {
    assertEquals(path("GET", "/v1/casino/players/goldvibe/emails"), "/v1/casino/players/goldvibe/p-1/emails")
    assertEquals(path("POST", "/v1/casino/players/goldvibe/emails"), "/v1/casino/players/goldvibe/p-1/emails")
    assertEquals(path("POST", "/v1/casino/players/goldvibe/emails/verify"), "/v1/casino/players/goldvibe/emails/verify")
    assertEquals(path("POST", "/v1/casino/players/goldvibe/emails/a%40b.test/promote"), "/v1/casino/players/goldvibe/emails/a%40b.test/promote")
  }

  test("the address change names the player; its verify is unchanged") {
    assertEquals(path("POST", "/v1/casino/players/goldvibe/email-change"), "/v1/casino/players/goldvibe/p-1/email-change")
    assertEquals(path("POST", "/v1/casino/players/goldvibe/email-change/verify"), "/v1/casino/players/goldvibe/email-change/verify")
  }

  test("the sessions list by query is the player's sessions route") {
    assertEquals(path("GET", "/v1/casino/sessions/goldvibe?playerId=p-2"), "/v1/casino/sessions/goldvibe/players/p-2")
    assertEquals(path("GET", "/v1/casino/sessions/goldvibe/s-1"), "/v1/casino/sessions/goldvibe/s-1")
  }

  test("the operator's player actions move under the operator prefix") {
    Seq("suspend", "reinstate", "vip-tier", "pot-vip", "reset-password", "date-of-birth", "clear-suspect-flag",
      "sessions/s-1/force-end", "sessions/force-end-all").foreach { action =>
      assertEquals(path("PATCH", s"/v1/casino/goldvibe/players/p-1/$action"), s"/v1/casino/backoffice/goldvibe/players/p-1/$action")
    }
  }

  test("the brand configuration moves under the operator prefix") {
    assertEquals(path("PUT", "/v1/casino/goldvibe/brand-config"), "/v1/casino/backoffice/goldvibe/brand-config")
  }

  test("the activity export and the fraud-case review are unchanged") {
    assertEquals(path("POST", "/v1/casino/backoffice/goldvibe/players/p-1/activity-export"), "/v1/casino/backoffice/goldvibe/players/p-1/activity-export")
    assertEquals(path("PATCH", "/v1/casino/backoffice/goldvibe/fraud-cases/c-1/review"), "/v1/casino/backoffice/goldvibe/fraud-cases/c-1/review")
  }

  test("the merge tags and the preview move under the operator prefix") {
    assertEquals(path("GET", "/v1/casino/brands/goldvibe/merge-tags"), "/v1/casino/backoffice/goldvibe/merge-tags")
    assertEquals(path("POST", "/v1/casino/brands/goldvibe/messages/preview"), "/v1/casino/backoffice/goldvibe/messages/preview")
  }

  test("the realm's webhook is the brand's realm route") {
    assertEquals(path("POST", "/internal/keycloak-webhook"), "/internal/realm/goldvibe/webhook")
  }

  test("a path the tables do not name passes through unchanged") {
    Seq("/v1/casino/players/goldvibe/register", "/v1/casino/players/goldvibe/p-1", "/v1/casino/players/goldvibe/p-1/consent",
      "/v1/casino/backoffice/goldvibe/players/p-1/notes", "/health").foreach(p => assertEquals(path("GET", p), p))
  }

  test("a registration's trackingContext.btag is sent as btag") {
    val out = WireTranslation.body("/v1/casino/players/goldvibe/register", Some(json("""{"email":"e","trackingContext":{"btag":"a_1b_2c_"}}""")))
    assertEquals(out.map(_.toString), Some("""{"email":"e","btag":"a_1b_2c_"}"""))
    val other = Some(json("""{"trackingContext":{"btag":"x"}}"""))
    assertEquals(WireTranslation.body("/v1/casino/players/goldvibe/login", other), other)
  }

  test("an answer keeps only the fields the original answered, recursively") {
    val recorded = json("""{"error":"x","items":[{"id":1}]}""")
    val observed = json("""{"error":"x","message":"why","items":[{"id":1,"extra":true},{"id":2}]}""")
    assertEquals(WireTranslation.answer(Some(recorded), Some(observed)).map(_.toString), Some("""{"error":"x","items":[{"id":1},{"id":2}]}"""))
    assertEquals(WireTranslation.answer(None, Some(observed)), Some(observed))
  }

  test("the original's plain-text missing-fields refusal is recognised as the listed divergence") {
    assert(WireTranslation.plainMissingFields(Some(mapper.getNodeFactory.textNode("email, password, and currency are required"))))
    assert(!WireTranslation.plainMissingFields(Some(json("""{"error":"missing_fields"}"""))))
  }
