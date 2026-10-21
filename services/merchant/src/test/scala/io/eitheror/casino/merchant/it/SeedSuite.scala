package io.eitheror.casino.merchant.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.merchant.{SeededBrand, Settings}
import io.eitheror.casino.merchant.hierarchy.*

import java.util.UUID
import scala.concurrent.duration.*

/** FR-003: the brands' hierarchy seeded on start under the original's ids, and nothing more on a second start. */
class SeedSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds
  private var kit: Kit = null
  override def beforeAll(): Unit = kit = Kit(Settings(brands = Vector(SeededBrand("goldvibe", "Goldvibe"))))
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private def subBrand = kit.components.forEventSourcedEntity(EntityId(UUID.nameUUIDFromBytes("seed.sub-brand.goldvibe".getBytes("UTF-8")).toString))

  test("a brand's holding, company and sub-brand exist under the original's name-based ids, the sub-brand a casino of the brand") {
    val (h, c, s) = Seed.ids("goldvibe")
    assertEquals(s, UUID.nameUUIDFromBytes("seed.sub-brand.goldvibe".getBytes("UTF-8")).toString)
    val sb = kit.eventually("seeded")(Option(subBrand.call(SubBrand.get).invoke()).filter(_.exists))
    assertEquals((sb.companyId, sb.holdingId, sb.subBrandType, sb.brand, sb.name), (c, h, SubBrandType.Casino, Some("goldvibe"), "goldvibe"))
    assert(kit.components.forEventSourcedEntity(EntityId(h)).call(Holding.get).invoke().exists)
  }

  test("a restart seeds again and records nothing more") {
    val (h, _, _) = Seed.ids("goldvibe")
    val first = kit.eventually("seeded")(Option(subBrand.call(SubBrand.get).invoke()).filter(_.exists)).createdAt
    kit.testKit.restartService()
    Thread.sleep(2000)
    assertEquals(subBrand.call(SubBrand.get).invoke().createdAt, first, "the sub-brand was not created again")
    val rows = kit.eventually("one holding row")(Option(kit.request("GET", "/v1/holdings", None, kit.asOperator()))
      .map(_.json.get("holdings")).filter(_.size == 1))
    assertEquals(rows.get(0).get("holdingId").asText, h)
  }
