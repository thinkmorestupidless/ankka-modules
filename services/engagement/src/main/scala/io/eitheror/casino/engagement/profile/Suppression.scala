package io.eitheror.casino.engagement.profile

/**
 * Whether each marketing channel is suppressed, and why (FR-012, research R14). The reasons in order:
 * the account closed, an open exclusion, an open cooling-off, the operator's message suppression (the
 * `messages` consent withdrawn), the channel's own consent not granted. A channel with no consent fact
 * is not consented. The CRM's tracks are consented when either email or SMS is.
 */
object Suppression:
  /** The reason each channel is suppressed, or None when it is not. */
  final case class Verdict(email: Option[String], sms: Option[String], crm: Option[String])

  val AccountClosed    = "account_closed"
  val SelfExcluded     = "self_excluded"
  val CoolingOff       = "cooling_off"
  val Operator         = "operator"
  val ConsentWithdrawn = "consent_withdrawn"

  /** Spec 010's case types (`RgCase.SelfExclusion`, `RgCase.CoolOff` in responsible-gambling). */
  val SelfExclusionCase = "self_exclusion"
  val CoolOffCase       = "cool_off"

  def of(p: ProfileState): Verdict =
    def open(caseType: String) = p.cases.values.exists(_.caseType == caseType)
    def granted(channel: String) = p.consents.get(channel).exists(_.granted)
    val account =
      if p.status == "closed" then Some(AccountClosed)
      else if p.status == "self_excluded" || open(SelfExclusionCase) then Some(SelfExcluded)
      else if open(CoolOffCase) then Some(CoolingOff)
      else if p.consents.get("messages").exists(!_.granted) then Some(Operator)
      else None
    def channel(consented: Boolean) = account.orElse(Option.when(!consented)(ConsentWithdrawn))
    Verdict(
      email = channel(granted("email")),
      sms = channel(granted("sms")),
      crm = channel(granted("email") || granted("sms"))
    )
