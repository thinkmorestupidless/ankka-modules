package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.activity.*
import io.eitheror.casino.programme.api.Answers.{Ok, given}
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.intake.Inbox
import io.eitheror.casino.programme.players.Players

import java.time.Instant

/** The shapes of what managers record and read about an affiliate and a brand (O23 to O25, O48 to O52). */
object RecordAnswers:
  final case class NoteBody(text: Option[String] = None)
  final case class NoteAnswer(noteId: String, affiliateId: String, brandId: String, text: String, authorId: String, createdAt: Instant)
  final case class NoteCreated(noteId: String)
  final case class AuditAnswer(auditId: String, affiliateId: String, brandId: String, event: String, actorId: String, recordedAt: Instant,
      details: Map[String, String])
  final case class NotificationAnswer(notificationId: String, `type`: String, body: String, brandId: Option[String], createdAt: Instant,
      read: Boolean)
  final case class FlagAnswer(flagId: String, playerId: String, affiliateId: String, brandId: String, period: String, reason: String,
      description: String, flaggedAt: Option[Instant], dismissed: Boolean, dismissedBy: Option[String], dismissedAt: Option[Instant],
      dismissalReason: Option[String])
  final case class DismissBody(reason: Option[String] = None)
  final case class CreativeBody(name: Option[String] = None, creativeType: Option[String] = None, fileUrl: Option[String] = None,
      dimensions: Option[String] = None, description: Option[String] = None)
  final case class CreativeCreated(creativeId: String)
  final case class UnattributedFact(factId: String, kind: String, day: String, channel: String, reason: String)
  final case class Disagreeing(playerId: String, boundAffiliateId: Option[String], factId: String, affiliateId: String, linkId: String)
  /** The brand's facts that paid no one (FR-011), those that disagreed with a binding, and the mismatched and duplicate counts. */
  final case class Unattributed(brandId: String, mismatched: Long, duplicates: Long, facts: Vector[UnattributedFact],
      disagreements: Vector[Disagreeing])

  def flag(s: FlagState): FlagAnswer =
    FlagAnswer(s.flagId, s.pseudonym, s.affiliate, s.brand, s.period, s.reason, s.description, s.raisedAt, s.dismissed, s.dismissedBy,
      s.dismissedAt, s.dismissalReason)

  given noteBody: JsonValueCodec[NoteBody]                       = ProgrammeCodecs.wire[NoteBody]
  given notes: JsonValueCodec[Vector[NoteAnswer]]                = ProgrammeCodecs.wire[Vector[NoteAnswer]]
  given noteCreated: JsonValueCodec[NoteCreated]                 = ProgrammeCodecs.wire[NoteCreated]
  given audits: JsonValueCodec[Vector[AuditAnswer]]              = ProgrammeCodecs.wire[Vector[AuditAnswer]]
  given notifications: JsonValueCodec[Vector[NotificationAnswer]] = ProgrammeCodecs.wire[Vector[NotificationAnswer]]
  given flags: JsonValueCodec[Vector[FlagAnswer]]                = ProgrammeCodecs.wire[Vector[FlagAnswer]]
  given dismissBody: JsonValueCodec[DismissBody]                 = ProgrammeCodecs.wire[DismissBody]
  given creativeBody: JsonValueCodec[CreativeBody]               = ProgrammeCodecs.wire[CreativeBody]
  given creatives: JsonValueCodec[Vector[CreativeState]]         = ProgrammeCodecs.wire[Vector[CreativeState]]
  given creativeCreated: JsonValueCodec[CreativeCreated]         = ProgrammeCodecs.wire[CreativeCreated]
  given unattributed: JsonValueCodec[Unattributed]               = ProgrammeCodecs.wire[Unattributed]

import RecordAnswers.{*, given}

