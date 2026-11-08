package io.eitheror.casino.engagement.profile

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal as Marked
import io.eitheror.casino.engagement.EngagementCodecs

import java.time.{Instant, LocalDate}

/**
 * What applying a fact told the caller (contracts/facts.md's conditions): whether the profile changed, and what
 * `Events` reads of it. No personal value: the reply crosses to a consumer.
 */
final case class Applied(changed: Boolean, registeredAt: Option[Instant] = None, depositCountBefore: Long = 0,
    depositCountAfter: Long = 0, payment: Option[RecentPayment] = None)

object Profiles:
  private val RecentPayments = 20
  private val RecentDeposits = 50

  /** `at` is newer than the last fact applied to `group`: a repeat, or an older fact, changes nothing. */
  private def newer(p: ProfileState, group: String, at: Instant): Boolean = p.seen.get(group).forall(at.isAfter)

  private def stamp(p: ProfileState, group: String, at: Instant): ProfileState = p.copy(seen = p.seen.updated(group, at))

  private def personal(p: ProfileState, v: Marked[String]): Option[com.thinkmorestupidless.ankka.core.Personal[String]] =
    Option(v.reveal).filter(_.nonEmpty).map(ProfileState.personal(p.playerId, _))

  private def personal(p: ProfileState, v: Option[Marked[String]]): Option[com.thinkmorestupidless.ankka.core.Personal[String]] =
    v.flatMap(personal(p, _))

  /** One group of fields, applied when `at` is newer than its last fact. */
  private def group(p: ProfileState, name: String, at: Instant)(change: ProfileState => ProfileState): ProfileState =
    if newer(p, name, at) then stamp(change(p), name, at) else p

  private def status(p: ProfileState, at: Instant, to: String) = group(p, "status", at)(_.copy(status = to))
  private def kyc(p: ProfileState, at: Instant, to: String)    = group(p, "kyc", at)(_.copy(kycStatus = Some(to)))

  /** A fact of `casino.players` onto the profile (data-model.md's source column). */
  def players(p0: ProfileState, fact: f.CasinoPlayersFact): ProfileState =
    val p = if p0.playerId.isEmpty then p0.copy(playerId = fact.playerId) else p0
    fact match
      case r: f.PlayerRegistered =>
        group(p, "registration", r.occurredAt)(q => q.copy(
          registeredAt = Some(r.registeredAt),
          email = personal(q, r.email).orElse(q.email),
          givenName = personal(q, r.givenName).orElse(q.givenName),
          familyName = personal(q, r.familyName).orElse(q.familyName),
          dateOfBirth = personal(q, r.dateOfBirth).orElse(q.dateOfBirth),
          country = r.country.orElse(q.country),
          currency = r.currency.orElse(q.currency),
          btag = r.btag.orElse(q.btag),
          vipTier = r.vipTier.orElse(q.vipTier)))
      case e: f.PlayerEmailChanged => group(p, "email", e.occurredAt)(q => q.copy(email = personal(q, e.email).orElse(q.email)))
      case u: f.PlayerProfileUpdated =>
        group(p, "profile", u.occurredAt)(q => q.copy(
          givenName = personal(q, u.givenName).orElse(q.givenName),
          familyName = personal(q, u.familyName).orElse(q.familyName),
          dateOfBirth = personal(q, u.dateOfBirth).orElse(q.dateOfBirth),
          country = u.country.orElse(q.country)))
      case v: f.VipTierChanged      => group(p, "vip", v.occurredAt)(_.copy(vipTier = Some(v.newTier)))
      case l: f.PlayerLoggedIn      => group(p, "login", l.occurredAt)(_.copy(lastLoginAt = Some(l.occurredAt)))
      case s: f.PlayerSelfExcluded  => status(p, s.occurredAt, "self_excluded")
      case s: f.PlayerClosed        => status(p, s.occurredAt, "closed")
      case s: f.PlayerSuspended     => status(p, s.occurredAt, "suspended")
      case s: f.PlayerReinstated    => status(p, s.occurredAt, "active")
      case s: f.PlayerReopened      => status(p, s.occurredAt, "active")
      case k: f.PlayerKycVerified   => kyc(p, k.occurredAt, k.newValue)
      case k: f.PlayerKycRejected   => kyc(p, k.occurredAt, k.newValue)
      case k: f.PlayerKycPending    => kyc(p, k.occurredAt, k.newValue)
      case k: f.PlayerKycSubmitted  => kyc(p, k.occurredAt, "submitted")
      case k: f.PlayerKycReviewRequired => kyc(p, k.occurredAt, "review_required")
      case d: f.PlayerDuplicateCheckEnded =>
        group(p, "duplicate", d.occurredAt)(_.copy(suspectedDuplicate = d.outcome == "strong" || d.outcome == "weak"))
      case c: f.PlayerConsentChanged =>
        group(p, s"consent/${c.channel}", c.occurredAt)(q =>
          q.copy(consents = q.consents.updated(c.channel, Consent(c.consented, c.occurredAt, c.source,
            if c.consented then Some(c.occurredAt) else q.consents.get(c.channel).flatMap(_.lastGranted)))))
      case o: f.RgCaseOpened =>
        group(p, s"case/${o.rgCaseId}", o.occurredAt)(q => q.copy(cases = q.cases.updated(o.rgCaseId, RgCase(o.caseType, o.closesAt))))
      case c: f.RgCaseClosed =>
        group(p, s"case/${c.rgCaseId}", c.occurredAt)(q => q.copy(cases = q.cases - c.rgCaseId))
      case l: f.RgLimitApplied =>
        group(p, s"limit/${l.limitType}", l.occurredAt)(q =>
          if l.causedBy == "removed" then q.copy(limits = q.limits - l.limitType)
          else q.copy(limits = q.limits.updated(l.limitType, Limit(l.period, l.value, l.currency))))
      case _ => p

  /** A movement of the player's wallet: the balances it leaves, and a deposit's counts (FR-008, FR-009). */
  def money(p0: ProfileState, fact: f.MoneyTransactionsFact): (ProfileState, Long) =
    val p = if p0.playerId.isEmpty then p0.copy(playerId = fact.playerId) else p0
    val balanced = group(p, "balance", fact.occurredAt)(_.copy(
      realCents = Some(fact.realBalanceAfter.value), restrictedCents = Some(fact.restrictedBalanceAfter.value)))
    fact match
      case d: f.DepositConfirmed if !balanced.seen.contains(s"deposit/${d.transactionId}") =>
        val day = DepositDay.of(d.occurredAt)
        // A deposit of a day already rolled past counts in the lifetime only.
        val (daily, stamped) = balanced.depositDay match
          case Some(current) if current.isAfter(day) => (balanced.dailyDepositCount, current)
          case Some(current) if current == day       => (balanced.dailyDepositCount + 1, day)
          case _                                     => (1, day)
        // The deposits already counted, by transaction, the most recent kept.
        val (counted, other) = balanced.seen.partition((k, _) => k.startsWith("deposit/"))
        val kept = counted.toVector.sortBy(_._2).takeRight(RecentDeposits - 1).toMap
        val next = balanced.copy(
          depositCount = balanced.depositCount + 1,
          totalDepositCents = balanced.totalDepositCents + d.amountCents.value,
          lastDepositAt = Some(balanced.lastDepositAt.filter(_.isAfter(d.occurredAt)).getOrElse(d.occurredAt)),
          dailyDepositCount = daily,
          depositDay = Some(stamped),
          seen = other ++ kept + (s"deposit/${d.transactionId}" -> d.occurredAt))
        (next, p.depositCount)
      case _ => (balanced, p.depositCount)

  /** A payment's start remembered, so its failure can say the amount (contracts/facts.md). */
  def payments(p0: ProfileState, fact: f.PaymentsPaymentsFact): ProfileState =
    val p = if p0.playerId.isEmpty then p0.copy(playerId = fact.playerId) else p0
    fact match
      case i: f.PaymentInitiated if !p.recentPayments.exists(_.paymentId == i.paymentId) =>
        p.copy(recentPayments = (p.recentPayments :+
          RecentPayment(i.paymentId, i.amountCents.value, i.currency.code, i.paymentProvider, i.terminal)).takeRight(RecentPayments))
      case _ => p

  /** FR-009's reset: a count above zero stamped with an earlier day goes to 0 and today's stamp. */
  def resetDepositDay(p: ProfileState, day: LocalDate): Option[ProfileState] =
    if p.dailyDepositCount > 0 && p.depositDay.exists(_.isBefore(day)) then Some(p.copy(dailyDepositCount = 0, depositDay = Some(day)))
    else None

  /** Every personal field gone, and the profile marked so no later fact produces a call (FR-016). */
  def erase(p: ProfileState): ProfileState =
    p.copy(email = None, givenName = None, familyName = None, dateOfBirth = None, erased = true)

