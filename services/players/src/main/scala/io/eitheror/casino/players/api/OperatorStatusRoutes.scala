package io.eitheror.casino.players.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Principal, Respond}
import io.eitheror.casino.players.{Json, PlayersRuntime}
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.clients.{Neighbour, Reply as NeighbourReply}
import io.eitheror.casino.players.config.*
import io.eitheror.casino.players.fraud.{DecideCase, FraudCaseEntity, FraudCaseStatus, FraudCasesView}
import io.eitheror.casino.players.inbox.*
import io.eitheror.casino.players.player.*
import io.eitheror.casino.players.reads.{ActivityCategory, ActivityView, WalletSummaryView}
import io.eitheror.casino.players.realm.RealmRefusal
import io.eitheror.casino.players.sessions.{SessionRow, SessionsView}

import java.time.{Duration, Instant, LocalDate}
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

import Answers.*

/**
 * What an operator, `kyc` and `responsible-gambling` do to a player's status (FR-031 to FR-034, FR-041),
 * with the operator's notes, messages and the brand's configuration. A transition is one entity event:
 * the fact and the audit record are made from it, never here. A suspension, an exclusion and a closure
 * end every session through [[Cascade]].
 */
final class OperatorStatusRoutes(ctx: Ctx):
  import OperatorStatusRoutes.*
  import ProfileRoutes.answer

  private def components         = ctx.clients.componentClient
  private def views              = ctx.clients.viewClient
  private def player(id: String) = components.forEventSourcedEntity(PlayerEntity.id(id))
  private def stateOf(id: String): PlayerState = player(id).call(PlayerEntity.get).invoke()
  private def now(): Instant     = PlayersRuntime.now()
  private def body(text: String): JsonNode = Json.parse(text).filter(_.isObject).getOrElse(Json.obj())

  private def known(playerId: String)(f: PlayerState => Reply): Reply =
    val s = stateOf(playerId)
    if !s.exists then refusal(404, "player_not_found", "No such player") else f(s)

  /** The realm user enabled or disabled; a refusal is logged, the entity's transition stands. */
  private def enable(playerId: String, enabled: Boolean): Unit =
    val realm = ctx.neighbours.realm
    realm.userOf(playerId).flatMap(realm.setEnabled(_, enabled)) match
      case Left(r)  => log.warn("{} of {}: the realm user was not changed: {}", if enabled then "reopen" else "close", playerId, r.code)
      case Right(_) => ()

  // ── the status ─────────────────────────────────────────────────────────────────────────────────

  def suspend(p: Principal, playerId: String, text: String): Reply =
    known(playerId) { _ =>
      val reason = body(text).text("reason").map(_.trim).filter(_.nonEmpty).getOrElse(DefaultSuspendReason)
      suspended(player(playerId).call(PlayerEntity.suspend).invoke(Suspend(reason, Some(p.subject), now())), playerId)
    }

  /** `kyc`'s suspension at the end of its window (009 FR-025): a repeat under the same deadline is the first. */
  def suspendByKyc(playerId: String, text: String): Reply =
    known(playerId) { _ =>
      val reason = body(text).text("reason").map(_.trim).filter(_.nonEmpty).getOrElse("kyc_window_expired")
      suspended(player(playerId).call(PlayerEntity.suspend).invoke(Suspend(reason, Some("kyc"), now())), playerId)
    }

  private def suspended(d: Done, playerId: String): Reply =
    answer(d) {
      if d.changed then Cascade.endAll(ctx, playerId, Cascade.Suspended): Unit
      ok(Json.obj("status" -> Status.Suspended))
    }

  def reinstate(p: Principal, playerId: String): Reply =
    known(playerId) { _ =>
      answer(player(playerId).call(PlayerEntity.reinstate).invoke(Reinstate(Some(p.subject), None, now())))(ok(Json.obj("status" -> Status.Active)))
    }

  /** `responsible-gambling`'s exclusion (010 FR-018, FR-019): the status, the time kept, the sessions ended `suspended`. */
  def exclude(playerId: String, text: String): Reply =
    known(playerId) { _ =>
      val b     = body(text)
      val at    = b.text("at").flatMap(t => Try(Instant.parse(t)).toOption).getOrElse(now())
      val until = b.text("until").flatMap(t => Try(Instant.parse(t)).toOption)
      val d     = player(playerId).call(PlayerEntity.setSelfExcluded).invoke(SetSelfExcluded(until, at))
      answer(d) {
        if d.changed then Cascade.endAll(ctx, playerId, Cascade.Suspended): Unit
        ok(Json.obj("status" -> Status.SelfExcluded))
      }
    }

  def liftExclusion(playerId: String): Reply =
    known(playerId) { _ =>
      answer(player(playerId).call(PlayerEntity.liftExclusion).invoke(At(now())))(ok(Json.obj("status" -> Status.Active)))
    }

  def close(p: Principal, playerId: String, text: String): Reply =
    known(playerId) { _ =>
      val reason = body(text).text("reason").map(_.trim).filter(_.nonEmpty).getOrElse(DefaultCloseReason)
      closed(player(playerId).call(PlayerEntity.close).invoke(Close(Actor.Operator, Some(p.subject), reason, now())), playerId)
    }

  /** The player's own closure (FR-032a): confirmed with the password; not a self-exclusion, no RG call. */
  def closeMine(p: Principal, playerId: String, text: String): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      known(playerId) { s =>
        val password = body(text).textOr("password", "")
        val email    = s.primary.map(_.address.reveal).getOrElse("")
        if password.isEmpty then refusal(400, "invalid_password", "The password is incorrect")
        else
          ctx.neighbours.realm.verifyPassword(email, password) match
            case Left(RealmRefusal.InvalidCredentials) => refusal(400, "invalid_password", "The password is incorrect")
            case Left(RealmRefusal.Disabled)           => refusal(409, "already_closed", "The account is already closed")
            case Left(_)                               => refusal(503, "service_unavailable", "the realm did not answer")
            case Right(_) =>
              closed(player(playerId).call(PlayerEntity.close).invoke(Close(Actor.Player, Some(playerId), PlayerRequest, now())), playerId)
      }
    }

  private def closed(d: Done, playerId: String): Reply =
    answer(d) {
      if !d.changed then refusal(409, "already_closed", "The account is already closed")
      else
        Cascade.endAll(ctx, playerId, Cascade.Forced): Unit
        enable(playerId, enabled = false)
        ok(Json.obj("status" -> Status.Closed))
    }

  /** Within the brand's retention hold (`retentionDays`, else [[DefaultRetentionDays]]) on the player's request. */
  def reopen(p: Principal, playerId: String, text: String): Reply =
    known(playerId) { _ =>
      body(text).text("reason").map(_.trim).filter(_.nonEmpty) match
        case None => refusal(400, "missing_fields", "reason is required", "fields" -> Json.arr("reason"))
        case Some(reason) =>
          val days = components.forKeyValueEntity(EntityId(ctx.slug)).call(BrandConfigEntity.get).invoke().retentionDays
            .getOrElse(DefaultRetentionDays)
          answer(player(playerId).call(PlayerEntity.reopen).invoke(Reopen(p.subject, reason, Duration.ofDays(days.toLong), now()))) {
            enable(playerId, enabled = true)
            ok(Json.obj("status" -> Status.Active))
          }
    }

  def vipTier(p: Principal, playerId: String, text: String): Reply =
    known(playerId) { _ =>
      val tier = body(text).textOr("tier", "").trim
      player(playerId).call(PlayerEntity.setVipTier).invoke(SetVipTier(tier, Some(p.subject), now())): Unit
      ok(Json.obj("vipTier" -> tier))
    }

  /** The flag, and the note the original wrote beside it. */
  def potVip(p: Principal, playerId: String, text: String): Reply =
    known(playerId) { _ =>
      val flag = body(text).bool("potVip").getOrElse(false)
      val d    = player(playerId).call(PlayerEntity.setPotVip).invoke(SetPotVip(flag, Some(p.subject), now()))
      if d.changed then
        writeNote(playerId, s"PotVIP status ${if flag then "enabled" else "disabled"} by ${author(p)}", author(p), pinned = false): Unit
      ok(Json.obj("potVip" -> flag))
    }

  /**
   * Clears the player's flag and, as the original, dismisses every open case naming them; a dismissed
   * case clears the flag of every player it links.
   */
  def clearSuspectFlag(p: Principal, playerId: String): Reply =
    known(playerId) { s =>
      val open = FraudCasesView.ofPlayer(views, playerId).filter(_.status == FraudCaseStatus.Open).map(_.caseId)
      open.foreach { c =>
        val a = components.forEventSourcedEntity(EntityId(c)).call(FraudCaseEntity.dismiss).invoke(DecideCase(p.subject, now()))
        if a.refusal.isEmpty then FraudRoutes.clear(ctx, c, a.state.playerIds, p.subject)
      }
      s.fraud.map(_.caseIds).getOrElse(Vector.empty).foreach(c =>
        player(playerId).call(PlayerEntity.clearDuplicate).invoke(ClearDuplicate(c, Some(p.subject), now())): Unit)
      ok(Json.obj("playerId" -> playerId, "flagCleared" -> true, "linkedCasesFound" -> open.size))
    }

  // ── the reads ──────────────────────────────────────────────────────────────────────────────────

  /** The original's list: `search`, `status`, `page` (from 1), `pageSize` (1–200, 25); `{items, total}`. */
  def players(search: Option[String], status: Option[String], vipOnly: Option[String], page: Option[String], pageSize: Option[String]): Reply =
    val n    = page.flatMap(_.toIntOption).filter(_ >= 1).getOrElse(1)
    val size = pageSize.flatMap(_.toIntOption).filter(s => s >= 1 && s <= 200).getOrElse(25)
    val all = search.map(_.trim).filter(_.nonEmpty) match
      case Some(q) => PlayersView.find(views, q, ListLimit)
      case None =>
        val st = status.map(_.trim).filter(s => s.nonEmpty && s != "all" && s != "rg-flagged")
        PlayersView.page(views, PlayersView.Filter(status = st), 0, ListLimit)
    val seen = all.filter(r => !vipOnly.contains("true") || r.vipTier.exists(_.nonEmpty))
    ok(Json.obj("items" -> Json.arr(seen.slice((n - 1) * size, n * size).map(row)*), "total" -> seen.size))

  private def row(r: PlayerRow): ObjectNode =
    Json.obj("playerId" -> r.playerId, "twinId" -> s"casino-${ctx.slug}/${r.playerId}", "username" -> r.displayName.reveal,
      "email" -> r.primaryEmail.reveal, "accountStatus" -> r.status, "country" -> r.country.getOrElse(""), "currency" -> r.currency,
      "registeredAt" -> (if r.registeredAt > 0 then Instant.ofEpochMilli(r.registeredAt).toString else ""),
      "vipTier" -> r.vipTier.orNull, "potVip" -> r.potVip, "suspectedDuplicate" -> r.flagged)

  /** Balances in minor units from `money.transactions` (the original answered decimal strings). */
  def wallet(playerId: String): Reply =
    known(playerId) { s =>
      val rows = WalletSummaryView.ofPlayer(views, playerId)
      val w    = rows.find(_.currency == s.currency).orElse(rows.headOption)
      ok(Json.obj("realBalanceMinorUnits" -> w.fold(0L)(_.realBalanceAfter), "bonusBalanceMinorUnits" -> w.fold(0L)(_.restrictedBalanceAfter),
        "currency" -> w.map(_.currency).getOrElse(s.currency)))
    }

  /** The original's operator activity: `category`, `page` from 0, 50 a page, `{events, page, pageSize, hasMore}`. */
  def activity(playerId: String, category: Option[String], page: Option[String]): Reply =
    known(playerId) { _ =>
      val cat  = category.map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(ActivityCategory.all.contains)
      val n    = page.flatMap(_.toIntOption).filter(_ >= 0).getOrElse(0)
      val rows = ActivityView.ofPlayer(views, playerId, cat, n * ActivityPage, ActivityPage + 1)
      ok(Json.obj("events" -> Json.arr(rows.take(ActivityPage).map(ProfileRoutes.event)*), "page" -> n, "pageSize" -> ActivityPage,
        "hasMore" -> (rows.size > ActivityPage)))
    }

  /** The settled rounds from the activity, `fromDate`/`toDate` as ISO dates; `400 invalid_date_range`. */
  def bets(playerId: String, from: Option[String], to: Option[String], limit: Option[String], offset: Option[String]): Reply =
    val f = from.flatMap(d => Try(LocalDate.parse(d.take(10))).toOption)
    val t = to.flatMap(d => Try(LocalDate.parse(d.take(10))).toOption)
    if f.zip(t).exists((a, b) => a.isAfter(b)) then refusal(400, "invalid_date_range", "fromDate is after toDate")
    else
      known(playerId) { _ =>
        val lim  = limit.flatMap(_.toIntOption).filter(_ >= 1).getOrElse(50)
        val off  = offset.flatMap(_.toIntOption).filter(_ >= 0).getOrElse(0)
        def day(at: Long) = LocalDate.ofInstant(Instant.ofEpochMilli(at), java.time.ZoneOffset.UTC)
        val all = ActivityView.ofPlayer(views, playerId, Some(ActivityCategory.Games), 0, ListLimit)
          .filter(r => r.eventType == "round_settled" && f.forall(d => !day(r.at).isBefore(d)) && t.forall(d => !day(r.at).isAfter(d)))
        val bets = all.slice(off, off + lim).map(r => Json.obj("betId" -> r.factId, "amountMinorUnits" -> r.amountMinorUnits.map(Long.box).orNull,
          "currency" -> r.currency.orNull, "description" -> r.description, "createdAt" -> Instant.ofEpochMilli(r.at).toString))
        ok(Json.obj("bets" -> Json.arr(bets*), "summary" -> Json.obj("totalBets" -> all.size, "fromDate" -> from.orNull, "toDate" -> to.orNull),
          "total" -> all.size, "limit" -> lim, "offset" -> off))
      }

  def sessions(playerId: String): Reply =
    known(playerId) { _ =>
      val rows = SessionsView.ofPlayer(views, playerId, SessionRow.Game, 0, ListLimit)
      ok(Json.obj("sessions" -> Json.arr(rows.map(GameSessionRoutes.wire)*)))
    }

  // ── notes ──────────────────────────────────────────────────────────────────────────────────────

  private def author(p: Principal): String = p.claims.get("email").filter(_.nonEmpty).getOrElse(p.subject)

  private def writeNote(playerId: String, text: String, by: String, pinned: Boolean): (String, NoteState) =
    val id = UUID.randomUUID().toString
    val at = now()
    val n  = components.forKeyValueEntity(EntityId(id)).call(NoteEntity.write).invoke(WriteNote(playerId, text, by, at))
    (id, if pinned then components.forKeyValueEntity(EntityId(id)).call(NoteEntity.pin).invoke(PinNote(true, at)) else n)

  def note(p: Principal, playerId: String, text: String): Reply =
    known(playerId) { _ =>
      val b       = body(text)
      val content = b.textOr("content", "").trim
      if content.isEmpty then refusal(400, "missing_fields", "content is required", "fields" -> Json.arr("content"))
      else
        val (id, n) = writeNote(playerId, content, author(p), b.bool("pinImmediately").getOrElse(false))
        json(201, Json.obj("noteId" -> id, "createdAt" -> n.createdAt.map(_.toString).orNull))
    }

  def pin(playerId: String, noteId: String, pinned: Boolean): Reply =
    val ref = components.forKeyValueEntity(EntityId(noteId))
    val n   = ref.call(NoteEntity.get).invoke()
    if !n.exists || n.playerId != playerId then refusal(404, "note_not_found", "No such note")
    else
      ref.call(NoteEntity.pin).invoke(PinNote(pinned, now())): Unit
      ok(Json.obj("isPinned" -> pinned))

  // ── messages ───────────────────────────────────────────────────────────────────────────────────

  def messages(playerId: String, page: Option[String], pageSize: Option[String]): Reply =
    known(playerId) { _ =>
      val n    = page.flatMap(_.toIntOption).filter(_ >= 1).getOrElse(1)
      val size = pageSize.flatMap(_.toIntOption).filter(s => s >= 1 && s <= 100).getOrElse(20)
      val all  = MessagesView.ofPlayer(views, playerId, 0, ListLimit, includeDeleted = true)
      val items = all.slice((n - 1) * size, n * size).map { r =>
        val m = components.forEventSourcedEntity(EntityId(r.messageId)).call(MessageEntity.get).invoke()
        val o = ProfileRoutes.message(r, m)
        o.put("origin", r.origin)
        o.put("deliveredAt", null: String)
        o.put("subjectTemplate", m.subjectTemplate.reveal)
        o.put("bodyTemplate", m.bodyTemplate.reveal)
        o
      }
      ok(Json.obj("items" -> Json.arr(items*), "total" -> all.size, "page" -> n, "pageSize" -> size))
    }

  /**
   * The original's order: the category before any read, a repeat under the same id answered as written,
   * then the player, `account_closed`, `marketing_suppressed`, and the render.
   */
  def message(p: Principal, playerId: String, text: String): Reply =
    val b = body(text)
    b.text("category").filter(Set("service", "marketing")) match
      case None => refusal(422, "category_required", "category must be service or marketing")
      case Some(category) =>
        val id  = b.text("notificationId").orElse(b.text("idempotencyKey")).filter(_.nonEmpty).getOrElse(UUID.randomUUID().toString)
        val ref = components.forEventSourcedEntity(EntityId(id))
        val was = ref.call(MessageEntity.get).invoke()
        if was.exists then json(200, sent(id, was))
        else
          known(playerId) { s =>
            if Status.ended(s.status) then refusal(422, "account_closed", "The account is closed")
            else if category == "marketing" && s.consents.messagesSuppressed then
              refusal(422, "marketing_suppressed", "Messages to this player are suppressed")
            else
              val values = valuesOf(s)
              val subject = b.text("subjectTemplate").orElse(b.text("subject")).getOrElse("")
              val text    = b.text("bodyTemplate").orElse(b.text("body")).getOrElse("")
              (MergeTags.render(subject, values), MergeTags.render(text, values)) match
                case (Left(MergeTags.Refused.UnknownTag(t)), _) => unknownTag(t)
                case (_, Left(MergeTags.Refused.UnknownTag(t))) => unknownTag(t)
                case (Right(sub), Right(rendered)) =>
                  val m = ref.call(MessageEntity.write)
                    .invoke(CreateMessage(playerId, category, MessageOrigin.Operator, sub, rendered, Some(p.subject), now(), subject, text)).state
                  json(201, sent(id, m))
          }

  private def unknownTag(t: String): Reply = refusal(422, "unknown_merge_tag", s"unknown merge tag $t", "tag" -> t)

  private def sent(id: String, m: MessageState): ObjectNode =
    Json.obj("notificationId" -> id, "state" -> (if m.readAt.nonEmpty then "read" else if m.delivered then "delivered" else "sent"),
      "createdAt" -> m.createdAt.map(_.toString).orNull, "subject" -> m.subject.reveal, "body" -> m.body.reveal)

  def mergeTags: Reply =
    ok(Json.obj("tags" -> Json.arr(MergeTags.tags.map(t => Json.obj("name" -> t.name, "label" -> t.label, "example" -> t.example))*)))

  def preview(text: String): Reply =
    val b = body(text)
    known(b.textOr("playerId", "")) { s =>
      val values  = valuesOf(s) ++ MergeTags.demo
      val subject = b.textOr("subjectTemplate", "")
      val text    = b.textOr("bodyTemplate", "")
      val open    = MergeTags.demo.keySet
      val result = for
        rs <- MergeTags.render(subject, values)
        rb <- MergeTags.render(text, values)
        fs <- MergeTags.renderFallback(subject)
        fb <- MergeTags.renderFallback(text)
        us <- MergeTags.unresolved(subject, valuesOf(s))
        ub <- MergeTags.unresolved(text, valuesOf(s))
      yield ok(Json.obj("resolved" -> Json.obj("subject" -> rs, "body" -> rb), "fallback" -> Json.obj("subject" -> fs, "body" -> fb),
        "hasAllValues" -> (us ++ ub).diff(open).isEmpty))
      result.fold({ case MergeTags.Refused.UnknownTag(t) => unknownTag(t) }, identity)
    }

  /** The player's tag values: the profile's first name, the tier, the registration date, the verification. */
  private def valuesOf(s: PlayerState): Map[String, String] =
    Map(
      "first_name"          -> s.profile.get("firstName"),
      "vip_status"          -> s.vipTier,
      "registration_date"   -> s.registeredAt.map(t => java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH)
        .format(t.atZone(java.time.ZoneOffset.UTC))),
      "verification_status" -> Some(if s.kycVerified then "Verified" else "Not verified"),
      "play_now_cta"        -> Some(MergeTags.playNow(s.profile.get("language")))
    ).collect { case (k, Some(v)) => k -> v }

  // ── configuration ──────────────────────────────────────────────────────────────────────────────

  def config(name: String): Reply =
    name match
      case "brand-config"    => ok(brandJson(components.forKeyValueEntity(EntityId(ctx.slug)).call(BrandConfigEntity.get).invoke()))
      case "currency-config" => ok(currencyJson(components.forKeyValueEntity(EntityId(ctx.slug)).call(CurrencyConfigEntity.get).invoke()))
      case "allow-list"      => ok(allowJson(components.forKeyValueEntity(EntityId(ctx.slug)).call(AllowListEntity.get).invoke()))
      case _                 => refusal(404, "config_not_found", s"no configuration $name")

  /** A `PUT` sets the fields it names over the configuration held; `400 invalid_config` names the field. */
  def setBrandConfig(text: String): Reply =
    val ref = components.forKeyValueEntity(EntityId(ctx.slug))
    val c   = ref.call(BrandConfigEntity.get).invoke()
    val b   = body(text)
    val next = c.copy(
      name = b.textOr("name", c.name), legalName = b.textOr("legalName", c.legalName),
      playerSiteBase = b.textOr("playerSiteBase", c.playerSiteBase), reportingZone = b.textOr("reportingZone", c.reportingZone),
      singleSession = b.bool("singleSession").getOrElse(c.singleSession),
      multiAccountDetection = b.bool("multiAccountDetection").getOrElse(c.multiAccountDetection),
      retentionDays = b.long("retentionDays").map(_.toInt).orElse(c.retentionDays),
      features = Option(b.get("features")).filter(_.isObject).fold(c.features)(f =>
        c.features ++ f.properties().toArray(Array.empty[java.util.Map.Entry[String, JsonNode]]).map(e => e.getKey -> e.getValue.asBoolean)))
    configured(ref.call(BrandConfigEntity.set).invoke(next))(brandJson)

  def setCurrencyConfig(text: String): Reply =
    val ref = components.forKeyValueEntity(EntityId(ctx.slug))
    val c   = ref.call(CurrencyConfigEntity.get).invoke()
    val b   = body(text)
    val next = CurrencyConfig(if b.has("offered") then b.strings("offered") else c.offered,
      b.text("defaultCurrency").filter(_.nonEmpty).orElse(c.defaultCurrency))
    configured(ref.call(CurrencyConfigEntity.set).invoke(next))(currencyJson)

  def setAllowList(text: String): Reply =
    val ref = components.forKeyValueEntity(EntityId(ctx.slug))
    configured(ref.call(AllowListEntity.set).invoke(AllowList(body(text).strings("countries").filter(_.trim.nonEmpty).toSet)))(allowJson)

  private def configured[A](a: ConfigAnswer[A])(f: A => ObjectNode): Reply =
    a.refusal match
      case Some(code) => refusal(400, code, s"${a.field.getOrElse("a field")} is not valid", "field" -> a.field.orNull)
      case None       => ok(f(a.config))

  // ── modules (spec 021) ─────────────────────────────────────────────────────────────────────────

  def modules: Reply = agent("GET", "/vendor/modules", None)

  def moduleRequest(p: Principal, text: String): Reply =
    val b = body(text)
    agent("POST", "/vendor/requests", Some(Json.obj("module" -> b.textOr("module", ""), "note" -> b.text("note").orNull, "by" -> p.subject)))

  /** The agent's answer passed through; no answer within the bound is `503 agent unreachable`. */
  private def agent(method: String, path: String, payload: Option[JsonNode]): Reply =
    Try(Await.result(Neighbour.call(ctx.neighbours.call, ctx.neighbours.agent, method, path, payload), AgentBound)).toOption
      .filter(_.status < 500) match
      case None                         => refusal(503, "agent_unreachable", "agent unreachable")
      case Some(r: NeighbourReply) if r.status == 204 => Respond(Bytes("application/json", Array.emptyByteArray), 204, Vector.empty)
      case Some(r)                      => json(r.status, r.body.getOrElse(Json.obj()))

