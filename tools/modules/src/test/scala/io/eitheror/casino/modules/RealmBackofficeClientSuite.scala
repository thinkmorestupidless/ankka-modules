package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.ObjectMapper

import scala.jdk.CollectionConverters.*

/** The brand's backoffice client in the operators' realm (spec 030 D3): made with the brand's secret, or given it. */
class RealmBackofficeClientSuite extends munit.FunSuite:

  private val mapper = ObjectMapper()

  private def realms(): InMemoryRealms =
    val admin = InMemoryRealms()
    admin.realms(Realm.OperatorsRealm) = """{"realm":"eitheror"}"""
    admin.realms("casino-goldvibe") = "{}"
    io.eitheror.casino.contract.access.OperatorPermissions.DefaultRoles.keys.foreach(r => admin.createRole(Realm.OperatorsRealm, r, r))
    Seq("brand_goldvibe", "audit_compliance_goldvibe", "reidentify_goldvibe").foreach(r => admin.createRole(Realm.OperatorsRealm, r, r))
    admin

  private def clientOf(admin: InMemoryRealms) = mapper.readTree(admin.clients(Realm.OperatorsRealm -> "backoffice-goldvibe"))

  test("a client made without the brand's secret is given it on the next apply") {
    val admin = realms()
    admin.createClient(Realm.OperatorsRealm, Realm.backofficeClient("goldvibe", "https://bo.example", Some("minted")))
    val made = Realm.ensure(admin, "goldvibe", "{}", None, Some("https://bo.example" -> Some("the-brand's")))
    assertEquals(clientOf(admin).path("secret").asText, "the-brand's")
    assertEquals(made, Vector("client backoffice-goldvibe in realm eitheror updated: secret"))
    assert(!made.mkString.contains("the-brand's"), "no secret is printed")
  }

  test("a missing client is made with the brand's secret, and none is set when the brand has none") {
    val admin = realms()
    Realm.ensure(admin, "goldvibe", "{}", None, Some("https://bo.example" -> Some("s")))
    assertEquals(clientOf(admin).path("secret").asText, "s")
    assertEquals(Realm.ensure(admin, "goldvibe", "{}", None, Some("https://bo.example" -> None)), Vector.empty)
    assertEquals(clientOf(admin).path("secret").asText, "s")
  }

  test("a changed backoffice address moves the client's redirect, origins, root and sign-out address, and keeps the rest (review 217)") {
    val admin = realms()
    Realm.ensure(admin, "goldvibe", "{}", None, Some("https://bo.example" -> Some("s")))
    val made = Realm.ensure(admin, "goldvibe", "{}", None, Some("https://backoffice.goldvibe.example/" -> Some("s")))
    assertEquals(made, Vector("client backoffice-goldvibe in realm eitheror updated: rootUrl, baseUrl, redirectUris, webOrigins, post.logout.redirect.uris"))
    val c = clientOf(admin)
    assertEquals(c.path("redirectUris").elements().asScala.map(_.asText).toVector, Vector("https://backoffice.goldvibe.example/auth/callback"))
    assertEquals(c.path("webOrigins").elements().asScala.map(_.asText).toVector, Vector("https://backoffice.goldvibe.example"))
    assertEquals((c.path("rootUrl").asText, c.path("baseUrl").asText), ("https://backoffice.goldvibe.example", "https://backoffice.goldvibe.example"))
    assertEquals(c.path("attributes").path("post.logout.redirect.uris").asText, "https://backoffice.goldvibe.example/*")
    // what the patch did not name is kept, as Keycloak keeps it
    assertEquals(c.path("attributes").path("pkce.code.challenge.method").asText, "S256")
    assertEquals(c.path("secret").asText, "s")
    assertEquals(c.path("publicClient").asBoolean(true), false)
    // and the next run changes nothing
    assertEquals(Realm.ensure(admin, "goldvibe", "{}", None, Some("https://backoffice.goldvibe.example" -> Some("s"))), Vector.empty)
  }

  test("the patch names only the fields that differ, with the client id, and never a field it does not own") {
    val existing = mapper.readTree(Realm.backofficeClient("goldvibe", "https://bo.example", Some("s")))
    val (patch, changed) = Realm.clientPatch(existing, mapper.readTree(Realm.backofficeClient("goldvibe", "https://bo.example", Some("t"))))
    assertEquals(changed, Vector("secret"))
    assertEquals(patch.properties().asScala.map(_.getKey).toSet, Set("clientId", "secret"))
    val (_, none) = Realm.clientPatch(existing, mapper.readTree(Realm.backofficeClient("goldvibe", "https://bo.example/", None)))
    assertEquals(none, Vector.empty)
  }

  test("an operators' realm that rotates refresh tokens is refused, naming the setting, and nothing is made (review Refresh.scala:42)") {
    val admin = realms()
    admin.realms(Realm.OperatorsRealm) = """{"realm":"eitheror","revokeRefreshToken":true,"refreshTokenMaxReuse":0}"""
    val e = intercept[ControlPlaneError](Realm.ensure(admin, "goldvibe", "{}", None, Some("https://bo.example" -> Some("s"))))
    assert(e.getMessage.contains("revokeRefreshToken"), e.getMessage)
    assert(!admin.clients.contains(Realm.OperatorsRealm -> "backoffice-goldvibe"))
    // a brand without the backoffice is not held to it
    assertEquals(Realm.ensure(admin, "goldvibe", "{}", None, None), Vector.empty)
  }

  test("the operators' realm the tool imports leaves refresh-token rotation off") {
    val root = java.nio.file.Paths.get(sys.props.getOrElse("casino.root", "."))
    val tree = mapper.readTree(Realm.operators(root))
    assertEquals(tree.path("revokeRefreshToken").asBoolean(false), false)
  }