/** A fact of each topic the profile reads. The contract's codecs; the entity never stores these. */
final case class PlayersFact(fact: f.CasinoPlayersFact)
final case class MoneyFact(fact: f.MoneyTransactionsFact)
final case class PaymentsFact(fact: f.PaymentsPaymentsFact)

/**
 * A player as engagement knows them (key value, id = player; data-model.md `Profile`): built from the brand's facts,
 * each applied at most once by its time per field group. Its personal fields are sealed in the store.
 */
final class Profile(context: KeyValueEntityContext) extends KeyValueEntity[ProfileState]:
  def emptyState: ProfileState = ProfileState()

  private def id(s: ProfileState): ProfileState = if s.playerId.isEmpty then s.copy(playerId = context.entityId) else s

  private def answer(next: ProfileState, applied: Applied): Effect[Applied] =
    if applied.changed then effects.updateState(next).thenReply(_ => applied) else effects.reply(applied)

  def players(c: PlayersFact): Effect[Applied] =
    val before = id(currentState)
    val next   = Profiles.players(before, c.fact)
    answer(next, Applied(next != before, next.registeredAt, before.depositCount, next.depositCount))

  def money(c: MoneyFact): Effect[Applied] =
    val before        = id(currentState)
    val (next, count) = Profiles.money(before, c.fact)
    answer(next, Applied(next != before, next.registeredAt, count, next.depositCount))

  def payments(c: PaymentsFact): Effect[Applied] =
    val before  = id(currentState)
    val next    = Profiles.payments(before, c.fact)
    val payment = next.recentPayments.find(_.paymentId == c.fact.paymentId)
    answer(next, Applied(next != before, next.registeredAt, before.depositCount, next.depositCount, payment))

  /** Whether the count was reset (FR-009). */
  def resetDepositDay(day: LocalDate): Effect[Boolean] =
    Profiles.resetDepositDay(id(currentState), day) match
      case Some(next) => effects.updateState(next).thenReply(_ => true)
      case None       => effects.reply(false)

  def erase: Effect[ProfileState] = effects.updateState(Profiles.erase(id(currentState))).thenReply(identity)

  def get: ReadOnlyEffect[ProfileState] = effects.reply(id(currentState))

