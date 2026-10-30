package io.eitheror.casino.kyc.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.{AuditRecord, PlatformAudit}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.api.OperatorEndpoint
import io.eitheror.casino.kyc.record.{KycRecord, ProfileChanged, ProfileField}

import java.time.LocalDate
import java.util.UUID

/** The operator's routes (US2, FR-002, FR-014, FR-018): who is admitted, and what each change leaves in `platform.audit`. */
class OperatorSuite extends munit.FunSuite with LogCapturing:
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private lazy val kit = SharedKit.kit
  private var player   = ""

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    player = s"p-${UUID.randomUUID().toString.take(8)}"

  private def submit(category: String): String =
    val o = kit.json("POST", kit.playerPath(s"/$player/uploads"), Json.obj("category" -> category, "contentType" -> "image/png"), kit.asPlayer(player))
    assertEquals(o.status, 200, o.text)
    assertEquals(kit.putSigned(o.str("url"), Array.fill(10)(1.toByte), "image/png") / 100, 2)
    assertEquals(kit.json("POST", kit.playerPath(s"/$player/documents"), Json.obj("documentId" -> o.str("documentId")), kit.asPlayer(player)).status, 200)
    o.str("documentId")

  private def complete(): Unit =
    kit.components.forEventSourcedEntity(EntityId(player)).call(KycRecord.profileChanged)
      .invoke(ProfileChanged(player, ProfileField.values.toSet, Some(Personal(LocalDate.of(2000, 1, 1))), false, Vector.empty, kit.now())): Unit

  private def audit(action: String, f: AuditRecord => Boolean = _ => true): AuditRecord =
    kit.eventually(s"an audit record $action") {
      kit.facts(PlatformAudit).collectFirst { case r: AuditRecord if r.subjectId == player && r.action == action && f(r) => r }
    }

  private def changed(r: AuditRecord, field: String) = r.changes.find(_.field == field).flatMap(_.after)

  test("each compliance role is admitted; any other role of the brand is forbidden; a player's token is refused"):
    submit("identity_front")
    OperatorEndpoint.ComplianceRoles.foreach { role =>
      val r = kit.request("GET", kit.operatorPath(s"/$player"), None, kit.asOperator(s"op-$role", s"brand_${kit.slug},$role"))
      assertEquals(r.status, 200, s"$role: ${r.text}")
    }
    Seq("support_agent", "fraud_analyst", "").foreach { role =>
      val r = kit.request("GET", kit.operatorPath(s"/$player"), None, kit.asOperator("op-x", s"brand_${kit.slug},$role"))
      assertEquals((r.status, r.str("error")), (403, "forbidden"), role)
    }
    assertEquals(kit.request("GET", kit.operatorPath(s"/$player"), None, kit.asPlayer(player)).status, 403)

  test("an operator of another brand is refused before any route runs"):
    val r = kit.request("GET", kit.operatorPath(s"/$player"), None, kit.asOperator("op-y", "brand_otherbrand,brand_compliance"))
    assertEquals(r.status, 403, r.text)

  test("a player with no record is player_not_found"):
    assertEquals(kit.request("GET", kit.operatorPath(s"/$player"), None, kit.asOperator()).status, 404)

  test("a review, an override, a mode change and a required change are each audited with the operator"):
    val id = submit("identity_front")
    val as = kit.asOperator("op-7")
    assertEquals(kit.json("POST", kit.operatorPath(s"/$player/reviews/$id"), Json.obj("outcome" -> "rejected", "reasonCode" -> "other"), as).status, 200)
    assertEquals(kit.json("PUT", kit.operatorPath(s"/$player/mode"), Json.obj("mode" -> "manual"), as).status, 200)
    assertEquals(kit.json("PUT", kit.operatorPath(s"/$player/required"), Json.obj("categories" -> Json.arr("proof_of_address")), as).status, 200)
    assertEquals(kit.json("POST", kit.operatorPath(s"/$player/override"), Json.obj("status" -> "verified", "reason" -> "phoned"), as).status, 200)
    val reviewed = audit("kyc.document_reviewed")
    assertEquals((reviewed.actorId, reviewed.reasonCode, changed(reviewed, "decision")), ("op-7", Some("other"), Some("rejected")))
    val overridden = audit("kyc.status_changed", r => changed(r, "reason").contains("override"))
    assertEquals((overridden.actorKind, overridden.actorId, changed(overridden, "status")), ("operator", "op-7", Some("verified")))
    assert(overridden.changes.exists(_.field == "note") && !overridden.toString.contains("phoned"), overridden.toString)
    val mode = audit("kyc.mode_changed")
    assertEquals((mode.actorId, changed(mode, "mode")), ("op-7", Some("manual")))
    val required = audit("kyc.required_changed")
    assertEquals((required.actorId, changed(required, "categories")), ("op-7", Some("proof_of_address")))

  test("the refusals of the account routes: unknown mode and status, an unknown category, a rejection without a code"):
    val id = submit("identity_front")
    def err(r: Reply) = (r.status, r.str("error"))
    assertEquals(err(kit.json("PUT", kit.operatorPath(s"/$player/mode"), Json.obj("mode" -> "auto"), kit.asOperator())), (400, "invalid_mode"))
    assertEquals(err(kit.json("PUT", kit.operatorPath(s"/$player/status"), Json.obj("status" -> "rejected"), kit.asOperator())), (400, "invalid_status"))
    assertEquals(err(kit.json("POST", kit.operatorPath(s"/$player/override"), Json.obj("status" -> "pending"), kit.asOperator())), (400, "invalid_status"))
    assertEquals(err(kit.json("PUT", kit.operatorPath(s"/$player/required"), Json.obj("categories" -> Json.arr("selfie")), kit.asOperator())),
      (400, "invalid_category"))
    assertEquals(err(kit.json("POST", kit.operatorPath(s"/$player/reviews/$id"), Json.obj("outcome" -> "rejected"), kit.asOperator())),
      (400, "invalid_rejection_reason"))
    assertEquals(err(kit.json("POST", kit.operatorPath(s"/$player/reviews/nope"), Json.obj("outcome" -> "approved"), kit.asOperator())),
      (404, "document_not_found"))

  test("in manual mode the ready time is set when the player-required categories are approved, and cleared by a switch to automatic"):
    complete()
    assertEquals(kit.json("PUT", kit.operatorPath(s"/$player/required"), Json.obj("categories" -> Json.arr("identity_front")), kit.asOperator()).status, 200)
    val id = submit("identity_front")
    assertEquals(kit.json("POST", kit.operatorPath(s"/$player/reviews/$id"), Json.obj("outcome" -> "approved"), kit.asOperator()).status, 200)
    val read = kit.request("GET", kit.operatorPath(s"/$player"), None, kit.asOperator()).json
    assert(Option(read.get("readyAt")).exists(n => !n.isNull), read.toString)
    val switched = kit.json("PUT", kit.operatorPath(s"/$player/mode"), Json.obj("mode" -> "automatic"), kit.asOperator())
    assertEquals(switched.status, 200)
    assert(switched.json.get("readyAt").isNull, switched.text)
    assertEquals(switched.str("status"), "verified") // automatic derives at once (FR-015)
