package io.eitheror.casino.rg.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.rg.Json
import io.eitheror.casino.rg.Json.*
import io.eitheror.casino.rg.profile.*

import java.nio.charset.StandardCharsets.UTF_8

import Answers.*

/**
 * `/v1/casino/rg-service/<slug>` (spec 017 amendments R1–R4): `support-agent`'s reads and the changes a player
 * approved in the chat. Each change is the player's own command, as the player route runs it, under the body's
 * `key` in [[RgKeys]]: a repeat answers the first outcome, the key with another body is 409 `key_reused`, and a
 * key older than the last 64 is 409 `key_expired`. An answer of 5xx is not kept, so its repeat runs again.
 */
final class SupportEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/rg-service/${ctx.slug}"):
  val acl: Acl = Routes.serviceOf("support-agent")

  private val profileRoutes = ProfileRoutes(ctx)

  private def keys(playerId: String) = ctx.clients.componentClient.forKeyValueEntity(RgKeys.id(playerId))

  private def replay(r: KeptReply): Reply = Respond(Bytes("application/json", r.body.getBytes(UTF_8)), r.status, Vector.empty)

  /** Runs `change` once under the body's key; `command` names it and the body's fields it reads, for the hash. */
  private def keyed(playerId: String, text: String, command: JsonNode => Seq[String])(change: => Reply): Reply =
    Json.parseObject(text).fold(badBody) { b =>
      b.text("key") match
        case None => refusal(400, "key_required", "key is required")
        case Some(key) =>
          val hash = RgKeysState.hash(command(b)*)
          keys(playerId).call(RgKeys.check).invoke(KeyAsk(key, hash)) match
            case KeyCheck.Repeat(r) => replay(r)
            case KeyCheck.Reused    => refusal(409, "key_reused", "The key was used for another change")
            case KeyCheck.Expired   => refusal(409, "key_expired", "The key is too old to answer again")
            case KeyCheck.Fresh =>
              val reply = change
              if reply.status >= 500 then reply
              else replay(keys(playerId).call(RgKeys.record).invoke(KeyEntry(key, hash, KeptReply(reply.status, String(reply.body.body, UTF_8)))))
    }

  private def field(b: JsonNode, name: String): String = b.text(name).getOrElse("")

  get("/{player}/limits") { (p: String) => profileRoutes.status(p, query.raw("currency")) }

  putBody("/{player}/limits/{type}") { (p: String, t: String, text: String) =>
    keyed(p, text, b => Seq("limit", t.toUpperCase, field(b, "value"), field(b, "currency").toUpperCase))(
      profileRoutes.setLimit(p, t, text, Actor.Player))
  }

  putBody("/{player}/cooling-off") { (p: String, text: String) =>
    keyed(p, text, b => Seq("cooling-off", field(b, "until")))(profileRoutes.setBreak(p, text, Actor.Player))
  }

  // An exclusion already in place is what was asked: 200, as the first answered (R4).
  putBody("/{player}/permanent-exclusion") { (p: String, text: String) =>
    keyed(p, text, _ => Seq("permanent-exclusion")) {
      val r = profileRoutes.exclude(p, "permanent", Actor.Player)
      if r.status == 409 && String(r.body.body, UTF_8).contains("\"already_excluded\"") then ok(Json.obj("status" -> "self_excluded")) else r
    }
  }
