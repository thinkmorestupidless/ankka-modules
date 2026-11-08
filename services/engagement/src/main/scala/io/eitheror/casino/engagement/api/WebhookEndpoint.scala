package io.eitheror.casino.engagement.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.engagement.{EngagementRuntime, Json}
import io.eitheror.casino.engagement.Json.*
import io.eitheror.casino.engagement.clients.{Players, Rewards}

import java.nio.charset.StandardCharsets.UTF_8

import Answers.*

/**
 * `/v1/casino/customerio` (FR-017 to FR-019; contracts/routes.md): Customer.io's campaign actions for the brand, served
 * to anyone and acted on only under the brand's signature. ankka reads the body before the handler runs, so the
 * original's `could_not_read_body` is ankka's own refusal of a body it cannot read.
 */
final class WebhookEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/casino/customerio"):
  val acl: Acl = Routes.public

  private lazy val rewards = Rewards.http(ctx.clients.services, ctx.settings)
  private lazy val players = Players.http(ctx.clients.services, ctx.settings)

  postBody("/webhook/{brand}") { (brand: String, text: String) =>
    Webhook.serve(ctx, brand, text, request.header, rewards, players)
  }

object Webhook:
  val Actions: Set[String] = Set("grant_bonus", "send_message", "target_offer")

  /** The answer, every refusal counted on the brand's status by its code (contracts/routes.md). */
  def serve(ctx: Ctx, brand: String, text: String, header: String => Option[String], rewards: Rewards, players: Players): Answer =
    val (answer, refusal) = decide(ctx, brand, text, header, rewards, players)
    refusal.foreach(EngagementRuntime.refused)
    answer

  private def refused(a: Answer, code: String): (Answer, Option[String]) = (a, Some(code))

  private def decide(ctx: Ctx, brand: String, text: String, header: String => Option[String], rewards: Rewards,
      players: Players): (Answer, Option[String]) =
    if brand != ctx.slug then refused(wrongBrand, "wrong_brand")
    else
      val config = ctx.config
      val key    = EngagementRuntime.secrets.keys(ctx.clients.secrets, ctx.slug).signing
      Signature.verify(config.configured, key, header("x-cio-timestamp"), header("x-cio-signature"), text.getBytes(UTF_8), ctx.now()) match
        case Left(r) => refused(refusal(r.status, r.code), r.code)
        case Right(()) =>
          Json.parseObject(text) match
            case None => refused(badBody, "invalid_body")
            case Some(body) =>
              body.text("action").filter(Actions) match
                case None         => refused(Webhook.unknown, "unknown_action")
                case Some(action) => act(action, body, rewards, players)

  private val unknown = Answers.Webhook.unknownAction

  /** One action relayed, its neighbour's answer passed through (contracts/routes.md's action table). */
  private def act(action: String, body: JsonNode, rewards: Rewards, players: Players): (Answer, Option[String]) =
    def field(name: String) = body.text(name).map(_.trim).filter(_.nonEmpty)
    action match
      case "grant_bonus" =>
        (field("player_id"), field("offer_id")) match
          case (Some(p), Some(o)) => (relayed(rewards.award(p, o, field("role"), field("currency"))), None)
          case _                  => refused(Answers.Webhook.grantBonus, "player_id and offer_id required")
      case "send_message" =>
        field("player_id") match
          case None => refused(Answers.Webhook.sendMessage, "player_id required")
          case Some(p) =>
            field("delivery_id") match
              case None => refused(Answers.Webhook.deliveryId, "delivery_id_required")
              case Some(d) => (relayed(players.message(p, d, body.textOr("title", ""), body.textOr("body", ""))), None)
      case _ =>
        (field("player_id"), field("role")) match
          case (Some(p), Some(r)) =>
            (relayed(rewards.target(p, r, Option(body.get("context")).filterNot(_.isNull))), None)
          case _ => refused(Answers.Webhook.targetOffer, "player_id and role required")
