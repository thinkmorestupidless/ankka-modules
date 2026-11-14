package io.eitheror.casino.publicapi.it

import scala.jdk.CollectionConverters.*

/** contracts/routes.md's `/access-keys` table: every route, its validation and its admission (US1). */
class KeysSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit
  private def path(rest: String = "") = s"/access-keys/${kit.brand}$rest"

  test("create: 201 with the key and its secret; the defaults are no fields and 600 a minute") {
    val r = kit.request("POST", path(), Some("""{"name":"bi"}"""), kit.asKeyManager)
    assertEquals(r.status, 201, r.text)
    val key = r.json.path("key")
    assert(r.json.path("secret").asText().startsWith(s"ak_${key.path("id").asText()}_"))
    assertEquals(key.path("prefix").asText(), s"ak_${key.path("id").asText()}")
    assertEquals(key.path("identifyingFields").size(), 0)
    assertEquals(key.path("requestsPerMinute").asInt(), 600)
    assertEquals(key.path("enabled").asBoolean(), true)
    assertEquals(key.path("createdBy").asText(), "keys-1")
  }

  test("the secret is in the creation's answer only: neither the list nor the read carries it") {
    val k = Keys.emailOnly(kit)
    val one = kit.request("GET", path(s"/${k.id}"), headers = kit.asKeyManager)
    assertEquals(one.status, 200)
    assert(!one.text.contains(k.secret.drop(20)))
    val listed = kit.eventually("the key listed") {
      val r = kit.request("GET", path(), headers = kit.asKeyManager)
      Option.when(r.text.contains(k.id))(r)
    }
    assert(!listed.text.contains(k.secret.drop(20)))
    val row = listed.json.path("keys").elements().asScala.find(_.path("id").asText() == k.id).get
    assertEquals(row.path("identifyingFields").get(0).asText(), "email")
  }

  test("validation: 400 invalid_request naming the field") {
    val cases = Vector(
      """{}""" -> "name", """{"name":""}""" -> "name", s"""{"name":"${"x" * 101}"}""" -> "name",
      """{"name":"a","expiresAt":"2020-01-01T00:00:00Z"}""" -> "expiresAt", """{"name":"a","expiresAt":"soon"}""" -> "expiresAt",
      """{"name":"a","identifyingFields":["ssn"]}""" -> "identifyingFields", """{"name":"a","requestsPerMinute":0}""" -> "requestsPerMinute",
      """{"name":"a","requestsPerMinute":6001}""" -> "requestsPerMinute", """not json""" -> "body")
    cases.foreach { (body, field) =>
      val r = kit.request("POST", path(), Some(body), kit.asKeyManager)
      assertEquals(r.status, 400, body)
      assertEquals(r.error, "invalid_request", body)
      assertEquals(r.json.path("field").asText(), field, body)
    }
  }

  test("change: the name, the fields, the rate and the expiry; null clears the expiry") {
    val k = Keys.create(kit, "c", expiresAt = Some(kit.now.plusSeconds(3600).toString))
    val r = Keys.change(kit, k, """{"name":"c2","identifyingFields":["city"],"requestsPerMinute":5,"expiresAt":null}""")
    assertEquals(r.status, 200, r.text)
    assertEquals(r.json.path("name").asText(), "c2")
    assertEquals(r.json.path("requestsPerMinute").asInt(), 5)
    assert(!r.json.has("expiresAt"))
    assertEquals(Keys.change(kit, k, """{"requestsPerMinute":"many"}""").json.path("field").asText(), "requestsPerMinute")
  }

  test("disable and enable are idempotent; a deleted key is 409 key_deleted on change and enable, and delete is 204 twice") {
    val k = Keys.noFields(kit)
    assertEquals(Keys.act(kit, k, "disable").path("enabled").asBoolean(), false)
    assertEquals(Keys.act(kit, k, "disable").path("enabled").asBoolean(), false)
    assertEquals(Keys.act(kit, k, "enable").path("enabled").asBoolean(), true)
    assertEquals(kit.request("DELETE", path(s"/${k.id}"), headers = kit.asKeyManager).status, 204)
    assertEquals(kit.request("DELETE", path(s"/${k.id}"), headers = kit.asKeyManager).status, 204)
    val enable = kit.request("POST", path(s"/${k.id}/enable"), headers = kit.asKeyManager)
    assertEquals((enable.status, enable.error), (409, "key_deleted"))
    assertEquals(Keys.change(kit, k, """{"name":"x"}""").status, 409)
    assertEquals(kit.request("POST", path(s"/${k.id}/disable"), headers = kit.asKeyManager).status, 200)
  }

  test("deleted keys are listed only when asked") {
    val k = Keys.deleted(kit)
    kit.eventually("the deleted key listed when asked") {
      Option.when(kit.request("GET", path("?deleted=true"), headers = kit.asKeyManager).text.contains(k.id))(())
    }
    assert(!kit.request("GET", path(), headers = kit.asKeyManager).text.contains(k.id))
  }

  test("an unknown key is 404; another brand's segment is 404") {
    assertEquals(kit.request("GET", path("/aaaaaaaaaaaaaaaa"), headers = kit.asKeyManager).status, 404)
    assertEquals(kit.request("GET", "/access-keys/spinvibe", headers = kit.asStaff(Seq("api_keys", "brand_spinvibe"))).status, 404)
  }

  test("an operator without the access keys permission, or of another brand, is refused; platform_admin is admitted") {
    assertEquals(kit.request("GET", path(), headers = kit.asStaff(Nil)).status, 403)
    assertEquals(kit.request("POST", path(), Some("""{"name":"x"}"""), kit.asStaff(Nil)).status, 403)
    assertEquals(kit.request("GET", path(), headers = kit.asStaff(Seq("api_keys", "brand_spinvibe"), brandRole = false)).status, 403)
    assertEquals(kit.request("GET", path(), headers = kit.fromInternet).status, 401)
    assertEquals(kit.request("GET", path(), headers = kit.asStaff(Seq("platform_admin"), brandRole = false)).status, 200)
  }
