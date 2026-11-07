package io.eitheror.casino.engagement.events

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits}
import io.eitheror.casino.engagement.profile.RecentPayment

import java.time.{Duration, Instant}
import java.util.Locale

/**
 * A fact to the Customer.io events it makes (contracts/facts.md, FR-006, research R8), in the order
 * `G/handler.go`'s candidates give them. The original's freshness gate (`isFresh`) has no counterpart: a fact
 * is a transition, and the send claim keyed by the fact makes each event once.
 */
object Events:

  /** What the profile says, read before (and for the count, after) the fact is applied. */
  final case class Context(registeredAt: Option[Instant], depositCountBefore: Long, depositCountAfter: Long,
      payment: String => Option[RecentPayment])

  /** `G/handler.go` `isAutoLogin`: the session made at registration is no login the player performed. */
  val autoLoginWindow: Duration = Duration.ofMinutes(2)

  /** Within the window of registration, a login before it (a negative gap) counting too; no registration, no rule. */
  def isAutoLogin(registeredAt: Option[Instant], loggedInAt: Instant): Boolean =
    registeredAt.exists(r => Duration.between(r, loggedInAt).compareTo(autoLoginWindow) < 0)

  def of(factId: String, fact: f.Fact, ctx: Context): Vector[Event] =
    def event(name: String, data: (String, Option[String])*): Event = Event(name, s"$name/$factId", fields(data))
    def one(name: String, data: (String, Option[String])*): Vector[Event] = Vector(event(name, data*))

    fact match
      case r: f.PlayerRegistered =>
        val btag = r.btag.filter(_.nonEmpty)
        event("player_registered", "currency" -> r.currency, "btag" -> btag) +:
          btag.toVector.map(b => event("affiliate_attributed", "btag" -> Some(b)))
      case l: f.PlayerLoggedIn =>
        if isAutoLogin(ctx.registeredAt, l.occurredAt) then Vector.empty else one("player_logged_in")
      case _: f.PlayerSelfExcluded => one("self_exclusion_activated")
      case c: f.RgCaseOpened if c.caseType == "cool_off" =>
        c.closesAt.toVector.map(at => event("cooling_off_set", "cooling_off_period" -> Some(at.toString)))
      case a: f.RgLimitApplied =>
        val tpe = limitType(a.limitType, a.period)
        a.causedBy match
          case "set" | "lowered" =>
            limitSet(tpe).toVector.map(name => event(name, "limit_type" -> Some(tpe), "limit_value" -> a.value.map(limitValue(tpe, _))))
          case "removed" => one("rg_limit_loosened", "limit_type" -> Some(tpe))
          case _         => Vector.empty
      case r: f.RgLimitLooseningRequested =>
        val tpe = limitType(r.limitType, r.period)
        one("rg_limit_loosened", "limit_type" -> Some(tpe), "limit_value" -> r.value.map(limitValue(tpe, _)))
      case _: f.PlayerKycVerified => one("kyc_approved")
      case _: f.PlayerKycRejected => one("kyc_rejected")
      case d: f.PlayerKycDocumentReviewed =>
        d.decision match
          case "approved"              => one("kyc_document_accepted", "doc_type" -> Some(d.category))
          case "declined" | "rejected" => one("kyc_document_declined", "doc_type" -> Some(d.category), "reason" -> d.reasonCode)
          case _                       => Vector.empty
      case c: f.PlayerConsentChanged if c.channel == "email" || c.channel == "sms" => one("consent_updated")
      case s: f.GameSessionStarted => one("game_session_started", "game_id" -> Some(s.gameId), "session_id" -> Some(s.sessionId))
      case s: f.GameSessionEnded =>
        one("game_session_ended", "game_id" -> Some(s.gameId), "session_id" -> Some(s.sessionId),
          "session_duration_minutes" -> Some(s.durationMinutes.toString))
      case d: f.DepositConfirmed =>
        val data = Vector("amount" -> amount(d.amountCents), "currency" -> currency(d.currency), "transaction_id" -> Some(d.paymentReference))
        val first = ctx.depositCountBefore < 1 && ctx.depositCountAfter == 1
        event("deposit_confirmed", data*) +: (if first then Vector(event("first_deposit_confirmed", data*)) else Vector.empty)
      case p: f.PaymentFailed  => Vector(paymentFailed(p.paymentId, p.paymentProvider, p.reason, p.code, expired = false, ctx))
      case p: f.PaymentExpired => Vector(paymentFailed(p.paymentId, p.paymentProvider, "expired", "", expired = true, ctx))
      case w: f.WithdrawalFailed =>
        one("withdrawal_failed", "amount" -> amount(w.amountCents), "currency" -> currency(w.currency),
          "transaction_id" -> Some(w.payoutId), "failure_reason" -> Some(w.failureReason))
      case b: f.BonusGranted =>
        one("bonus_granted", "bonus_name" -> Some(b.offerId), "amount" -> amount(b.amountCents), "currency" -> currency(b.currency),
          "reward_id" -> Some(b.bonusId))
      case b: f.FreeSpinsGranted => one("bonus_granted", "bonus_name" -> Some(b.offerId), "reward_id" -> Some(b.bonusId))
      case b: f.BonusCancelled =>
        one("bonus_cancelled", "bonus_name" -> Some(b.offerId), "amount" -> amount(b.amountCents), "currency" -> currency(b.currency))
      case b: f.BonusForfeited =>
        one("bonus_cancelled", "bonus_name" -> Some(b.offerId), "amount" -> b.amountCents.flatMap(amount),
          "currency" -> b.currency.flatMap(currency))
      case b: f.BonusCompleted   => one("wagering_requirement_met", "bonus_name" -> Some(b.offerId))
      case c: f.BonusCodeEntered => one("promo_code_entered", "promo_code" -> Some(c.code))
      case _                     => Vector.empty

  /** contracts/facts.md "Identify only" (FR-010): a fact that changes an attribute no event carries. */
  def identifyOnly(fact: f.Fact): Boolean = fact match
    case _: f.VipTierChanged | _: f.PlayerEmailChanged | _: f.PlayerProfileUpdated | _: f.PlayerDuplicateCheckEnded |
        _: f.PlayerClosed | _: f.PlayerSuspended | _: f.PlayerReinstated | _: f.PlayerReopened | _: f.RgCaseClosed =>
      true
    case c: f.PlayerConsentChanged  => c.channel == "messages"
    case _: f.DepositConfirmed      => false
    case _: f.MoneyTransactionsFact => true
    case _                          => false

  /** The original's `<TYPE>_<PERIOD>` upper case (`DEPOSIT_DAILY`); a type that already names its period keeps it. */
  def limitType(limitType: String, period: Option[String]): String =
    val tpe = limitType.trim.toUpperCase(Locale.ROOT)
    period.map(_.trim.toUpperCase(Locale.ROOT)).filter(p => p.nonEmpty && !tpe.endsWith(s"_$p")).fold(tpe)(p => s"${tpe}_$p")

  private def limitSet(tpe: String): Option[String] =
    if tpe.startsWith("DEPOSIT") then Some("deposit_limit_set")
    else if tpe.startsWith("LOSS") then Some("loss_limit_set")
    else if tpe.startsWith("SESSION") then Some("session_limit_set")
    else None

  /** Minutes for a session limit, major units for a money limit (R8). */
  private def limitValue(tpe: String, value: Long): String =
    if tpe.startsWith("SESSION") then value.toString else Amounts.major(value)

  private def paymentFailed(paymentId: String, provider: String, failureReason: String, code: String, expired: Boolean,
      ctx: Context): Event =
    val initiated = ctx.payment(paymentId)
    val terminal  = initiated.map(_.terminal).getOrElse("")
    Event("payment_failed", s"payment_failed/$paymentId", fields(Vector(
      "amount"         -> initiated.map(p => Amounts.major(p.amountCents)),
      "currency"       -> initiated.map(_.currency),
      "transaction_id" -> Some(paymentId),
      "failure_reason" -> Some(failureReason),
      "provider"       -> Some(provider),
      "payment_method" -> Some(terminal),
      "reason"         -> Some(reason(expired, code, failureReason, card(terminal))),
      "provider_code"  -> Some(code)
    )))

  /** `G/payment_failed.go` `normalisePaymentMethod`'s card case: only a card turns a decline into `card_declined`. */
  def card(terminal: String): Boolean = terminal.trim.toUpperCase(Locale.ROOT).startsWith("CARD_")

  /**
   * `G/payment_failed.go` `normaliseFailureReason`, in its order: expiry, underpayment, cancellation, decline.
   * The received-crypto signal is gone (no fact carries it); the wording is the platform's own.
   */
  def reason(expired: Boolean, code: String, text: String, card: Boolean): String =
    val c = code.trim.toLowerCase(Locale.ROOT)
    val t = text.trim
    if expired || t == "Payment session expired" || t == "Payment not completed" || c == "expired" then "window_expired"
    else if c == "underpaid" || t.startsWith("Underpaid") || t.startsWith("Less crypto arrived") then "below_minimum"
    else if t == "Payment cancelled" || c == "canceled" || c == "cancelled" then "cancelled"
    else if t == "Payment declined" || c == "declined" then if card then "card_declined" else "declined"
    else "provider_error"

  private def amount(minor: MinorUnits): Option[String] = Some(Amounts.major(minor.value))
  private def currency(c: Currency): Option[String]    = Some(c.code)

  /** Each field only when it has a value, in the order given. */
  private def fields(data: Seq[(String, Option[String])]): Vector[(String, String)] =
    data.toVector.collect { case (k, Some(v)) if v.nonEmpty => k -> v }
