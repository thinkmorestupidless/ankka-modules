package io.eitheror.casino.kyc.api

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.record.*

import java.util.UUID

import Answers.*

/**
 * Uploads, confirmations, views and the read, as the player's and the operator's routes share them
 * (FR-004 to FR-008). The store is asked only for what the record cannot decide: a signed link, and at
 * confirm the object's size and type. A store that cannot be reached is `503 storage_unavailable`.
 */
final class DocumentRoutes(ctx: Ctx):

  def read(playerId: String, operator: Boolean = false): Reply =
    ok(Render.documents(ctx.state(playerId), ctx.policy, ctx.now(), operator))

  /** An upload offered, then its link signed for an hour with the content type signed (R7). */
  def upload(playerId: String, body: JsonNode, by: Uploader): Reply =
    val documentId = UUID.randomUUID().toString
    val at         = ctx.now()
    val category   = body.textOr("category", "")
    val answer = ctx.record(playerId).call(KycRecord.requestUpload)
      .invoke(RequestUpload(playerId, category, body.textOr("contentType", ""), by, documentId, at))
    answer.refusal match
      case Some(r) if r.code == "invalid_category" => refused(r.copy(fields = Vector(category)))
      case Some(r) => refused(r)
      case None =>
        val offer = answer.state.offers(documentId)
        ctx.store.signPut(offer.objectName, offer.contentType, ctx.settings.uploadLink).fold(
          _ => storageUnavailable,
          url => ok(Json.obj("documentId" -> documentId, "url" -> url, "objectName" -> offer.objectName,
            "contentType" -> offer.contentType, "expiresAt" -> offer.offeredAt.plus(Offer.Lifetime).toString))
        )

  /**
   * The upload confirmed (FR-007): the object read back from the store, refused and deleted when it is
   * larger than the brand takes or not of the type offered, then recorded. An id the record does not
   * hold, or an offer past its hour, is the record's to refuse.
   */
  def confirm(playerId: String, body: JsonNode): Reply =
    val documentId = body.textOr("documentId", "")
    val at         = ctx.now()
    val offer      = ctx.state(playerId).offers.get(documentId).filterNot(KycRecord.isExpired(_, at))
    val checked: Either[Reply, (Long, String)] = offer match
      case None => Right((0L, ""))
      case Some(o) =>
        ctx.store.head(o.objectName) match
          case Left(_)        => Left(storageUnavailable)
          case Right(None)    => Left(refused("upload_not_found"))
          case Right(Some(h)) =>
            val rule = if h.size > ctx.settings.maxUploadBytes then Some("size") else Option.when(h.contentType != o.contentType)("contentType")
            rule match
              case Some(r) => Left(ctx.store.delete(o.objectName).fold(_ => storageUnavailable, _ => refused("file_rejected", "rule" -> r)))
              case None    => Right((h.size, h.contentType))
    checked match
      case Left(reply) => reply
      case Right((size, contentType)) =>
        val answer = ctx.record(playerId).call(KycRecord.confirmUpload)
          .invoke(ConfirmUpload(documentId, size, contentType, ctx.policy.required, at))
        answer.refusal.fold(withDocument(answer.state, documentId))(refused)

  /** A link to that object alone for fifteen minutes (FR-008), signed before the view is recorded. */
  def view(playerId: String, documentId: String, requester: Actor): Reply =
    val at = ctx.now()
    val document = ctx.state(playerId) match
      case s if s.erased => None
      case s             => s.document(documentId)
    def issue(): KycAnswer = ctx.record(playerId).call(KycRecord.issueView).invoke(IssueView(documentId, requester, at))
    document match
      case None => issue().refusal.fold(refused("document_not_found"))(refused)
      case Some(d) =>
        ctx.store.signGet(d.objectName, ctx.settings.viewLink).fold(
          _ => storageUnavailable,
          url => issue().refusal.fold(ok(Json.obj("url" -> url, "expiresAt" -> at.plus(ctx.settings.viewLink).toString)))(refused)
        )

  private def withDocument(s: KycState, documentId: String): Reply =
    ok(Json.obj("document" -> s.document(documentId).map(Render.document(_, latest = true)), "status" -> s.status.wire))
