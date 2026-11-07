package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.players.{DepositTotals, PlayerRow, Players}
import io.eitheror.casino.programme.sealing.{Digest, Sealing}

import java.time.Instant

/** The data-subject routes' shapes (FR-037, FR-038): closing, erasure, a new email, and players by pseudonym. */
object SubjectAnswers:
  final case class EmailBody(email: Option[String] = None)
  final case class AccountClosed(affiliateId: String, closedAt: Instant)
  final case class ErasureFiled(affiliateId: String, status: String, filedAt: Instant, holdUntil: Instant)
  final case class EmailUpdated(affiliateId: String, ok: Boolean)
  /** A player as a manager or an affiliate reads one: the brand's pseudonym, the country and figures, nothing that names the player. */
  final case class PlayerAnswer(playerRef: String, brandId: String, referredByAffiliateId: Option[String], campaign: Option[String],
      registeredAt: Option[Instant], country: Option[String], hasFtd: Boolean, firstDepositAt: Option[Instant],
      deposits: Map[String, DepositTotals], withdrawals: Map[String, Long], closed: Boolean)

  given emailBody: JsonValueCodec[EmailBody]           = ProgrammeCodecs.wire[EmailBody]
  given accountClosed: JsonValueCodec[AccountClosed]   = ProgrammeCodecs.wire[AccountClosed]
  given erasureFiled: JsonValueCodec[ErasureFiled]     = ProgrammeCodecs.wire[ErasureFiled]
  given emailUpdated: JsonValueCodec[EmailUpdated]     = ProgrammeCodecs.wire[EmailUpdated]
  given players: JsonValueCodec[Vector[PlayerAnswer]]  = ProgrammeCodecs.wire[Vector[PlayerAnswer]]

  def player(r: PlayerRow): PlayerAnswer =
    PlayerAnswer(r.pseudonym, r.brand, r.affiliate, r.campaign, r.boundAt, r.country, r.firstDepositAt.isDefined, r.firstDepositAt,
      r.deposits, r.withdrawals, r.closed.isDefined)

import SubjectAnswers.{*, given}

/**
 * An affiliate as a data subject (FR-037), and players as the programme holds them (FR-038). Closing and
 * erasing reach the whole account, so the caller must hold every brand it has a membership at (decided
 * 2026-11-02); a platform administrator holds them all.
 */
final class DataSubjects(api: Api):
  private val networks = Networks(api)

  /**
   * What touches the account rather than one brand's membership of it (FR-041) is for a caller who holds
   * every brand of the account, or a platform administrator: one brand's manager must not change what the
   * account's other brands, another company's among them, rely on.
   */
  def whole(aff: String, holds: String => Boolean)(f: AffiliateState => Respond[Bytes]): Respond[Bytes] =
    val a = api.account(aff)
    if !a.exists then refuse(404, Codes.AccountNotFound)
    else if a.erased.isDefined then refuse(409, Codes.Erased)
    else
      val others = api.membershipsOf(aff).map(_.brand).distinct.filterNot(holds).sorted
      if others.nonEmpty then refuse(409, "account_shared", s"the account is also a member at ${others.mkString(", ")}")
      else f(a)

  /** Sign-in ends: the account is closed and its realm user loses the affiliate role and every brand's. */
  def close(brand: String, aff: String, by: String, holds: String => Boolean): Respond[Bytes] =
    whole(aff, holds) { a =>
      val at = api.now()
      val s  = api.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.close).invoke(CloseAccount(by, at))
      a.keycloakUser.foreach { user =>
        api.realm.rolesOf(user).foreach(_.filter(r => r == "affiliate" || r.startsWith("brand_")).foreach(r => api.realm.removeRole(user, r): Unit))
      }
      networks.audit(aff, Some(brand), "ACCOUNT_CLOSED", by)
      ok(AccountClosed(aff, s.closed.getOrElse(at)))
    }

  /** The erasure filed with its hold: the identity goes when the retention ends (`Erasure`). */
  def erasure(brand: String, aff: String, by: String, holds: String => Boolean): Respond[Bytes] =
    whole(aff, holds) { a =>
      if a.closed.isEmpty then refuse(409, "account_not_closed", "an account is closed before its erasure is filed")
      else
        val at   = api.now()
        val hold = ErasureRules.holdUntil(at, api.settings.retentionDays)
        val s = api.components.forWorkflow(EntityId(aff)).call(Erasure.file).invoke(FileErasure(aff, by, at, hold))
        networks.audit(aff, Some(brand), "ERASURE_FILED", by, Map("holdUntil" -> s.holdUntil.getOrElse(hold).toString))
        answer(202, ErasureFiled(aff, s.status, s.filedAt.getOrElse(at), s.holdUntil.getOrElse(hold)))
    }

  /** O18: the identity sealed again with the new address, lowercased as at enrolment, the index moved, and the realm user's email changed. */
  def updateEmail(brand: String, aff: String, b: EmailBody, by: String): Respond[Bytes] =
    val enrolment = Enrolment(api)
    enrolment.address(b.email) match
      case None => invalid("email is required")
      case Some(email) =>
        val a = api.account(aff)
        if !a.exists then refuse(404, Codes.AccountNotFound)
        else if a.erased.isDefined then refuse(409, Codes.Erased)
        else
          enrolment.lookup(email) match
            case Left(e) => refuse(503, "store_unavailable", e)
            case Right((_, Some(other))) if other != aff => refuse(409, "email_already_registered")
            case Right((digest, _)) =>
              val sealedAgain = for
                key      <- Secrets(api.secrets).dataKey(aff).left.map(_.reason)
                identity <- Sealing.open(a.identity, key, aff)
              yield Sealing.seal(identity.copy(email = Digest.normalised(email)), key, aff)
              sealedAgain match
                case Left(e) => refuse(503, "store_unavailable", e)
                case Right(identity) =>
                  api.components.forKeyValueEntity(EntityId(digest)).call(EmailIndex.claim).invoke(aff): Unit
                  api.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.updateIdentity)
                    .invoke(UpdateIdentity(identity, digest, by, api.now())): Unit
                  if a.emailDigest.nonEmpty && a.emailDigest != digest then
                    api.components.forKeyValueEntity(EntityId(a.emailDigest)).call(EmailIndex.release).invoke(aff): Unit
                  a.keycloakUser.foreach(user => api.realm.updateEmail(user, Digest.normalised(email)): Unit)
                  networks.audit(aff, Some(brand), "EMAIL_UPDATED", by)
                  ok(EmailUpdated(aff, true))

  /** O26 (the account's own players) and M23 (theirs and their network's, as the original): pseudonyms only. */
  def players(aff: String, brands: Vector[String], network: Boolean): Respond[Bytes] =
    val ids = if network then aff +: networks.descendants(aff) else Vector(aff)
    val rows = for
      a <- ids
      b <- brands
      r <- api.views.forView(Players).ask(Players.byAffiliate, "affiliate" -> a, "brand" -> b)
    yield player(r)
    ok(rows.sortBy(_.registeredAt.getOrElse(Instant.EPOCH))(using Ordering[Instant].reverse))
