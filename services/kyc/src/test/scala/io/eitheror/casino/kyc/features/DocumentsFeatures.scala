package io.eitheror.casino.kyc.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts.PlayerKycSubmitted
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.it.Reply
import io.eitheror.casino.kyc.policy.Policy
import io.eitheror.casino.kyc.record.{Category, Outcome, ReasonCode}

import java.net.URI
import java.time.Duration

/**
 * features/kyc/documents.feature (US1, FR-004 to FR-008). "In the player's language" is the frontends': here
 * the reason is asserted as the code the page renders.
 */
class DocumentsFeatures extends KycFeatures("documents.feature"):

  private var read: JsonNode            = null
  private var category: String          = ""
  private var offers: Vector[Upload]    = Vector.empty
  private var documentId: String        = ""
  private var completed: Option[Upload] = None

  private val Six = Category.Uploadable.map(_.wire)

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    read = null; category = ""; offers = Vector.empty; documentId = ""; completed = None

  // ── the page ──────────────────────────────────────────────────────────────────────────────────

  Given("the player has no documents") { () => assertEquals(state().documents, Vector.empty) }

  When("they open their documents page") { () => read = documents() }

  Then("all six categories are listed, each with no document and an upload action") { () =>
    assertEquals(read.objects("categories").flatMap(_.text("category")), Six)
    Six.foreach(c => assertEquals(documentOf(read, c), None, c))
    // An upload action is a category that takes an upload: one with no document is never locked (FR-006).
    Six.foreach(c => assertEquals(askUpload(c).status, 200, s"$c: ${last.text}"))
  }

  Then("the categories the brand's KYC policy requires are marked as required") { () =>
    // The policy's `identity` is the front of an identity document (FR-013).
    val required = Policy.Default.required.map(c => if c == Category.Identity then Category.IdentityFront else c).map(_.wire)
    assertEquals(read.objects("categories").filter(_.bool("requiredByPolicy").contains(true)).flatMap(_.text("category")), required)
  }

  // ── an upload ─────────────────────────────────────────────────────────────────────────────────

  Given("the category {string}") { (c: String) => category = c }

  When("the player asks to upload a file of type {string}, {string} or {string} for it") { (a: String, b: String, c: String) =>
    val types = Map("JPEG" -> "image/jpeg", "PNG" -> "image/png", "PDF" -> "application/pdf")
    offers = Vector(a, b, c).map(t => upload(askUpload(category, types(t))))
  }

  Then("they are given an upload address valid for one hour and a document id") { () =>
    offers.foreach { o =>
      assert(o.documentId.nonEmpty)
      assert(near(o.expiresAt, kit.now().plus(Duration.ofHours(1))), s"${o.expiresAt}")
      assert(URI.create(o.url).getQuery.contains("X-Amz-Expires=3600"), o.url)
    }
    assertEquals(offers.map(_.documentId).distinct.size, offers.size)
  }

  Then("the upload lands under the player's own prefix in the brand's document store and nowhere else") { () =>
    offers.foreach { o =>
      assert(o.objectName.startsWith(s"subjects/$player/kyc/$category-"), o.objectName)
      assertEquals(URI.create(o.url).getPath, s"/${kit.bucket.name}/${o.objectName}")
      put(o)
      assert(kit.store.head(o.objectName).exists(_.isDefined), o.objectName)
    }
  }

  // ── a confirmation ────────────────────────────────────────────────────────────────────────────

  Given("an upload that completed") { () =>
    val u = upload(askUpload("identity_front"))
    put(u)
    completed = Some(u)
  }

  When("the player confirms it") { () =>
    assertEquals(confirm(completed.get.documentId).status, 200, last.text)
    documentId = completed.get.documentId
    category = "identity_front"
  }

  Then("the category shows pending review") { () =>
    assertEquals(documentOf(documents(), category).flatMap(_.text("status")), Some("pending"))
  }

  Then("the document cannot be changed or removed by the player") { () =>
    assertEquals((askUpload(category).status, last.str("error")), (409, "document_locked"))
    val removed = kit.request("DELETE", kit.playerPath(s"/$player/documents/$documentId"), None, kit.asPlayer(player))
    assert(removed.status / 100 != 2, removed.text)
    assert(state().document(documentId).isDefined)
  }

  Then("a {string} fact is published") { (name: String) =>
    assertEquals(name, "player_kyc_submitted")
    eventually("player_kyc_submitted")(facts.collectFirst { case f: PlayerKycSubmitted if f.documentId == documentId => f })
  }

  private var first: Reply = null

  Given("the player confirmed it") { () =>
    first = confirm(completed.get.documentId)
    assertEquals(first.status, 200, first.text)
  }

  When("the player confirms it again") { () => confirm(completed.get.documentId) }

  Then("the answer is the first confirmation's") { () => assertEquals((last.status, last.text), (200, first.text)) }

  Then("the category holds that one document") { () =>
    assertEquals(state().documents.map(_.id), Vector(completed.get.documentId))
  }

  Given("two uploads for the category {string} that completed") { (c: String) =>
    category = c
    offers = Vector(upload(askUpload(c)), upload(askUpload(c)))
    offers.foreach(put(_))
  }

  When("the player confirms both") { () =>
    assertEquals(confirm(offers(0).documentId).status, 200, last.text)
    confirm(offers(1).documentId)
  }

  Then("the second is refused as locked") { () => assertEquals((last.status, last.str("error")), (409, "document_locked")) }

  Then("its file is removed from the brand's document store") { () =>
    assertEquals(kit.store.head(offers(1).objectName), Right(None))
    assert(kit.store.head(offers(0).objectName).exists(_.isDefined))
  }

  // ── locks ─────────────────────────────────────────────────────────────────────────────────────

  Given("a category whose latest document is {word}") { (s: String) =>
    category = "proof_of_address"
    documentId = submit(category)
    if s == "approved" then reviewed(documentId, Outcome.Approved)
    else assertEquals(s, "pending")
  }

  When("the player asks to upload another for it") { () => askUpload(category) }

  Then("it is refused as locked") { () => assertEquals((last.status, last.str("error")), (409, "document_locked")) }

  Given("the category {string} with a document pending") { (c: String) =>
    category = c
    documentId = submit(c)
  }

  Then("it is accepted as a document of its own") { () =>
    val u = upload(last)
    assertNotEquals(u.documentId, documentId)
    put(u)
    assertEquals(confirm(u.documentId).status, 200, last.text)
    val other = documents().objects("other")
    assertEquals(other.flatMap(_.text("documentId")).toSet, Set(documentId, u.documentId))
  }

  // ── a replacement and a rejection ─────────────────────────────────────────────────────────────

  Given("a category whose latest document was rejected") { () =>
    category = "proof_of_address"
    reviewed(submit(category), Outcome.Rejected, Some("document_unclear"))
  }

  When("the player uploads a replacement") { () => documentId = submit(category) }

  Then("the rejection reason is no longer shown") { () =>
    val d = documentOf(documents(), category).get
    assertEquals((d.text("documentId"), d.text("reasonCode")), (Some(documentId), None))
  }

  Given("a rejected document") { () =>
    category = "identity_front"
    reviewed(submit(category), Outcome.Rejected, Some("address_mismatch"))
  }

  When("the player views its category") { () => read = documents() }

  Then("the reason is one of the five reason codes rendered in the player's language") { () =>
    val d = documentOf(read, category).get
    assertEquals(d.text("status"), Some("rejected"))
    assert(d.text("reasonCode").exists(ReasonCode.of(_).isDefined), d.toString)
    assertEquals(ReasonCode.values.length, 5)
  }

  Then("never a free text") { () =>
    val d = documentOf(read, category).get
    assertEquals(d.text("reasonCode"), Some("address_mismatch"))
    assert(!d.has("reason") && !d.has("rejectReason") && !d.has("note"), d.toString)
  }

  // ── views ─────────────────────────────────────────────────────────────────────────────────────

  private var view: Reply = null
  private def bytesOf(id: String) = s"document $id".getBytes(java.nio.charset.StandardCharsets.UTF_8)

  Given("a document of the player") { () =>
    val u = upload(askUpload("identity_back"))
    put(u, bytesOf(u.documentId))
    assertEquals(confirm(u.documentId).status, 200, last.text)
    documentId = u.documentId
  }

  When("they ask to view it") { () =>
    view = kit.request("GET", kit.playerPath(s"/$player/views/$documentId"), None, kit.asPlayer(player))
  }

  Then("they are given a view address valid for {string} minutes to that document and no other") { (minutes: String) =>
    assertEquals(view.status, 200, view.text)
    val url = view.str("url")
    assert(near(java.time.Instant.parse(view.str("expiresAt")), kit.now().plus(Duration.ofMinutes(minutes.toLong))))
    assert(URI.create(url).getQuery.contains(s"X-Amz-Expires=${minutes.toInt * 60}"), url)
    assert(!url.contains("Bearer") && !url.contains("player|"), url)
    assertEquals(kit.getSigned(url) match { case (s, b) => (s, new String(b, java.nio.charset.StandardCharsets.UTF_8)) },
      (200, new String(bytesOf(documentId), java.nio.charset.StandardCharsets.UTF_8)))
    // The signature is for that object: the same query on another name is refused by the store.
    val other = url.replace("identity_back-", "identity_front-")
    assert(kit.getSigned(other)._1 / 100 != 2, other)
  }

  Then("the view is recorded") { () =>
    eventually("the view's audit record")(audits.find(r => r.action == "kyc.view_issued" && r.changes.exists(_.after.contains(documentId))))
  }

  Given("a document of another player") { () =>
    val other = newPlayer()
    documentId = submit("identity_front", as = other)
  }

  When("the player asks to view it") { () =>
    last = kit.request("GET", kit.playerPath(s"/$player/views/$documentId"), None, kit.asPlayer(player))
  }

  Then("it is refused") { () => assertEquals((last.status, last.str("error")), (404, "document_not_found")) }

  // ── files the brand does not take ─────────────────────────────────────────────────────────────

  private var fault = ""

  Given("a file that is of a type other than JPEG, PNG or PDF") { () => fault = "contentType" }
  Given("a file that is larger than the brand's limit") { () => fault = "size" }

  When("the player asks to upload it") { () =>
    if fault == "contentType" then askUpload("identity_front", "image/gif"): Unit
    else
      val u = upload(askUpload("identity_front"))
      put(u, Array.fill((kit.limit + 1).toInt)(1.toByte))
      confirm(u.documentId)
      completed = Some(u)
  }

  Then("it is refused naming the rule") { () =>
    assertEquals((last.status, last.str("error"), last.str("rule")), (400, "file_rejected", fault), last.text)
    // The oversized object is deleted, and nothing is recorded.
    completed.foreach(u => assertEquals(kit.store.head(u.objectName), Right(None)))
    assertEquals(state().documents, Vector.empty)
  }

  // ── support ───────────────────────────────────────────────────────────────────────────────────

  Given("a document support received by email") { () => category = "proof_of_address" }

  When("an operator asks to upload it for the player with a reason and confirms the upload") { () =>
    val u = upload(askUploadAsOperator(category, Some("sent by email on 28 October")))
    put(u)
    assertEquals(confirmAsOperator(u.documentId).status, 200, last.text)
    documentId = u.documentId
  }

  Then("the category is pending review") { () =>
    assertEquals(documentOf(documents(), category).flatMap(_.text("status")), Some("pending"))
  }

  Then("the audit record names the operator and the reason") { () =>
    val r = eventually("the operator upload's audit record")(audits.find(_.action == "kyc.upload_by_operator"))
    assertEquals(r.actorId, "op-1")
    assert(r.changes.exists(_.field == "reason"), r.changes.toString)
    // That a reason was given, never its text (personal).
    assert(!r.toString.contains("sent by email"), r.toString)
  }

  Then("the player's own list shows the document as submitted by support, with the time") { () =>
    val d = documentOf(documents(), category).get
    assertEquals(d.text("submittedBy"), Some("support"))
    assert(d.text("uploadedAt").exists(t => near(java.time.Instant.parse(t), kit.now())), d.toString)
  }
