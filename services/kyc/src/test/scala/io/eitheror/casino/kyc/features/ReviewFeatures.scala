package io.eitheror.casino.kyc.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.record.*

import java.net.URI
import java.time.Instant

/**
 * features/kyc/review.feature (US2, FR-010 to FR-018). The brand's policy is applied as a licence profile:
 * Curaçao requires identity only, MGA identity and proof of address. "The KYC status is derived" is an
 * operator switching the mode to manual and back, which derives in automatic mode (FR-015).
 */
class ReviewFeatures extends KycFeatures("review.feature"):

  private var documentId = ""
  private var before     = ""
  private var other      = ""
  private var subject    = "op-1"
  private var roles      = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    documentId = ""; before = ""; other = ""; subject = "op-1"; roles = ""

  private def derive(): Unit =
    assertEquals(setMode("manual").status, 200, last.text)
    assertEquals(setMode("automatic").status, 200, last.text)

  private def document(id: String): JsonNode =
    val read = operatorRead()
    (read.objects("categories").flatMap(c => Option(c.get("document")).filter(_.isObject)) ++ read.objects("other"))
      .find(_.text("documentId").contains(id)).getOrElse(fail(s"no document $id in ${read.toString}"))

  private def statusAudit(f: AuditRecord => Boolean): AuditRecord =
    eventually("the status change's audit record")(audits.find(r => r.action == "kyc.status_changed" && f(r)))

  private def changed(r: AuditRecord, field: String): Option[String] = r.changes.find(_.field == field).flatMap(_.after)

  private def approved(category: String): String =
    val id = submit(category)
    assertEquals(approve(id).status, 200, last.text)
    id

  // ── a review ──────────────────────────────────────────────────────────────────────────────────

  Given("a pending document") { () => documentId = submit("identity_front") }

  When("an operator approves it") { () => assertEquals(approve(documentId).status, 200, last.text) }

  Then("the document is approved with the operator and the time recorded") { () =>
    val d = document(documentId)
    assertEquals((d.text("status"), d.text("reviewedBy")), (Some("approved"), Some("op-1")))
    assert(d.text("reviewedAt").exists(t => near(Instant.parse(t), kit.now())), d.toString)
  }

  Then("a {string} fact is published") { (name: String) =>
    name match
      case "player_kyc_document_reviewed" =>
        eventually(name)(facts.collectFirst { case f: PlayerKycDocumentReviewed if f.documentId == documentId => f })
      case "player_kyc_verified" =>
        eventually(name)(kit.facts(CasinoPlayers).collectFirst { case f: PlayerKycVerified if f.playerId == player => f })
  }

  Then("a second approval or a rejection of the same document is refused as already reviewed") { () =>
    assertEquals((approve(documentId).status, last.str("error")), (409, "document_already_reviewed"))
    assertEquals((reject(documentId, "other").status, last.str("error")), (409, "document_already_reviewed"))
  }

  When("an operator rejects it with reason code {string}") { (code: String) => reject(documentId, code) }

  Then("the document is rejected with that code") { () =>
    assertEquals(last.status, 200, last.text)
    val d = document(documentId)
    assertEquals((d.text("status"), d.text("reasonCode")), (Some("rejected"), Some("document_unclear")))
  }

  Then("it is refused, because the code is not one of {string}, {string}, {string}, {string} or {string}") {
    (a: String, b: String, c: String, d: String, e: String) =>
      assertEquals((last.status, last.str("error")), (400, "invalid_rejection_reason"))
      assertEquals(ReasonCode.values.map(_.wire).toVector, Vector(a, b, c, d, e))
      assertEquals(state().document(documentId).flatMap(_.review), None)
  }

  // ── the derivation in automatic mode ──────────────────────────────────────────────────────────

  Given("the player in automatic mode on a brand requiring identity and proof of address, with a complete profile") { () =>
    applyProfile("MGA")
    profile()
    assertEquals(state().mode, Mode.Automatic)
  }

  When("the latest identity front and the latest proof of address are both approved") { () =>
    approved("identity_front"); approved("proof_of_address"): Unit
  }

  When("only the latest identity front is approved") { () =>
    approved("identity_front"): Unit
  }

  Then("the KYC status is {string}") { (s: String) => assertEquals(status, s) }

  Then("an audit record names the system as the actor") { () =>
    val r = statusAudit(r => changed(r, "status").contains("verified"))
    assertEquals((r.actorKind, r.actorId), ("system", "kyc"))
  }

  Then("the KYC status stays pending") { () => assertEquals(status, "pending") }

  // ── the legacy identity ───────────────────────────────────────────────────────────────────────

  private def legacy(status: Status): Unit =
    val at  = kit.now().minusSeconds(3600)
    val doc = Document("legacy-1", Category.Identity, s"subjects/$player/kyc/identity-1.png", "image/png", 100, at,
      review = Some(Review(Outcome.Approved, None, "op-old", at)))
    val record = KycState(player, documents = Vector(doc), status = status,
      profile = ProfileFacts(ProfileField.values.toSet, Some(Personal(java.time.LocalDate.of(1990, 1, 1))), Some(at)))
    val a = kit.components.forEventSourcedEntity(EntityId(player)).call(KycRecord.open).invoke(Open(record, at))
    assertEquals(a.refusal, None)

  Given("a brand requiring identity only and an approved legacy identity document with no identity front") { () =>
    legacy(Status.Unverified)
  }

  When("the KYC status is derived") { () => derive() }

  Then("the identity requirement is met") { () => assertEquals(status, "verified") }

  Given("a brand requiring identity only and an approved legacy identity document") { () => legacy(Status.Verified) }

  When("an identity front is uploaded") { () => documentId = submit("identity_front") }

  Then("the legacy document no longer counts for anything, approved or rejected") { () =>
    assertEquals(status, "pending")
    assertEquals(documentOf(documents(), "identity_front").flatMap(_.text("documentId")), Some(documentId))
    assertEquals(reject(documentId, "document_expired").status, 200, last.text)
    assertEquals(status, "rejected") // the front's rejection is the account's; the approved legacy meets nothing
  }

  // ── rejected and pending ──────────────────────────────────────────────────────────────────────

  Given("a category, required or not, whose latest document is rejected") { () =>
    profile()
    approved("identity_front")
    assertEquals(reject(submit("payment_method"), "invalid_document").status, 200)
  }

  Given("only a document in the category {string} is rejected") { (c: String) =>
    profile()
    approved("identity_front")
    assertEquals(reject(submit(c), "other").status, 200)
  }

  Then("the KYC status is not {string} by it") { (s: String) =>
    assertNotEquals(status, s)
    assertEquals(status, "verified")
  }

  Given("every required category approved and a document pending, as a category's latest or in the category {string}") { (c: String) =>
    profile()
    approved("identity_front")
    submit("payment_method"); submit(c): Unit
  }

  Then("the KYC status is pending") { () => assertEquals(status, "pending") }

  Given("every required category approved and a required profile field missing") { () =>
    profile(missing = Set(ProfileField.Phone))
    approved("identity_front"): Unit
  }

  Given("every required category approved and a date of birth under eighteen years ago") { () =>
    profile(adultFrom = today.plusYears(1))
    approved("identity_front"): Unit
  }

  Then("the player is told to complete their profile") { () =>
    assert(documents().strings("missingProfileFields").nonEmpty, documents().toString)
  }

  // ── a profile change ──────────────────────────────────────────────────────────────────────────

  Given("the player is verified") { () =>
    profile()
    documentId = approved("identity_front")
    assertEquals(status, "verified")
  }

  When("they change a required profile field") { () =>
    val p = (s: String) => Some(Personal(s))
    kit.publish(CasinoPlayers, player, PlayerProfileUpdated(player, Vector("addressLine"), p("Ada"), p("Lovelace"), p("1990-01-01"),
      p("+447700900000"), p("2 New Street"), p("London"), p("N1 1AA"), Some("GB"), kit.now()))
  }

  Then("the KYC status returns to unverified with an audit record whose reason is {string}") { (reason: String) =>
    eventually("unverified")(Option.when(status == "unverified")(()))
    val r = statusAudit(r => changed(r, "status").contains("unverified"))
    assertEquals(changed(r, "reason"), Some(reason))
  }

  Then("the account is not verified again until a document is reviewed after the change") { () =>
    derive()
    assertEquals(status, "unverified")
    approved("proof_of_address")
    assertEquals(status, "verified")
  }

  // ── manual mode ───────────────────────────────────────────────────────────────────────────────

  Given("the player in manual mode") { () =>
    profile()
    assertEquals(setMode("manual").status, 200, last.text)
    before = status
  }

  When("every required document is approved") { () => approved("identity_front"): Unit }

  Then("the KYC status does not change") { () => assertEquals(status, before) }

  Given("the player in manual mode with a complete profile") { () =>
    profile()
    assertEquals(setMode("manual").status, 200, last.text)
  }

  Given("the player in manual mode with an incomplete profile") { () =>
    profile(missing = Set(ProfileField.City, ProfileField.PostalCode))
    assertEquals(setMode("manual").status, 200, last.text)
  }

  When("the operator sets the KYC status to verified") { () => setStatus("verified") }

  Then("the KYC status is {string} at once") { (s: String) =>
    assertEquals((last.status, last.str("status")), (200, s))
    assertEquals(status, s)
  }

  Then("it is refused naming the missing fields") { () =>
    assertEquals((last.status, last.str("error")), (409, "profile_incomplete"))
    assertEquals(last.json.strings("missingProfileFields").toSet, Set("city", "postalCode"))
  }

  // ── categories required of one player ─────────────────────────────────────────────────────────

  Given("the player in automatic mode") { () =>
    other = newPlayer()
    assertEquals(state().mode, Mode.Automatic)
  }

  When("an operator marks a category required for this player") { () =>
    before = status
    assertEquals(setRequired("proof_of_address").status, 200, last.text)
  }

  Then("the account is in manual mode") { () => assertEquals(state().mode, Mode.Manual) }

  Then("the KYC status is pending unless already pending or rejected") { () =>
    assertEquals(status, if before == "rejected" then "rejected" else "pending")
  }

  Then("the player sees the category as required") { () =>
    assertEquals(category(documents(), "proof_of_address").bool("requiredOfPlayer"), Some(true))
  }

  Then("no other player is affected") { () =>
    val s = state(other)
    assertEquals((s.mode, s.required, s.status), (Mode.Automatic, Vector.empty, Status.Unverified))
    assertEquals(category(documents(other), "proof_of_address").bool("requiredOfPlayer"), Some(false))
  }

  // ── ready for final review ────────────────────────────────────────────────────────────────────

  private def ready(): Unit =
    profile()
    assertEquals(setRequired("identity_front").status, 200, last.text)
    approved("identity_front")
    assertNotEquals(status, "verified")
    assert(state().readyAt.isDefined, state().toString)

  private def listed: Option[JsonNode] = queue("ready").objects("rows").find(_.text("playerId").contains(player))

  Given("a manual mode account whose every player-required category has an approved latest document, a complete profile and a KYC status other than verified") {
    () => ready()
  }

  When("the queue is read with the {string} filter") { (name: String) => assertEquals(name, "Ready for final KYC review") }

  Then("the account is listed with the time it became ready") { () =>
    val row = eventually("the account in the ready list")(listed)
    assertEquals(row.text("readyAt").map(Instant.parse), state().readyAt)
  }

  Given("an account listed as ready for final review") { () =>
    ready()
    eventually("the account in the ready list")(listed)
  }

  When("the operator verifies it") { () => assertEquals(setStatus("verified").status, 200, last.text) }
  When("the operator switches it to automatic mode") { () => assertEquals(setMode("automatic").status, 200, last.text) }
  When("the operator marks another category required") { () =>
    assertEquals(setRequired("identity_front", "proof_of_address").status, 200, last.text)
  }

  Then("it leaves the list") { () => eventually("the account off the ready list")(Option.when(listed.isEmpty)(())) }

  Then("the time it became ready is cleared") { () =>
    assertEquals(state().readyAt, None)
    assert(Option(operatorRead().get("readyAt")).forall(_.isNull), operatorRead().toString)
  }

  // ── an operator's view ────────────────────────────────────────────────────────────────────────

  Given("an operator with the role {string} for the brand") { (role: String) =>
    subject = s"op-$role"
    roles = s"brand_${kit.slug},$role"
  }

  Given("an operator of another brand") { () =>
    subject = "op-elsewhere"
    roles = "brand_otherbrand,brand_compliance"
  }

  When("they view a document of the player") { () =>
    documentId = submit("identity_front")
    last = kit.request("GET", kit.operatorPath(s"/$player/views/$documentId"), None, kit.asOperator(subject, roles))
  }

  Then("they are given a view address valid for {string} minutes") { (minutes: String) =>
    assertEquals(last.status, 200, last.text)
    assert(URI.create(last.str("url")).getQuery.contains(s"X-Amz-Expires=${minutes.toInt * 60}"), last.str("url"))
  }

  Then("the view is recorded with their id") { () =>
    val r = eventually("the view's audit record")(audits.find(r => r.action == "kyc.view_issued" && r.changes.exists(_.after.contains(documentId))))
    assertEquals((r.actorKind, r.actorId), ("operator", subject))
  }

  Then("it is refused") { () => assertEquals(last.status, 403, last.text) }

  // ── an override ───────────────────────────────────────────────────────────────────────────────

  // A pending document and an approved one: the account is pending, so either decision is a change.
  Given("the player with documents in any state") { () =>
    submit("identity_front")
    approved("payment_method")
    assertEquals(status, "pending")
  }

  When("an operator verifies the account directly") { () => assertEquals(overrideStatus("verified").status, 200, last.text) }
  When("an operator rejects the account directly") { () => assertEquals(overrideStatus("rejected").status, 200, last.text) }

  Then("the KYC status is {string} with the operator as actor") { (s: String) =>
    assertEquals((last.str("status"), last.str("by")), (s, "op-1"))
    assertEquals(status, s)
  }

  Then("an audit record is written, regardless of the documents") { () =>
    val r = statusAudit(r => changed(r, "reason").contains("override"))
    assertEquals((r.actorKind, r.actorId, changed(r, "status")), ("operator", "op-1", Some(status)))
  }
