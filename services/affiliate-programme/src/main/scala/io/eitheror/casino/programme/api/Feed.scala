package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.accounts.{Affiliate, Login}
import io.eitheror.casino.programme.activity.{Activity, Creatives}
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.comms.Notification
import io.eitheror.casino.programme.postbacks.{OutcomeState, PostbackOutcomes}

import java.time.Instant

/** The portal's own records' shapes (M22, M24 to M27, M34, M35, postbacks), the original's where it had them. */
object FeedAnswers:
  final case class Marked(marked: Int)
  final case class EventAnswer(eventType: String, actorId: String, affiliateId: String, brandId: Option[String], recordedAt: Instant)
  final case class FirstLogin(firstLogin: Boolean)
  final case class PostbackAnswer(brandId: String, event: String, status: String, attempts: Int, lastStatus: Option[Int],
      lastError: Option[String], at: Option[Instant])

  given marked: JsonValueCodec[Marked]                      = ProgrammeCodecs.wire[Marked]
  given events: JsonValueCodec[Vector[EventAnswer]]         = ProgrammeCodecs.wire[Vector[EventAnswer]]
  given firstLogin: JsonValueCodec[FirstLogin]              = ProgrammeCodecs.wire[FirstLogin]
  given postbacks: JsonValueCodec[Vector[PostbackAnswer]]   = ProgrammeCodecs.wire[Vector[PostbackAnswer]]

  def postback(o: OutcomeState): PostbackAnswer = PostbackAnswer(o.brand, o.event, o.status, o.attempts, o.lastStatus, o.lastError, o.at)

import FeedAnswers.{*, given}

/**
 * What the portal reads of the affiliate's own records: notifications and marking them read, the audit
 * as events (theirs, or their network's to three levels, the original's caps of 50 accounts and 200
 * events), sign-ins, and the last outcome of each postback event.
 */
final class Feed(api: Api):
  /** M24: newest first, at the brands read and of no brand. */
  def notifications(aff: String, brands: Vector[String]): Respond[Bytes] =
    ok(Records(api).notificationsOf(aff, brands))(using RecordAnswers.notifications)

  /** M25: every unread one of the affiliate's, theirs only. */
  def readAll(aff: String): Respond[Bytes] =
    val unread = api.views.forView(Activity).ask(Activity.ofKind, "affiliate" -> aff, "kind" -> Activity.NotificationKind)
      .filterNot(_.details.contains("readAt"))
    val at = api.now()
    unread.foreach(r => api.components.forKeyValueEntity(EntityId(r.id)).call(Notification.read).invoke(at): Unit)
    ok(Marked(unread.size))

  /** M26, M27: the audit of the account alone, or of its network as the original walked it. */
  def events(aff: String, brands: Vector[String], network: Boolean): Respond[Bytes] =
    val ids  = if network then aff +: Networks(api).descendants(aff, maxDepth = 3).take(50) else Vector(aff)
    val rows = ids.flatMap(a => api.views.forView(Activity).ask(Activity.ofKind, "affiliate" -> a, "kind" -> Activity.Audit))
      .filter(_.brand.forall(brands.contains)).sortBy(_.at)(using Ordering[Instant].reverse).take(200)
    ok(rows.map(r => EventAnswer(r.action, r.actor, r.affiliate, r.brand, r.at)))

  /** M34: once, with the original's `AFFILIATE_FIRST_LOGIN` at each brand; after, `firstLogin: false`. */
  def firstLogin(aff: String, brands: Vector[String], by: String): Respond[Bytes] =
    val a = api.account(aff)
    if a.firstLoginAt.isDefined then ok(FirstLogin(false))
    else
      api.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.login).invoke(Login(first = true, api.now())): Unit
      // At each brand, or of no brand while the memberships' view has not caught up: the sign-in is recorded either way.
      (if brands.isEmpty then Vector(None) else brands.map(Some(_))).foreach(b => Networks(api).audit(aff, b, "AFFILIATE_FIRST_LOGIN", by))
      ok(FirstLogin(true))

  /** M35: the time of this sign-in, no event. */
  def lastLogin(aff: String): Respond[Bytes] =
    api.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.login).invoke(Login(first = false, api.now())): Unit
    ok(Answers.Ok(true))

  /** M22: the live creatives of the brands read. */
  def creatives(brands: Vector[String]): Respond[Bytes] =
    ok(brands.flatMap(b => api.views.forView(Creatives).ask(Creatives.liveOf, "brand" -> b)))(using RecordAnswers.creatives)

  /** FR-036: the last outcome of each event's postbacks at the brands read. */
  def postbacks(aff: String, brands: Vector[String]): Respond[Bytes] =
    ok(api.views.forView(PostbackOutcomes).ask(PostbackOutcomes.ofAffiliate, "affiliate" -> aff).filter(o => brands.contains(o.brand)).map(postback))

