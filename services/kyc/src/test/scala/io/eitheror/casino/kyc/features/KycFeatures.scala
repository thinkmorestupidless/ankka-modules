package io.eitheror.casino.kyc.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.it.{Kit, Reply, SharedKit}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.kyc.policy.{ApplyProfile, PolicyEntity}
import io.eitheror.casino.kyc.record.{KycRecord, KycState, Outcome, ProfileChanged, ProfileField, ReviewDocument}

import java.time.{Duration, Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.concurrent.duration.*

/**
 * A feature file of features/kyc bound against the whole service (constitution VII): the player's calls
 * through their routes, the operator's through theirs, the gates as the brand's services ask them. Each
 * scenario has a player of its own, since the database and the bucket outlive scenarios. A step that only
 * sets the scene for another story (an operator's review before the review routes are the story) is made
 * through the record itself.
 */
abstract class KycFeatures(file: String) extends GherkinSuite(s"../../features/kyc/$file") with LogCapturing:
  override val munitTimeout: scala.concurrent.duration.Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit

  protected var player      = ""
  protected var last: Reply = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()
    player = newPlayer()
    last = null
    applyProfile("CURACAO")

  protected def newPlayer(): String = s"p-${UUID.randomUUID().toString.take(8)}"

  Given("a player of brand {string}") { (brand: String) => assertEquals(brand, kit.slug) }

  protected def json(fields: (String, Any)*): JsonNode = Json.obj(fields*)

  // ── uploads ─────────────────────────────────────────────────────────────────────────────────────

  /** An upload offered: the signed link, the object name and the document id, as answered. */
  final case class Upload(documentId: String, url: String, objectName: String, contentType: String, expiresAt: Instant)

  protected def upload(reply: Reply): Upload =
    assertEquals(reply.status, 200, reply.text)
    val j = reply.json
    Upload(j.text("documentId").get, j.text("url").get, j.text("objectName").get, j.text("contentType").get, Instant.parse(j.text("expiresAt").get))

  protected def askUpload(category: String, contentType: String = "image/png", as: String = player): Reply =
    last = kit.json("POST", kit.playerPath(s"/$as/uploads"), json("category" -> category, "contentType" -> contentType), kit.asPlayer(as))
    last

  protected def askUploadAsOperator(category: String, reason: Option[String], contentType: String = "image/png"): Reply =
    last = kit.json("POST", kit.operatorPath(s"/$player/uploads"),
      json("category" -> category, "contentType" -> contentType, "reason" -> reason), kit.asOperator())
    last

  /** The browser's part: the bytes put through the signed link, of the type it was signed for. */
  protected def put(u: Upload, bytes: Array[Byte] = Array.fill(512)(7.toByte)): Unit =
    assertEquals(kit.putSigned(u.url, bytes, u.contentType) / 100, 2, s"PUT to ${u.objectName}")

  protected def confirm(documentId: String, as: String = player): Reply =
    last = kit.json("POST", kit.playerPath(s"/$as/documents"), json("documentId" -> documentId), kit.asPlayer(as))
    last

  protected def confirmAsOperator(documentId: String): Reply =
    last = kit.json("POST", kit.operatorPath(s"/$player/documents"), json("documentId" -> documentId), kit.asOperator())
    last

  /** Offered, put and confirmed: the document id of a pending document. */
  protected def submit(category: String, as: String = player): String =
    val u = upload(askUpload(category, as = as))
    put(u)
    assertEquals(confirm(u.documentId, as).status, 200, last.text)
    u.documentId

  // ── reads ───────────────────────────────────────────────────────────────────────────────────────

  protected def documents(as: String = player): JsonNode =
    val r = kit.request("GET", kit.playerPath(s"/$as/documents"), None, kit.asPlayer(as))
    assertEquals(r.status, 200, r.text)
    r.json

  protected def category(read: JsonNode, name: String): JsonNode =
    read.objects("categories").find(_.text("category").contains(name)).getOrElse(fail(s"no category $name in ${Json.render(read)}"))

  protected def documentOf(read: JsonNode, name: String): Option[JsonNode] =
    Option(category(read, name).get("document")).filter(_.isObject)

  protected def state(of: String = player): KycState =
    kit.components.forEventSourcedEntity(EntityId(of)).call(KycRecord.get).invoke()

  // ── what happened ───────────────────────────────────────────────────────────────────────────────

  protected def facts: Vector[CasinoPlayersFact] = kit.facts(CasinoPlayers).filter {
    case f: PlayerKycSubmitted        => f.playerId == player
    case f: PlayerKycDocumentReviewed => f.playerId == player
    case _                            => false
  }

  protected def audits: Vector[AuditRecord] = kit.facts(PlatformAudit).collect { case r: AuditRecord if r.subjectId == player => r }

  protected def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = kit.eventually(what, within)(f)

  // ── a review, made on the record (the review routes are US2's) ──────────────────────────────────

  protected def reviewed(documentId: String, outcome: Outcome, reason: Option[String] = None): Unit =
    val answer = kit.components.forEventSourcedEntity(EntityId(player)).call(KycRecord.review)
      .invoke(ReviewDocument(documentId, outcome, reason, "op-1", Vector.empty, kit.now()))
    assertEquals(answer.refusal, None)

  // ── the operator's routes ───────────────────────────────────────────────────────────────────────

  protected def operatorRead(of: String = player): JsonNode =
    val r = kit.request("GET", kit.operatorPath(s"/$of"), None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    r.json

  protected def approve(documentId: String): Reply =
    last = kit.json("POST", kit.operatorPath(s"/$player/reviews/$documentId"), json("outcome" -> "approved"), kit.asOperator())
    last

  protected def reject(documentId: String, code: String): Reply =
    last = kit.json("POST", kit.operatorPath(s"/$player/reviews/$documentId"), json("outcome" -> "rejected", "reasonCode" -> code), kit.asOperator())
    last

  protected def setMode(mode: String): Reply =
    last = kit.json("PUT", kit.operatorPath(s"/$player/mode"), json("mode" -> mode), kit.asOperator())
    last

  protected def setStatus(status: String): Reply =
    last = kit.json("PUT", kit.operatorPath(s"/$player/status"), json("status" -> status), kit.asOperator())
    last

  protected def setRequired(categories: String*): Reply =
    last = kit.json("PUT", kit.operatorPath(s"/$player/required"), json("categories" -> categories.toVector), kit.asOperator())
    last

  protected def overrideStatus(status: String, reason: String = "checked by the compliance team"): Reply =
    last = kit.json("POST", kit.operatorPath(s"/$player/override"), json("status" -> status, "reason" -> reason), kit.asOperator())
    last

  protected def queue(filter: String): JsonNode =
    val r = kit.request("GET", kit.operatorPath(s"/queue?filter=$filter"), None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    r.json

  // ── the brand's policy and the player's profile, as the scene ───────────────────────────────────

  protected def applyProfile(name: String): Unit =
    val a = kit.components.forEventSourcedEntity(EntityId(kit.slug)).call(PolicyEntity.applyProfile)
      .invoke(ApplyProfile(Some(name), "op-setup", kit.now()))
    assertEquals(a.refusal, None)

  protected def policyRequired = kit.components.forEventSourcedEntity(EntityId(kit.slug)).call(PolicyEntity.get).invoke().required

  /** The profile as players' facts would leave it: every field present and an adult, or `missing` absent, or `adultFrom` as given. */
  protected def profile(missing: Set[ProfileField] = Set.empty, adultFrom: LocalDate = LocalDate.of(1990, 1, 1)): Unit =
    val present = ProfileField.values.toSet -- missing
    val a = kit.components.forEventSourcedEntity(EntityId(player)).call(KycRecord.profileChanged)
      .invoke(ProfileChanged(player, present, Option.when(present(ProfileField.DateOfBirth))(Personal(adultFrom)), false, policyRequired, kit.now()))
    assertEquals(a.refusal, None)

  protected def today: LocalDate = LocalDate.ofInstant(kit.now(), ZoneOffset.UTC)

  protected def status: String = state().status.wire

  protected def near(a: Instant, b: Instant, within: Duration = Duration.ofMinutes(1)): Boolean =
    Duration.between(a, b).abs.compareTo(within) <= 0
