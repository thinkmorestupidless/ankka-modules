package io.eitheror.casino.engagement.profile

import java.time.LocalDate

import scala.util.Try

/**
 * The identify's attributes (FR-008, research R9), as the original's Scala `buildIdentifyAttributes`
 * (`CrmService.scala`), in its order. Seven are always sent; the rest only once their source has been
 * seen, never as an empty string. `language`, `affiliate_id` and `last_device_os` are not sent: no fact
 * carries them.
 */
object Attributes:
  /** Sent as JSON numbers (FR-003, the original's `numericAttributes`); every other attribute is a string. */
  val numeric: Set[String] = Set(
    "created_at",
    "deposit_count",
    "daily_deposit_count",
    "real_money",
    "bonus_money",
    "total_balance",
    "total_deposit_amount",
    "amount",
    "session_duration_minutes"
  )

  /** The original's limit keys, in its order (`rg_limit_types`). */
  val limitTypes: Vector[String] =
    Vector("DEPOSIT_DAILY", "DEPOSIT_WEEKLY", "DEPOSIT_MONTHLY", "LOSS_DAILY", "LOSS_WEEKLY", "LOSS_MONTHLY", "SESSION_MINUTES")

  /**
   * The number an attribute is sent as: the original's `Try(BigDecimal(v))`, exact, so "100.00" stays
   * 100.00. None for an attribute not in `numeric`, and for a value that does not parse, which is
   * sent as a string rather than dropped.
   */
  def number(key: String, value: String): Option[BigDecimal] =
    if numeric.contains(key) then Try(BigDecimal(value)).toOption else None

  /** Minor units as the original's `majorUnits`: major units with two decimals, plain ("-5.00"). */
  def majorUnits(cents: Long): String = java.math.BigDecimal.valueOf(cents, 2).toPlainString

  def of(p: ProfileState, brandId: String, today: LocalDate): Vector[(String, String)] =
    def nonEmpty(v: Option[String]) = v.filter(_.nonEmpty)
    val email      = ProfileState.reveal(p.email)
    val playerName = ProfileState.reveal(p.givenName).getOrElse(email.getOrElse("").takeWhile(_ != '@'))
    val limits     = limitTypes.filter(p.limits.contains)
    val coolingOff = p.cases.values.exists(_.caseType == Suppression.CoolOffCase)
    val deposited  = p.depositCount > 0
    val emailConsent = p.consents.get("email")
    val smsConsent   = p.consents.get("sms")
    val consentSource = Vector(emailConsent, smsConsent).flatten.maxByOption(_.at).map(_.source)
    val suppressed    = Suppression.of(p).crm
    val candidates: Vector[(String, Option[String])] = Vector(
      "id"                   -> Some(p.playerId),
      "created_at"           -> p.registeredAt.map(_.getEpochSecond.toString),
      "email"                -> email,
      "player_name"          -> Option.when(playerName.nonEmpty)(playerName),
      "brand_id"             -> Some(brandId),
      "currency"             -> nonEmpty(p.currency),
      "country"              -> nonEmpty(p.country),
      "date_of_birth"        -> ProfileState.reveal(p.dateOfBirth),
      "registration_date"    -> p.registeredAt.map(_.toString),
      "vip_tier"             -> nonEmpty(p.vipTier),
      "last_login_at"        -> p.lastLoginAt.map(_.toString),
      "last_deposit_at"      -> p.lastDepositAt.map(_.toString),
      "total_deposit_amount" -> Option.when(deposited)(majorUnits(p.totalDepositCents)),
      "has_rg_limits"        -> Some(limits.nonEmpty.toString),
      "rg_limit_types"       -> Option.when(limits.nonEmpty)(limits.mkString(",")),
      // The original wrote "false" when a cooling-off ended; a case, open or seen, is the source here.
      "is_cooling_off"       -> Option.when(coolingOff || p.cases.nonEmpty || p.seen.keys.exists(_.startsWith("case/")))(coolingOff.toString),
      // The original only ever wrote "true" (`MultiAccountDetector`).
      "suspected_duplicate"  -> Option.when(p.seen.contains("duplicate"))(p.suspectedDuplicate.toString),
      "kyc_status"           -> nonEmpty(p.kycStatus),
      "account_status"       -> Option.when(p.registeredAt.isDefined || p.status != "active")(p.status),
      "deposit_count"        -> Option.when(deposited)(p.depositCount.toString),
      "daily_deposit_count"  -> Some(DepositDay.countToday(p, today).toString),
      "real_money"           -> p.realCents.map(majorUnits),
      "bonus_money"          -> p.restrictedCents.map(majorUnits),
      "total_balance"        -> (for r <- p.realCents; b <- p.restrictedCents yield majorUnits(r + b)),
      "email_subscribed"     -> Some(emailConsent.exists(_.granted).toString),
      "sms_subscribed"       -> Some(smsConsent.exists(_.granted).toString),
      "email_consent_at"     -> emailConsent.flatMap(_.lastGranted).map(_.toString),
      "sms_consent_at"       -> smsConsent.flatMap(_.lastGranted).map(_.toString),
      "consent_source"       -> consentSource.filter(_.nonEmpty),
      "pot_vip"              -> Some("false"),
      "btag"                 -> nonEmpty(p.btag),
      "marketing_suppressed" -> Some(suppressed.isDefined.toString),
      "suppression_reason"   -> suppressed
    )
    candidates.collect { case (k, Some(v)) => k -> v }
