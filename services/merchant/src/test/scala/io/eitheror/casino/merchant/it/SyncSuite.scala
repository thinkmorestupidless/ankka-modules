package io.eitheror.casino.merchant.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.operators.Operator

import scala.concurrent.duration.*

/** FR-028: the realm's enabled flag mirrored on the recurring timer, and the timer kept across a restart. */
class SyncSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds
  private var kit: Kit = null
  override def beforeAll(): Unit =
    kit = Kit(io.eitheror.casino.merchant.Settings(operatorSyncInterval = java.time.Duration.ofSeconds(1)))
  override def afterAll(): Unit = if kit != null then kit.stop()

  test("an operator disabled at the realm reads as disabled once the recurring sync has run") {
    val r = kit.subBrand()
    val c = kit.request("POST", "/v1/operators", Some(Json.render(Json.obj("email" -> "sync@acme.example", "role" -> "resource_viewer",
      "scopeLevel" -> "company", "scopeId" -> r.companyId))), kit.asOperator())
    val id = c.str("operatorId")
    kit.keycloak.disable(id)
    kit.eventually("mirrored", 60.seconds)(Option(kit.components.forEventSourcedEntity(EntityId(id)).call(Operator.get).invoke()).filter(!_.enabled))
  }
