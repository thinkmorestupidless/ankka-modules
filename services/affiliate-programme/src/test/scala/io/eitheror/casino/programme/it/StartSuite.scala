package io.eitheror.casino.programme.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.brands.BrandProgramme
import io.eitheror.casino.programme.intake.FactInbox
import io.eitheror.casino.programme.links.SigningKeys

/** The kit starts, seeds the brands here, and admits each route class as research R12 says. */
class StartSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit

  test("the brands here are seeded, and a signing key made") {
    kit.brands.foreach(b => assert(kit.components.forKeyValueEntity(EntityId(b)).call(BrandProgramme.get).invoke().exists, b))
    assert(kit.components.forKeyValueEntity(EntityId(SigningKeys.Id)).call(SigningKeys.get).invoke().live.nonEmpty)
  }

  test("a fact on a brand's topic is claimed once") {
    val f = Facts("goldvibe", "aff-x", "lnk-x").registration("ps-1", kit.now)
    kit.publish(f); kit.publish(f)
    kit.eventually("the claim")(Option(kit.components.forKeyValueEntity(EntityId(s"goldvibe/${f.factId}")).call(FactInbox.get).invoke()).filter(_.outcome.nonEmpty))
  }

  test("a manager is held to the brands they hold; an affiliate to their own account") {
    assertEquals(kit.request("GET", "/v1/affiliates/goldvibe/settings", headers = kit.asManager(Seq("silvervibe"))).status, 403)
    assertEquals(kit.request("GET", "/v1/affiliates/goldvibe/settings", headers = kit.asManager()).status, 200)
    assertEquals(kit.request("GET", "/v1/affiliates/goldvibe/managers", headers = kit.asManager()).status, 403)
    assertEquals(kit.request("GET", "/v1/affiliates/goldvibe/managers", headers = kit.asManager(role = "brand_admin")).status, 200)
    assertEquals(kit.request("GET", "/v1/programme/brands", headers = kit.asManager()).status, 403)
    assertEquals(kit.request("GET", "/v1/programme/brands", headers = kit.asPlatformAdmin()).status, 200)
    val other = kit.request("GET", "/v1/my/aff-other/account", headers = kit.asAffiliate("aff-me"))
    assertEquals((other.status, other.json.get("error").asText), (404, "account_not_found"))
    assertEquals(kit.request("GET", "/v1/my/aff-me/account", headers = kit.asAffiliate("aff-me")).json.get("error").asText, "account_not_found")
    assertEquals(kit.request("GET", "/v1/my/aff-me/account", headers = kit.fromInternet).status, 401)
  }
