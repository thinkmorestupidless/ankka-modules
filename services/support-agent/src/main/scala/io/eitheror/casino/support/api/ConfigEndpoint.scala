package io.eitheror.casino.support.api

import com.fasterxml.jackson.databind.JsonNode
import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.support.{Json, SupportCodecs}
import io.eitheror.casino.support.config.{AgentConfiguration, ConfigBody, ConfigNote, ConfigVersion}
import io.eitheror.casino.support.domain.ConfigRules
import io.eitheror.casino.support.limits.Budget
import io.eitheror.casino.support.reads.{ConversationRow, ConversationsByPlayer}

import java.nio.charset.StandardCharsets.UTF_8

/**
 * The operator's routes (contracts/routes.md), under `/v1/casino/agent-config/<slug>`: the brand's configuration, a
 * version of it, a change of it, its status, and its conversations. Changing the prompt or the personal fields is the
 * brand admin's (or a platform admin's); the other roles the ACL admits change the rest.
 */
final class ConfigEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/agent-config/${ctx.slug}"):
  val acl: Acl = Routes.operatorHolding(ctx.slug, "brand_admin", "resource_admin", "platform_admin")(ctx.authenticate())

  get("/") { () => Operators.shown(ctx) }

  putBody("/") { (text: String) => Operators.put(ctx, text, principal.subject, principal.roles) }

  get("/versions/{version}") { (version: String) => Operators.version(ctx, version) }

  get("/status") { () => Operators.status(ctx) }

  get("/conversations") { () =>
    Operators.conversations(ctx, request.query.raw("player").filter(_.nonEmpty), request.query.raw("offset"))
  }

object Operators:
  type Answer = Respond[Bytes]

  /** The fields only a brand admin changes. */
  val AdminFields: Set[String] = Set("prompt", "personalFields")
  private val AdminRoles       = Set("brand_admin", "platform_admin")

  private given JsonValueCodec[ConfigBody]      = SupportCodecs.shown[ConfigBody]
  private given JsonValueCodec[ConfigNote]      = SupportCodecs.shown[ConfigNote]
  private given JsonValueCodec[ConversationRow] = SupportCodecs.shown[ConversationRow]

  private def node[A: JsonValueCodec](a: A): JsonNode = Json.mapper.readTree(writeToString(a))

  private def json(status: Int, body: JsonNode): Answer =
    Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status, Vector.empty)
  private def ok(body: JsonNode): Answer = json(200, body)
  private def refusal(status: Int, error: String, fields: (String, Any)*): Answer = json(status, Json.obj(("error" -> error) +: fields*))

  /** `GET /`: the version, its body and the changes kept for the audit. */
  def shown(ctx: Ctx): Answer =
    val c = ctx.configuration
    ok(Json.obj("version" -> c.version, "config" -> node(c.current), "recentChanges" -> Json.arr(c.recentChanges.map(node(_))*)))

  /** `PUT /`: the body read over the current one and checked, the admin's fields held to the admin, then the next version. */
  def put(ctx: Ctx, text: String, operator: String, roles: Set[String]): Answer =
    val sent = Json.parse(text).orNull
    // Read and checked over the current body on each attempt, so a put read again after another keeps the other's.
    def next(current: ConfigBody): Either[Answer, ConfigBody] =
      ConfigRules.read(sent, current, ctx.settings.realMoney, ctx.settings.judge.isDefined) match
        case Left(r) => Left(refusal(r.status, r.code, (r.field.map("field" -> _).toVector :+ ("message" -> r.message))*))
        case Right(body) =>
          ConfigRules.changes(current, body)._1.find(f => AdminFields(f) && !roles.exists(AdminRoles)) match
            case Some(f) => Left(refusal(403, Routes.Forbidden, "field" -> f, "message" -> s"Changing $f needs the brand_admin permission"))
            case None    => Right(body)
    AgentConfiguration.put(ctx.components, ctx.slug, operator, ctx.now())(next).fold(identity, p => ok(Json.obj("version" -> p.version)))

  /** `GET /versions/{version}`: that version's body; version 0 is the defaults. */
  def version(ctx: Ctx, version: String): Answer =
    version.toIntOption.filter(v => v >= 0 && v <= ctx.configuration.version) match
      case None    => refusal(404, "not_found")
      case Some(v) => ok(node(ConfigVersion.body(ctx.components, ctx.slug, v)))

  /** `GET /status` (FR-024): whether the agent is on, which guardrails run, the day's budget and the conversations. */
  def status(ctx: Ctx): Answer =
    val c      = ctx.configuration.current
    val s      = ctx.settings
    val now    = ctx.now()
    val used   = ctx.components.forKeyValueEntity(Budget.id(ctx.slug, now)).call(Budget.get).invoke().tokens
    val counts = ConversationsByPlayer.counts(ctx.clients.viewClient.forView(ConversationsByPlayer), now)
    def on(b: Boolean) = if b then "on" else "off"
    ok(Json.obj(
      "enabled"       -> c.enabled,
      "realMoney"     -> s.realMoney,
      "safeguarding"  -> on(s.judge.isDefined && c.safeguarding(s.realMoney)),
      "judged"        -> on(s.judge.isDefined),
      "budget"        -> Json.obj("used" -> used, "limit" -> c.dailyTokens, "spent" -> (used >= c.dailyTokens)),
      "conversations" -> Json.obj("open" -> counts.open, "awaiting" -> counts.awaiting, "lastDay" -> counts.lastDay)
    ))

  /** `GET /conversations?player=&offset=`: 50 rows, newest activity first. */
  def conversations(ctx: Ctx, player: Option[String], offset: Option[String]): Answer =
    offset.fold(Some(0))(_.toIntOption.filter(_ >= 0)) match
      case None => refusal(400, "invalid_offset", "message" -> "offset is not a whole number from 0")
      case Some(from) =>
        val rows = ConversationsByPlayer.page(ctx.clients.viewClient.forView(ConversationsByPlayer), player, from)
        ok(Json.obj("conversations" -> Json.arr(rows.map(node(_))*)))
