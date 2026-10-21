package io.eitheror.casino.merchant.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.hierarchy.Actor
import io.eitheror.casino.merchant.terminals.{Capability, CreateTerminal, Terminal}

import java.util.UUID
import scala.jdk.CollectionConverters.*

/** `/internal/v1/brands/{brand}/terminals` (spec 005, research R12): `psp-gateway` only, and no secret in it. */
class InternalRoutesSuite extends SharedKitSuite:

  private def as = kit.asOperator()
  private def brand() = s"b${UUID.randomUUID().toString.take(8)}"
  private def listing(b: String, headers: Seq[(String, String)]) = kit.request("GET", s"/internal/v1/brands/$b/terminals", None, headers)

  test("the gateway reads every terminal of the brand's sub-brands, with its capabilities and its properties but no secret") {
    val b      = brand()
    val first  = kit.subBrand(brand = Some(b))
    val second = kit.subBrand(brand = Some(b))
    val other  = kit.subBrand(brand = Some(brand()))
    val secret = s"tk-${UUID.randomUUID()}"
    assertEquals(kit.request("POST", s"/v1/merchants/${first.subBrandId}/gateways/DREAMZPAY/integrations",
      Some(s"""{"scheme":"VISA","name":"Visa","regions":[],"properties":{"memberId":"m-1","secureKey":"$secret"},"supportsWithdrawal":true}"""), as).status, 201)
    assertEquals(kit.request("POST", s"/v1/merchants/${second.subBrandId}/gateways/COZENI/integrations",
      Some("""{"scheme":"CRYPTO","name":"Coins","regions":[],"properties":{"supportedCurrencies":"BTC,ETH"},"supportsWithdrawal":true,"requiresPayoutDestination":true}"""), as).status, 201)
    assertEquals(kit.request("POST", s"/v1/merchants/${other.subBrandId}/gateways/DREAMZPAY/integrations",
      Some("""{"scheme":"VISA","name":"Elsewhere","regions":[],"properties":{}}"""), as).status, 201)

    val read = kit.eventually("both terminals")(Option(listing(b, kit.asGateway)).filter(_.status == 200)
      .filter(_.json.get("terminals").size == 2))
    assert(!read.text.contains(secret), "a secret value is in the answer")
    val ts = read.json.get("terminals").elements.asScala.toVector.map(t => t.textOr("name", "") -> t).toMap
    assertEquals(ts.keySet, Set("Visa", "Coins"))
    val visa = ts("Visa")
    assertEquals((visa.textOr("subBrandId", ""), visa.textOr("gateway", ""), visa.textOr("scheme", ""), visa.textOr("status", "")),
      (first.subBrandId, "DREAMZPAY", "VISA", "INACTIVE"))
    assertEquals((visa.bool("supportsDeposit"), visa.bool("supportsWithdrawal"), visa.bool("requiresPayoutDestination")), (Some(true), Some(true), Some(false)))
    assertEquals(visa.get("properties").properties.asScala.map(e => e.getKey -> e.getValue.asText).toMap, Map("memberId" -> "m-1"))
    val coins = ts("Coins")
    assertEquals(coins.textOr("subBrandId", ""), second.subBrandId)
    assertEquals(coins.bool("requiresPayoutDestination"), Some(true))
    assertEquals(coins.get("properties").textOr("supportedCurrencies", ""), "BTC,ETH")
    assert(coins.textOr("id", "").nonEmpty)
  }

  test("a key the catalogue calls a secret is left out even when the entity kept its value") {
    val b      = brand()
    val r      = kit.subBrand(brand = Some(b))
    val secret = s"sk-${UUID.randomUUID()}"
    val tid    = s"t-${UUID.randomUUID().toString.take(8)}"
    kit.components.forEventSourcedEntity(EntityId(tid)).call(Terminal.create).invoke(CreateTerminal(r.subBrandId, "DREAMZPAY", "VISA", "Kept",
      Vector.empty, Map("memberId" -> "m-2", "secureKey" -> secret), Vector.empty, Capability(true, true, false), Actor.operator("kit"), kit.now())): Unit
    val read = kit.eventually("the terminal")(Option(listing(b, kit.asGateway)).filter(_.status == 200).filter(_.json.get("terminals").size == 1))
    assert(!read.text.contains(secret), "a secret value is in the answer")
    assertEquals(read.json.get("terminals").get(0).get("properties").properties.asScala.map(_.getKey).toSet, Set("memberId"))
  }

  test("a brand no sub-brand names is an empty list") {
    val read = listing(brand(), kit.asGateway)
    assertEquals(read.status, 200)
    assertEquals(read.json.get("terminals").size, 0)
  }

  test("another caller is refused 403: ingest, a brand's payouts, and an operator") {
    val b = brand()
    kit.subBrand(brand = Some(b)): Unit
    assertEquals(listing(b, kit.asIngest).status, 403)
    assertEquals(listing(b, kit.asService(b, "payouts")).status, 403)
    assertEquals(listing(b, kit.asService("payments", "merchant")).status, 403)
    assertEquals(listing(b, as).status, 403)
  }
