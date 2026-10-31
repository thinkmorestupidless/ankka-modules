package io.eitheror.casino.kyc.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.record.{Actor, Uploader}

import Answers.*

/** `/v1/casino/kyc/<slug>`: the player's own documents (FR-002); the `{playerId}` segment must be the token's player. */
final class PlayerEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/kyc/${ctx.slug}"):
  val acl: Acl = Routes.player(ctx.slug, ctx.authenticate())

  private val documents = DocumentRoutes(ctx)

  private def mine(playerId: String)(f: => Reply): Reply =
    if !Routes.playerId(principal).contains(playerId) then refused("player_id_mismatch") else f

  private def body(text: String)(f: com.fasterxml.jackson.databind.JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  get("/{playerId}/documents") { (p: String) => mine(p)(documents.read(p)) }
  postBody("/{playerId}/uploads") { (p: String, text: String) => mine(p)(body(text)(documents.upload(p, _, Uploader.Player))) }
  postBody("/{playerId}/documents") { (p: String, text: String) => mine(p)(body(text)(documents.confirm(p, _))) }
  get("/{playerId}/views/{documentId}") { (p: String, d: String) => mine(p)(documents.view(p, d, Actor.Player)) }
