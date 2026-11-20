package io.eitheror.casino.kyc.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.{AuditRecord, PlatformAudit}
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.documents.FakeStore
import io.eitheror.casino.kyc.record.{KycRecord, Outcome, ReviewDocument}

import java.time.Duration
import java.util.UUID

/**
 * The document routes against Postgres and Garage (US1, FR-004 to FR-008, SC-005), at the level of their
 * answers: what the features say in the domain's words, here as statuses and refusal codes.
 */
class DocumentsSuite extends munit.FunSuite with LogCapturing with io.eitheror.casino.kyc.it.Held:
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private lazy val kit = SharedKit.kit
  private var player   = ""

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    player = s"p-${UUID.randomUUID().toString.take(8)}"

  private def offer(category: String, as: String = player, contentType: String = "image/png"): Reply =
    kit.json("POST", kit.playerPath(s"/$as/uploads"), Json.obj("category" -> category, "contentType" -> contentType), kit.asPlayer(as))

  private def confirm(documentId: String, as: String = player): Reply =
    kit.json("POST", kit.playerPath(s"/$as/documents"), Json.obj("documentId" -> documentId), kit.asPlayer(as))

  /** Offered, put through the signed link and confirmed: the document id. */
  private def submit(category: String, as: String = player): String =
    val o = offer(category, as)
    assertEquals(o.status, 200, o.text)
    assertEquals(kit.putSigned(o.str("url"), Array.fill(100)(1.toByte), "image/png") / 100, 2)
    val c = confirm(o.str("documentId"), as)
    assertEquals(c.status, 200, c.text)
    o.str("documentId")

  private def review(documentId: String, outcome: Outcome, reason: Option[String] = None): Unit =
    kit.components.forEventSourcedEntity(EntityId(player)).call(KycRecord.review)
      .invoke(ReviewDocument(documentId, outcome, reason, "op-1", Vector.empty, kit.now())): Unit

  private def error(r: Reply): (Int, String) = (r.status, r.str("error"))

  test("an upload through the signed PUT is confirmed as pending, answering the document and the status"):
    val id = submit("identity_front")
    val read = kit.request("GET", kit.playerPath(s"/$player/documents"), None, kit.asPlayer(player)).json
    val front = Json.render(read)
    assert(front.contains(s""""documentId":"$id","category":"identity_front","status":"pending""""), front)

  test("a category with a pending or approved latest document is locked; other never is; a rejected one takes a replacement"):
    val id = submit("proof_of_address")
    assertEquals(error(offer("proof_of_address")), (409, "document_locked"))
    review(id, Outcome.Approved)
    assertEquals(error(offer("proof_of_address")), (409, "document_locked"))
    submit("other"); submit("other")
    val rejected = submit("identity_front")
    review(rejected, Outcome.Rejected, Some("document_expired"))
    assertEquals(offer("identity_front").status, 200)

  test("a confirm sent again answers the document; a second offer of a category confirmed meanwhile is locked at confirm"):
    val first  = offer("identity_back")
    val second = offer("identity_back")
    for o <- Seq(first, second) do assertEquals(kit.putSigned(o.str("url"), Array.fill(100)(1.toByte), "image/png") / 100, 2)
    val confirmed = confirm(first.str("documentId"))
    assertEquals(confirmed.status, 200, confirmed.text)
    val again = confirm(first.str("documentId"))
    assertEquals((again.status, again.text), (200, confirmed.text))
    assertEquals(error(confirm(second.str("documentId"))), (409, "document_locked"))

  test("a view link lasts fifteen minutes, records ViewIssued and one audit record naming the document (SC-005)"):
    val id = submit("identity_front")
    val v  = kit.request("GET", kit.playerPath(s"/$player/views/$id"), None, kit.asPlayer(player))
    assertEquals(v.status, 200, v.text)
    assert(v.str("url").contains("X-Amz-Expires=900"), v.str("url"))
    assertEquals(kit.getSigned(v.str("url"))._1, 200)
    val events = kit.components.forEventSourcedEntity(EntityId(player)).call(KycRecord.get).invoke()
    assert(events.document(id).isDefined)
    val audit = kit.eventually("the view's audit record") {
      kit.facts(PlatformAudit).collectFirst { case r: AuditRecord if r.subjectId == player && r.action == "kyc.view_issued" => r }
    }
    assertEquals(audit.changes.flatMap(_.after), Vector(id))

  // spec 030 K5: the operator is answered the bytes themselves; no address is given
  test("an operator's view is the document's bytes from the store, inline, never cached, recording ViewIssued with the operator"):
    val id = submit("identity_front")
    val v  = kit.request("GET", kit.operatorPath(s"/$player/views/$id"), None, kit.asOperator(subject = "op-k5"))
    assertEquals(v.status, 200, v.text)
    assertEquals(v.bytes.toVector, Vector.fill(100)(1.toByte))
    assertEquals((v.header("Content-Type"), v.header("Content-Disposition"), v.header("Cache-Control"), v.header("X-Content-Type-Options")),
      (Some("image/png"), Some("inline"), Some("no-store"), Some("nosniff")))
    assert(!v.text.contains("X-Amz"), v.text)
    val audit = kit.eventually("the view's audit record") {
      kit.facts(PlatformAudit).collectFirst { case r: AuditRecord if r.subjectId == player && r.action == "kyc.view_issued" => r }
    }
    assertEquals((audit.actorKind, audit.actorId, audit.changes.flatMap(_.after)), ("operator", "op-k5", Vector(id)))

  test("an operator's view is refused as the link was: unknown, gone from the store, without identity.review, the store unreachable"):
    assertEquals(error(kit.request("GET", kit.operatorPath(s"/$player/views/never"), None, kit.asOperator())), (404, "document_not_found"))
    val gone = submit("identity_front")
    val name = kit.components.forEventSourcedEntity(EntityId(player)).call(KycRecord.get).invoke().document(gone).get.objectName
    assert(kit.store.delete(name).isRight)
    assertEquals(error(kit.request("GET", kit.operatorPath(s"/$player/views/$gone"), None, kit.asOperator())), (404, "document_not_found"))
    val id      = submit("other")
    val support = kit.request("GET", kit.operatorPath(s"/$player/views/$id"), None, kit.asOperator(roles = s"brand_${kit.slug},${kit.named("brand_support")}"))
    assert(support.status == 403 && support.text.contains("the identity.review permission is needed"), support.toString)
    val store = FakeStore()
    store.outage(true)
    kit.reset(store)
    try assertEquals(error(kit.request("GET", kit.operatorPath(s"/$player/views/$id"), None, kit.asOperator())), (503, "storage_unavailable"))
    finally kit.reset()
    val viewed = kit.facts(PlatformAudit).collect { case r: AuditRecord if r.subjectId == player && r.action == "kyc.view_issued" => r }
    assertEquals(viewed, Vector.empty, "a refused view records none")

  test("another player's document is not found; another player's routes are a mismatch"):
    val other = s"p-${UUID.randomUUID().toString.take(8)}"
    val theirs = submit("identity_front", as = other)
    assertEquals(error(kit.request("GET", kit.playerPath(s"/$player/views/$theirs"), None, kit.asPlayer(player))), (404, "document_not_found"))
    assertEquals(error(kit.request("GET", kit.playerPath(s"/$other/views/$theirs"), None, kit.asPlayer(player))), (403, "player_id_mismatch"))
    assertEquals(error(confirm(theirs)), (404, "document_not_found"))

  test("an id never offered is not found; an offer past its hour is upload_expired; nothing put is upload_not_found"):
    assertEquals(error(confirm("never-offered")), (404, "document_not_found"))
    val unput = offer("identity_back")
    assertEquals(error(confirm(unput.str("documentId"))), (404, "upload_not_found"))
    kit.advance(Duration.ofMinutes(61))
    assertEquals(error(confirm(unput.str("documentId"))), (410, "upload_expired"))

  test("a category not uploadable, or the legacy identity, is invalid_category naming it"):
    val r = offer("selfie")
    assertEquals((r.status, r.str("error"), r.str("category")), (400, "invalid_category", "selfie"))
    assertEquals(error(offer("identity")), (400, "invalid_category"))

  test("an operator without identity.review is forbidden, naming it; an upload without a reason is reason_required"):
    val support = kit.asOperator(roles = s"brand_${kit.slug},${kit.named("brand_support")}")
    val refused = kit.json("POST", kit.operatorPath(s"/$player/uploads"),
      Json.obj("category" -> "other", "contentType" -> "image/png", "reason" -> "x"), support)
    assert(refused.status == 403 && refused.text.contains("the identity.review permission is needed"), refused.toString)
    assertEquals(error(kit.json("POST", kit.operatorPath(s"/$player/uploads"),
      Json.obj("category" -> "other", "contentType" -> "image/png"), kit.asOperator())), (400, "reason_required"))

  test("a store that cannot be reached is storage_unavailable on upload, confirm and view"):
    val id    = submit("identity_front")
    val open  = offer("other")
    val store = FakeStore()
    store.outage(true)
    kit.reset(store)
    try
      assertEquals(error(offer("proof_of_address")), (503, "storage_unavailable"))
      assertEquals(error(confirm(open.str("documentId"))), (503, "storage_unavailable"))
      assertEquals(error(kit.request("GET", kit.playerPath(s"/$player/views/$id"), None, kit.asPlayer(player))), (503, "storage_unavailable"))
    finally kit.reset()
