package io.eitheror.casino.kyc.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.record.{Actor, Uploader}

import Answers.*
import com.thinkmorestupidless.ankka.http.openapi.Description
import io.eitheror.casino.contract.http.Json as JsonBody

/** `/v1/casino/kyc/<slug>`: the player's own documents (FR-002); the `{playerId}` segment must be the token's player. */
final class PlayerEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/kyc/${ctx.slug}"):
  val acl: Acl = Routes.player(ctx.slug, ctx.authenticate())

  private val documents = DocumentRoutes(ctx)

  private def mine(playerId: String)(f: => Reply): Reply =
    if !Routes.playerId(principal).contains(playerId) then refused("player_id_mismatch") else f

  private def body(text: String)(f: com.fasterxml.jackson.databind.JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  // Spec 031: each route described for the player SDK (contracts/routes.md's player table).
  override protected def description: Description = Described.refusals

  described(_.summary("The signed-in player's verification: status, each category's latest document, what the profile lacks")
    .answers[Described.Documents](200).refuses(403, Described.mine).tag("player")) {
    get("/{playerId}/documents") { (p: String) => mine(p)(documents.read(p)) }
  }
  described(_.summary("Offer an upload: a link to put one file of a category to, for an hour")
    .body[Described.Upload].answers[Described.Offered](200).refuses(403, Described.mine)
    .refuses(400, "invalid_body, invalid_category, file_rejected").refuses(409, "document_locked, account_closed")
    .refuses(503, "storage_unavailable").tag("player")) {
    postBody("/{playerId}/uploads") { (p: String, b: JsonBody) => mine(p)(body(b.text)(documents.upload(p, _, Uploader.Player))) }
  }
  described(_.summary("Confirm an upload: the file put is checked and recorded as the category's document")
    .body[Described.Confirm].answers[Described.Confirmed](200).refuses(403, Described.mine)
    .refuses(400, "invalid_body, file_rejected").refuses(404, "document_not_found, upload_not_found")
    .refuses(409, "document_locked").refuses(410, "upload_expired").refuses(503, "storage_unavailable").tag("player")) {
    postBody("/{playerId}/documents") { (p: String, b: JsonBody) => mine(p)(body(b.text)(documents.confirm(p, _))) }
  }
  described(_.summary("A link to view one of the signed-in player's documents, for fifteen minutes")
    .answers[Described.View](200).refuses(403, Described.mine).refuses(404, "document_not_found")
    .refuses(410, "document_erased").refuses(503, "storage_unavailable").tag("player")) {
    get("/{playerId}/views/{documentId}") { (p: String, d: String) => mine(p)(documents.view(p, d, Actor.Player)) }
  }
