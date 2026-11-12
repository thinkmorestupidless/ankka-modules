package io.eitheror.casino.publicapi.it

import scala.concurrent.duration.*

/** contracts/routes.md's pipeline steps 2 and 3: every key refusal with its status and code (US1). */
class AdmissionSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit

  private def contract(key: Option[String]) = kit.v1("/contract", key)

  test("an admitted key reads /v1/contract") {
    val r = contract(Some(Keys.noFields(kit).secret))
    assertEquals(r.status, 200, r.text)
    assertEquals((r.json.path("version").asText(), r.json.path("maxPageSize").asInt()), ("v1", 100))
  }

  test("no key is 401 key_missing") {
    val r = contract(None)
    assertEquals((r.status, r.error), (401, "key_missing"))
  }

  test("a malformed, unknown or wrong key is 401 key_invalid") {
    val k = Keys.noFields(kit)
    Vector("nonsense", k.secret.dropRight(1), k.secret.dropRight(1) + (if k.secret.last == 'A' then "B" else "A"),
      "ak_aaaaaaaaaaaaaaaa_" + "A" * 43).foreach { key =>
      val r = contract(Some(key))
      assertEquals((r.status, r.error), (401, "key_invalid"), key)
    }
    val basic = kit.request("GET", "/v1/contract", headers = kit.fromInternet :+ ("Authorization" -> s"Basic ${k.secret}"))
    assertEquals((basic.status, basic.error), (401, "key_invalid"))
  }

  test("a disabled key is 403 key_disabled; a deleted one is 401 key_invalid") {
    val d = Keys.disabled(kit)
    assertEquals((contract(Some(d.secret)).status, contract(Some(d.secret)).error), (403, "key_disabled"))
    val x = Keys.deleted(kit)
    assertEquals((contract(Some(x.secret)).status, contract(Some(x.secret)).error), (401, "key_invalid"))
  }

  test("a key past its expiry is 401 key_expired") {
    val k = Keys.expiring(kit)
    assertEquals(contract(Some(k.secret)).status, 200)
    kit.moveClock(61.seconds)
    val r = contract(Some(k.secret))
    assertEquals((r.status, r.error), (401, "key_expired"))
  }
