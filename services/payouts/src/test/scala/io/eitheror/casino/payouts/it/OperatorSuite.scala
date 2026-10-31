package io.eitheror.casino.payouts.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.clients.Gateway
import io.eitheror.casino.payouts.player.*
import io.eitheror.casino.payouts.terminals.{Refresh, TerminalCatalogue}

import java.util.UUID

/** US4 over the whole service (FR-001, FR-015; R6): who may see the queue and decide, once, and the audit of each decision. */
class OperatorSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    val bank = FakeGateway.terminal("t-bank", "unityfinance", destination = true)
    kit.gateway.terminals("EUR", bank)
    kit.components.forKeyValueEntity(EntityId(kit.slug)).call(TerminalCatalogue.refresh)
      .invoke(Refresh("EUR", Vector(Gateway.terminal(bank).get), kit.now())): Unit

  private def pending(): String =
    val p = s"op-${UUID.randomUUID().toString.take(8)}"
    val record = kit.components.forEventSourcedEntity(EntityId(p))
    record.call(PlayerPayouts.seeNames).invoke(SeeNames(Personal("Ana"), Personal("Silva"), kit.now())): Unit
    record.call(PlayerPayouts.seeKyc).invoke(SeeKyc(Kyc.Verified, kit.now())): Unit
    record.call(PlayerPayouts.saveDestination).invoke(SaveDestination(s"d-$p", "Ana", "Silva", "DE89370400440532013000", "main", true, kit.now())): Unit
    val r = kit.json("POST", kit.playerPath(s"/$p/withdrawal-requests"), Json.obj("amount" -> 5000L, "currency" -> "EUR",
      "terminalId" -> "t-bank", "destinationId" -> s"d-$p", "key" -> UUID.randomUUID().toString), kit.asPlayer(p))
    assertEquals(r.status, 201, r.text)
    r.str("requestId")

  private def call(method: String, path: String, roles: String, subject: String = "op-1", body: Option[String] = None) =
    kit.request(method, s"/v1/casino/brands/${kit.slug}/withdrawal-requests$path", body.orElse(Option.when(method == "POST")("{}")),
      kit.asOperator(subject, roles))

  private val brand = s"brand_${kit.slug}"

  test("each of the five roles is admitted to the queue; any other role is forbidden"):
    Vector("brand_admin", "brand_finance", "resource_admin", "resource_finance").foreach { role =>
      assertEquals(call("GET", "/", s"$brand,$role").status, 200, role)
    }
    assertEquals(call("GET", "/", "platform_admin").status, 200, "platform_admin")
    val viewer = call("GET", "/", s"$brand,resource_viewer")
    assertEquals((viewer.status, viewer.str("error")), (403, "forbidden"))
    val id = pending()
    assertEquals(call("POST", s"/$id/approve", s"$brand,support_agent").status, 403)
    assertEquals(call("GET", s"/$id", s"$brand,brand_finance").status, 200)

  test("an operator of another brand is refused, and a player's token too"):
    assertEquals(call("GET", "/", "brand_other,brand_admin").status, 403)
    val id = pending()
    assertEquals(call("POST", s"/$id/reject", "brand_other,brand_admin").status, 403)
    assertEquals(kit.request("GET", s"/v1/casino/brands/${kit.slug}/withdrawal-requests/", None, kit.asPlayer("p-1")).status, 403)

  test("a second decision is refused as not pending, whichever it is"):
    val approved = pending()
    assertEquals(call("POST", s"/$approved/approve", s"$brand,brand_finance").status, 200)
    Vector("approve", "reject").foreach { d =>
      val r = call("POST", s"/$approved/$d", s"$brand,brand_admin")
      assertEquals((r.status, r.str("error")), (409, "REQUEST_NOT_PENDING"), d)
    }
    val rejected = pending()
    assertEquals(call("POST", s"/$rejected/reject", s"$brand,brand_finance").status, 200)
    assertEquals(call("POST", s"/$rejected/approve", s"$brand,brand_finance").status, 409)
    assertEquals(call("POST", "/no-such-request/approve", s"$brand,brand_finance").status, 404)

  test("each decision is in platform.audit with the operator, and the reason never in clear text"):
    val approved = pending()
    val rejected = pending()
    val subject  = s"op-${UUID.randomUUID().toString.take(6)}"
    assertEquals(call("POST", s"/$approved/approve", s"$brand,brand_finance", subject).status, 200)
    assertEquals(call("POST", s"/$rejected/reject", s"$brand,brand_finance", subject,
      Some("""{"reason":"the holder is Mr Secret Person"}""")).status, 200)
    val records = kit.eventually("audited") {
      Some(kit.facts(PlatformAudit).collect { case r: AuditRecord if r.actorId == subject => r }).filter(_.size == 2)
    }
    assertEquals(records.map(r => (r.subjectId, r.actorKind)).toSet, Set(approved -> "operator", rejected -> "operator"))
    val text = kit.facts(PlatformAudit).collect { case r: AuditRecord if r.subjectId == rejected => r.toString }.mkString
    assert(!text.contains("Secret Person"), text)