final class Records(api: Api):
  private def audit(affiliate: String, brand: String, action: String, by: String, details: Map[String, String]): Unit =
    Networks(api).audit(affiliate, Some(brand), action, by, details)

  private def entries(affiliate: String, kind: String, brand: String): Vector[ActivityRow] =
    api.views.forView(Activity).ask(Activity.ofKind, "affiliate" -> affiliate, "kind" -> kind).filter(_.brand.contains(brand))

  // ── notes and the audit (O23 to O25) ──────────────────────────────────────────────────────────

  def notesOf(affiliate: String, brand: String): Respond[Bytes] =
    ok(entries(affiliate, Activity.NoteKind, brand).map(r => NoteAnswer(r.id, r.affiliate, brand, r.details.getOrElse("text", ""), r.actor, r.at)))

  def note(affiliate: String, brand: String, b: NoteBody, by: String): Respond[Bytes] =
    b.text.map(_.trim).filter(_.nonEmpty) match
      case None => invalid("text is required")
      case Some(text) =>
        val id = Note.newId()
        api.components.forKeyValueEntity(EntityId(id)).call(Note.write).invoke(WriteNote(affiliate, brand, text, by, api.now())): Unit
        audit(affiliate, brand, "NOTE_ADDED", by, Map("detail" -> text.take(120)))
        answer(201, NoteCreated(id))

  def auditOf(affiliate: String, brand: String): Respond[Bytes] =
    ok(entries(affiliate, Activity.Audit, brand).map(r => AuditAnswer(r.id, r.affiliate, brand, r.action, r.actor, r.at, r.details)))

  /** The affiliate's notifications at the brand, and those of no brand (M24's, as a manager reads them, O34). */
  def notificationsOf(affiliate: String, brands: Vector[String]): Vector[NotificationAnswer] =
    api.views.forView(Activity).ask(Activity.ofKind, "affiliate" -> affiliate, "kind" -> Activity.NotificationKind)
      .filter(_.brand.forall(brands.contains))
      .map(r => NotificationAnswer(r.id, r.action, r.details.getOrElse("body", ""), r.brand, r.at, r.details.contains("readAt")))

  // ── fraud flags (O48, O49) ────────────────────────────────────────────────────────────────────

  def flagsOf(brand: String): Respond[Bytes] = ok(api.views.forView(FraudFlags).ask(FraudFlags.ofBrand, "brand" -> brand).map(RecordAnswers.flag))

  /** The affiliate is told (`FlagNotices`), and the dismissal audited. */
  def dismiss(brand: String, flag: String, b: DismissBody, by: String): Respond[Bytes] =
    b.reason.map(_.trim).filter(_.nonEmpty) match
      case None => invalid("reason is required")
      case Some(reason) =>
        val s = api.components.forKeyValueEntity(FraudFlag.entityId(brand, flag)).call(FraudFlag.dismiss).invoke(DismissFlag(by, reason, api.now()))
        audit(s.affiliate, brand, "FRAUD_DISMISSED", by, Map("flagId" -> flag, "reason" -> reason))
        ok(Ok(true))

  // ── creatives (O50 to O52) ────────────────────────────────────────────────────────────────────

  def creativesOf(brand: String): Respond[Bytes] = ok(api.views.forView(Creatives).ask(Creatives.liveOf, "brand" -> brand))

  def creative(brand: String, b: CreativeBody, by: String): Respond[Bytes] =
    b.name.map(_.trim).filter(_.nonEmpty) match
      case None => invalid("name is required")
      case Some(name) =>
        val id = Creative.newId()
        api.components.forKeyValueEntity(EntityId(id)).call(Creative.write)
          .invoke(WriteCreative(id, brand, name, b.creativeType, b.fileUrl, b.dimensions, b.description, by, api.now())): Unit
        audit("", brand, "CREATIVE_ADDED", by, Map("creativeId" -> id))
        answer(201, CreativeCreated(id))

  def deleteCreative(brand: String, id: String, by: String): Respond[Bytes] =
    val c = api.components.forKeyValueEntity(EntityId(id))
    if c.call(Creative.get).invoke().brand != brand then refuse(404, "not_found")
    else
      c.call(Creative.delete).invoke(api.now()): Unit
      audit("", brand, "CREATIVE_DELETED", by, Map("creativeId" -> id))
      ok(Ok(true))

  // ── what paid no one ──────────────────────────────────────────────────────────────────────────

  def unattributedOf(brand: String): Respond[Bytes] =
    val b     = api.brand(brand)
    val facts = api.views.forView(Inbox).ask(Inbox.unattributed, "brand" -> brand)
      .map(r => UnattributedFact(r.factId, r.kind, r.day, r.channel, r.outcome.getOrElse("").stripPrefix("unattributed:")))
    val disagreeing = api.views.forView(Players).ask(Players.disagreeing, "brand" -> brand).flatMap { p =>
      p.disagreements.map(d => Disagreeing(p.pseudonym, p.affiliate, d.factId, d.affiliate, d.link))
    }
    ok(Unattributed(brand, b.mismatches, b.duplicates, facts, disagreeing))