object Profile
    extends KeyValueEntity.Companion[Profile, ProfileState](
      componentId = ComponentId("profile"),
      stateSerializer = EngagementCodecs.serializer[ProfileState]("profile-state")
    ):
  given JsonValueCodec[ProfileState] = EngagementCodecs.stored[ProfileState]
  given Serializer[ProfileState] = EngagementCodecs.serializer[ProfileState]("profile-reply")
  given Serializer[Applied]      = EngagementCodecs.serializer[Applied]("profile-applied")
  given Serializer[PlayersFact]  = Serializer.json[PlayersFact]("engagement.profile-players-fact")(using
    com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make[PlayersFact])
  given Serializer[MoneyFact]    = Serializer.json[MoneyFact]("engagement.profile-money-fact")(using
    com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make[MoneyFact])
  given Serializer[PaymentsFact] = Serializer.json[PaymentsFact]("engagement.profile-payments-fact")(using
    com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make[PaymentsFact])
  given Serializer[LocalDate]    = EngagementCodecs.serializer[LocalDate]("profile-day")
  given Serializer[Boolean]      = EngagementCodecs.serializer[Boolean]("profile-reset")

  def create(context: KeyValueEntityContext) = new Profile(context)

  val players         = command("players")(_.players)
  val money           = command("money")(_.money)
  val payments        = command("payments")(_.payments)
  val resetDepositDay = command("reset-deposit-day")(_.resetDepositDay)
  val erase           = command("erase")(_.erase)
  val get             = query("get")(_.get)