object OperatorStatusRoutes:
  /** The hold a closure starts when the brand names no `retentionDays`: 5 years. */
  val DefaultRetentionDays: Int    = 1825
  val DefaultSuspendReason: String = "Suspended by operator"
  val DefaultCloseReason: String   = "Closed by operator"
  val PlayerRequest: String        = "player_request"
  val ActivityPage: Int            = 50
  val ListLimit: Int               = 10000
  val AgentBound: FiniteDuration   = 5.seconds

  def brandJson(c: BrandConfig): ObjectNode =
    Json.obj("name" -> c.name, "legalName" -> c.legalName, "playerSiteBase" -> c.playerSiteBase, "reportingZone" -> c.reportingZone,
      "singleSession" -> c.singleSession, "multiAccountDetection" -> c.multiAccountDetection,
      "retentionDays" -> c.retentionDays.map(Int.box).orNull, "features" -> Json.obj(c.features.toSeq.sortBy(_._1)*))

  def currencyJson(c: CurrencyConfig): ObjectNode =
    Json.obj("offered" -> Json.arr(c.offered*), "defaultCurrency" -> c.defaultCurrency.orNull)

  def allowJson(c: AllowList): ObjectNode = Json.obj("countries" -> Json.arr(c.countries.toVector.sorted*))

  private val log = org.slf4j.LoggerFactory.getLogger("io.eitheror.casino.players.api.OperatorStatusRoutes")
