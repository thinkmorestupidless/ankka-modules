package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.ObjectMapper

import scala.jdk.CollectionConverters.*

/** What the brand's realm gains for `players` (spec 008 T075, contracts/realm.md), the original's template otherwise unchanged. */
class RealmPlayersSuite extends munit.FunSuite:

  private val mapper   = ObjectMapper()
  private val rendered = Realm.render(Realm.template(Fixtures.root), "goldvibe", "https://goldvibe.example")
  private val secrets  = Map("REALM_ADMIN" -> "s3cret-admin", "REALM_SECRET" -> "rs", "WEBHOOK_SECRET" -> "ws")

  test("the template already maps eitheror_twin_id into casino-frontend's tokens") {
    val frontend = mapper.readTree(rendered).path("clients").elements().asScala.find(_.path("clientId").asText() == "casino-frontend").get
    assert(frontend.path("protocolMappers").elements().asScala.exists(_.path("name").asText() == "eitheror_twin_id"))
  }

  test("players-admin: confidential, a service account with manage-users, view-users and view-clients; the attributes the plugins read") {
    val r = mapper.readTree(Realm.withPlayers(rendered, Some("https://players-goldvibe.example"), secrets))
    val c = r.path("clients").elements().asScala.find(_.path("clientId").asText() == Realm.PlayersAdmin).get
    assertEquals((c.path("publicClient").asBoolean(), c.path("serviceAccountsEnabled").asBoolean(), c.path("secret").asText()), (false, true, "s3cret-admin"))
    val u = r.path("users").elements().asScala.find(_.path("serviceAccountClientId").asText() == Realm.PlayersAdmin).get
    assertEquals(u.path("clientRoles").path("realm-management").elements().asScala.map(_.asText()).toVector, Vector("manage-users", "view-users", "view-clients"))
    val a = r.path("attributes")
    assertEquals((a.path("players_url").asText(), a.path("players_realm_secret").asText(), a.path("players_webhook_secret").asText()),
      ("https://players-goldvibe.example", "rs", "ws"))
    assertEquals(a.path("frontendBaseUrl").asText(), "https://goldvibe.example")
    assert(r.path("eventsListeners").elements().asScala.exists(_.asText() == "eitheror-audit-listener"))
  }

  test("refresh tokens rotate: the template leaves it off, players' realm refuses a reused one") {
    assert(!mapper.readTree(rendered).path("revokeRefreshToken").asBoolean())
    val r = mapper.readTree(Realm.withPlayers(rendered, None, Map.empty))
    assertEquals((r.path("revokeRefreshToken").asBoolean(), r.path("refreshTokenMaxReuse").asInt(-1)), (true, 0))
  }

  test("an admin secret given as {clientId, clientSecret} sets the client's secret; none leaves it to Keycloak; a second pass adds no second client") {
    val json = Realm.withPlayers(rendered, None, Map("REALM_ADMIN" -> """{"clientId":"players-admin","clientSecret":"inner"}"""))
    def admins(j: String) = mapper.readTree(j).path("clients").elements().asScala.filter(_.path("clientId").asText() == Realm.PlayersAdmin).toVector
    assertEquals(admins(json).map(_.path("secret").asText()), Vector("inner"))
    assert(!admins(Realm.withPlayers(rendered, None, Map.empty)).head.has("secret"))
    assertEquals(admins(Realm.withPlayers(json, None, Map.empty)).size, 1)
    assert(!mapper.readTree(Realm.withPlayers(rendered, None, Map.empty)).path("attributes").has("players_realm_secret"))
  }
