package io.eitheror.casino.engagement.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.Json.*
import io.eitheror.casino.engagement.events.{Classes, SendClass}
import io.eitheror.casino.engagement.profile.{Profile, ProfileState}
import io.eitheror.casino.engagement.reads.SendsView
import io.eitheror.casino.engagement.send.{DataField, Kind, Outcome, Send, SendRequest, SendState}

import java.time.Instant
import scala.jdk.CollectionConverters.*

import Answers.*

/**
 * `/v1/casino/brands/<slug>/engagement` (contracts/routes.md): the brand's own services ask for one of FR-014's seven
 * transactional sends. The send is the `Send` workflow keyed `<trigger>/<key>`, so a repeat of the key claims nothing
 * and answers the first outcome. The links are the caller's data: never logged.
 */
final class SendEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/engagement"):
  val acl: Acl = Routes.service()

  postBody("/sends") { (text: String) => SendRoute.serve(ctx, text) }

  withAcl(Routes.playersOnly) {
    post("/erasure/players/{player}") { (player: String) => ok(Erasure.erase(ctx, player)) }
  }

/**
 * The player's erasure, for `players` (FR-016, research R15). The profile is erased first, so a send that starts
 * after it is discarded at its first step; every open send of the subject is then ended `discarded`; then a `Send`
 * of trigger `erasure` asks Customer.io to delete the person, and a CRM outage parks it for an operator like any
 * send. A brand with no CRM configured has no person to delete. A repeat answers the first deletion's outcome.
 */
object Erasure:
  /** How long the route waits for the deletion's first attempt. */
  val Bound: java.time.Duration = java.time.Duration.ofSeconds(10)

  def erase(ctx: Ctx, player: String): JsonNode =
    ctx.components.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(player)).call(Profile.erase).invoke(): Unit
    val open = Iterator.from(1).map(p => SendsView.byPlayer(ctx.sends, player, p).rows).takeWhile(_.nonEmpty).flatten
      .filter(r => r.trigger != Send.Erasure && (r.outcome == Outcome.Pending || r.outcome == Outcome.Parked)).toVector
    // The view may lag: a send is ended only when its own state says it is still open.
    val discarded = open.count(r => !ctx.sendState(r.key).ended && ctx.send(r.key).call(Send.erase).invoke().outcome == Outcome.Discarded)
    val crm =
      if !ctx.config.configured then "none"
      else
        val r = SendRequest(s"${Send.Erasure}/$player", player, Send.Erasure, None, SendClass.Service.wire, SendRequest.Crm,
          "", ctx.now(), calls = Vector(Kind.Delete))
        ctx.send(r.key).call(Send.start).invoke(r): Unit
        val until = System.nanoTime() + Bound.toNanos
        def settled(): SendState =
          val s = ctx.sendState(r.key)
          if s.ended || s.outcome == Outcome.Parked || System.nanoTime() >= until then s
          else
            Thread.sleep(50)
            settled()
        if settled().outcome == Outcome.Accepted then "deleted" else "failed"
    Json.obj("service" -> "engagement", "subject" -> s"player/$player", "crm" -> crm, "sends" -> discarded)

object SendRoute:
  /** How long the route waits for the first attempt's outcome (contracts/routes.md). */
  val Bound: java.time.Duration = java.time.Duration.ofSeconds(2)

  private val required = Vector("key", "playerId", "trigger", "to")

  /** The caller's link, by the name the trigger's template reads (the original's `send*` methods). */
  def linkField(trigger: String): String = if trigger == "PasswordResetRequest" then "reset_link" else "verification_link"

  /** The send a body asks for, or the refusal (pure). */
  def request(body: JsonNode, at: Instant): Either[Answer, SendRequest] =
    val missing = required.filter(f => body.text(f).forall(_.trim.isEmpty))
    if missing.nonEmpty then Left(missingFields(missing))
    else
      val trigger = body.text("trigger").get
      if !Classes.sendOnly(trigger) then Left(refusal(422, "unknown_trigger", "trigger" -> trigger))
      else
        val player = body.text("playerId").get
        val sent   = Option(body.get("data")).filter(_.isObject).toVector.flatMap(_.properties.asScala.toVector)
          .collect { case e if e.getValue.isTextual => e.getKey -> e.getValue.asText }
        val link   = body.text("link").filter(_.nonEmpty).map(linkField(trigger) -> _)
        val data   = (sent.filterNot(d => link.exists(_._1 == d._1)) ++ link).map((k, v) => DataField(k, ProfileState.personal(player, v)))
        Right(SendRequest(s"$trigger/${body.text("key").get}", player, trigger, None, SendClass.Service.wire, SendRequest.Email,
          "", at, transactional = Some(trigger), recipient = Some(ProfileState.personal(player, body.text("to").get.trim)),
          data = data, calls = Vector(Kind.Transactional)))

  /** The answer for a send's state, once its first attempt has an outcome (pure); a send still trying is taken. */
  def answer(s: SendState, timedOut: Boolean): Option[JsonNode] =
    def status(st: String, reason: Option[String] = None) =
      Some(Json.obj("sendId" -> s.request.fold("")(_.key), "status" -> st, "reason" -> reason))
    s.outcome match
      case Outcome.Accepted | Outcome.Parked                  => status("accepted")
      case Outcome.NoConfiguration                            => status("not_configured")
      case Outcome.MarketingSuppressed | Outcome.AccountClosed => status("suppressed", Some(s.outcome))
      case Outcome.NoAddress                                  => status("refused", Some(Outcome.NoAddress))
      case Outcome.Refused =>
        status("refused", s.calls.lastOption.flatMap(_.status).map(_.toString).orElse(s.reason))
      case Outcome.Pending if !timedOut                       => None
      case Outcome.Pending                                    => status("accepted")
      case other                                              => status("refused", Some(other))

  def serve(ctx: Ctx, text: String): Answer =
    Json.parse(text).filter(_.isObject) match
      case None => badBody
      case Some(body) =>
        request(body, ctx.now()) match
          case Left(refused) => refused
          case Right(r) =>
            ctx.send(r.key).call(Send.start).invoke(r): Unit
            val until = System.nanoTime() + Bound.toNanos
            def poll(): JsonNode =
              val timedOut = System.nanoTime() >= until
              answer(ctx.sendState(r.key), timedOut) match
                case Some(a) => a
                case None =>
                  Thread.sleep(50)
                  poll()
            ok(poll())
