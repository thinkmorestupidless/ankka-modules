package io.eitheror.casino.kyc.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.{AuditRecord, PlatformAudit}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.record.{KycRecord, ProfileChanged, ProfileField}

import java.time.LocalDate
import java.util.UUID

/** The operator's routes (US2, FR-002, FR-014, FR-018): who is admitted, and what each change leaves in `platform.audit`. */
class OperatorSuite extends munit.FunSuite with LogCapturing with io.eitheror.casino.kyc.it.Held:
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

  test("identity.review is shown the documents, identity.read alone the status without them; a payments role and a player are refused"):
    submit("identity_front")
    def read(role: String) = kit.request("GET", kit.operatorPath(s"/$player"), None, kit.asOperator(s"op-$role", s"brand_${kit.slug},$role"))
    Seq("brand_compliance", "brand_admin", "platform_admin").foreach { role =>
      val r = read(role)
      assertEquals(r.status, 200, s"$role: ${r.text}")
      assert(r.text.contains("\"documentId\""), s"$role: ${r.text}")
    }
    Seq("brand_support", "brand_finance").foreach { role =>
      val r = read(role)
      assertEquals(r.status, 200, s"$role: ${r.text}")
      assert(r.json.has("status") && r.json.has("flags"), r.text)
      assert(!r.text.contains("documentId") && r.json.get("other") == null, s"$role: ${r.text}")
    }
    Seq("compliance_officer", "resource_admin", "").foreach { role =>
      val r = read(role)
      assert(r.status == 403 && r.text.contains("the identity.read permission is needed"), s"$role: $r")
    }
    assertEquals(kit.request("GET", kit.operatorPath(s"/$player"), None, kit.asPlayer(player)).status, 403)

  // spec 030 K3
  test("decisions are listed oldest first with who made each and when; identity.read alone without document ids; bounded by from and to"):
    val doc = submit("identity_front")
    val as  = kit.asOperator("op-k3", s"brand_${kit.slug},brand_compliance")
    assertEquals(kit.json("POST", kit.operatorPath(s"/$player/reviews/$doc"), Json.obj("outcome" -> "rejected", "reasonCode" -> "other"), as).status, 200)
    kit.advance(java.time.Duration.ofMinutes(5))
    val middle = kit.now()
    assertEquals(kit.json("PUT", kit.operatorPath(s"/$player/mode"), Json.obj("mode" -> "manual"), as).status, 200)
    kit.advance(java.time.Duration.ofMinutes(5))
    assertEquals(kit.json("PUT", kit.operatorPath(s"/$player/required"), Json.obj("categories" -> Json.arr("proof_of_address")), as).status, 200)
    assertEquals(kit.json("POST", kit.operatorPath(s"/$player/override"), Json.obj("status" -> "verified", "reason" -> "a reason of the operator's"), as).status, 200)

    def decisions(query: String = "", who: Seq[(String, String)] = as) =
      val r = kit.request("GET", kit.operatorPath(s"/$player/decisions$query"), None, who)
      assertEquals(r.status, 200, r.text)
      r
    val all   = decisions()
    val items = all.json.objects("decisions")
    val kinds = items.flatMap(_.text("kind"))
    assertEquals(kinds.filterNot(_ == "status_changed"), Vector("document_reviewed", "mode_changed", "required_changed"))
    assertEquals(items.flatMap(_.text("at")), items.flatMap(_.text("at")).sorted)
    val reviewed = items.find(_.text("kind").contains("document_reviewed")).get
    assertEquals((reviewed.text("documentId"), reviewed.text("outcome"), reviewed.text("reasonCode"), reviewed.get("actor").text("id")),
      (Some(doc), Some("rejected"), Some("other"), Some("op-k3")))
    val mode = items.find(_.text("kind").contains("mode_changed")).get
    assertEquals((mode.text("from"), mode.text("to")), (Some("automatic"), Some("manual")))
    val required = items.find(_.text("kind").contains("required_changed")).get
    assertEquals(Json.render(required.get("to")), """["proof_of_address"]""")
    val overridden = items.filter(_.text("kind").contains("status_changed")).last
    assertEquals((overridden.text("to"), overridden.text("cause"), overridden.get("actor").text("id")), (Some("verified"), Some("override"), Some("op-k3")))
    assert(!all.text.contains("a reason of the operator's"), all.text)

    val support = decisions(who = kit.asOperator("op-s", s"brand_${kit.slug},brand_support"))
    assertEquals(support.json.objects("decisions").size, items.size)
    assert(!support.text.contains("documentId"), support.text)

    val later = decisions(s"?from=$middle").json.objects("decisions").flatMap(_.text("kind"))
    assert(!later.contains("document_reviewed") && later.contains("mode_changed"), later.toString)
    val earlier = decisions(s"?to=$middle").json.objects("decisions").flatMap(_.text("kind"))
    assertEquals(earlier.filterNot(_ == "status_changed"), Vector("document_reviewed"))

    val bad = kit.request("GET", kit.operatorPath(s"/$player/decisions?from=yesterday"), None, as)
    assertEquals((bad.status, bad.str("error"), bad.str("field")), (400, "invalid_time", "from"))
    val payments = kit.request("GET", kit.operatorPath(s"/$player/decisions"), None, kit.asOperator("op-p", s"brand_${kit.slug},resource_admin"))
    assert(payments.status == 403 && payments.text.contains("the identity.read permission is needed"), payments.toString)

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
