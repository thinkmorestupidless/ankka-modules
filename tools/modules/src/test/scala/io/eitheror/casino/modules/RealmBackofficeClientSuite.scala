package io.eitheror.casino.modules

import scala.collection.mutable

/** The brand's backoffice client in the operators' realm (spec 030 D3): made with the brand's secret, or given it. */
class RealmBackofficeClientSuite extends munit.FunSuite:

  final class FakeAdmin(clients: mutable.Map[String, Option[String]]) extends RealmAdmin:
    val log = mutable.ArrayBuffer.empty[String]
    def realmExists(realm: String) = true
    def importRealm(json: String)  = ()
    def roleExists(realm: String, role: String) = true
    def createRole(realm: String, role: String, description: String) = ()
    def clientExists(realm: String, clientId: String) = clients.contains(clientId)
    def createClient(realm: String, json: String) =
      val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(json)
      clients(node.path("clientId").asText()) = Option(node.path("secret").asText(null))
    def setClientSecret(realm: String, clientId: String, secret: String) =
      clients.contains(clientId) && { clients(clientId) = Some(secret); log += s"secret $clientId"; true }
    def calls = log.toVector

  test("a client made without the brand's secret is given it on the next apply") {
    val clients = mutable.Map[String, Option[String]]("backoffice-goldvibe" -> Some("minted"))
    val made    = Realm.ensure(FakeAdmin(clients), "goldvibe", "{}", None, Some("https://bo.example" -> Some("the-brand's")))
    assertEquals(clients("backoffice-goldvibe"), Some("the-brand's"))
    assert(made.exists(_.contains("given the brand's secret")), made)
  }

  test("a missing client is made with the brand's secret, and none is set when the brand has none") {
    val clients = mutable.Map.empty[String, Option[String]]
    Realm.ensure(FakeAdmin(clients), "goldvibe", "{}", None, Some("https://bo.example" -> Some("s")))
    assertEquals(clients("backoffice-goldvibe"), Some("s"))
    val again = FakeAdmin(clients)
    Realm.ensure(again, "goldvibe", "{}", None, Some("https://bo.example" -> None))
    assertEquals(again.calls, Vector.empty)
  }
