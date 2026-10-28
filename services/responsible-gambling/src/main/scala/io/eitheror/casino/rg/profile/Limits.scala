package io.eitheror.casino.rg.profile

/**
 * The seven limit types, by the original's names (`CS/RgRoutes.scala` `validLimitTypes`): three deposit
 * and three loss limits in minor units of a currency, and `SESSION_MINUTES` in whole minutes.
 */
final case class LimitType(name: String, kind: LimitType.Kind, period: Option[String]):
  def money: Boolean = kind != LimitType.Kind.Session

object LimitType:
  enum Kind:
    case Deposit, Loss, Session

  val DepositDaily   = LimitType("DEPOSIT_DAILY", Kind.Deposit, Some("daily"))
  val DepositWeekly  = LimitType("DEPOSIT_WEEKLY", Kind.Deposit, Some("weekly"))
  val DepositMonthly = LimitType("DEPOSIT_MONTHLY", Kind.Deposit, Some("monthly"))
  val LossDaily      = LimitType("LOSS_DAILY", Kind.Loss, Some("daily"))
  val LossWeekly     = LimitType("LOSS_WEEKLY", Kind.Loss, Some("weekly"))
  val LossMonthly    = LimitType("LOSS_MONTHLY", Kind.Loss, Some("monthly"))
  val SessionMinutes = LimitType("SESSION_MINUTES", Kind.Session, None)

  val all: Vector[LimitType]     = Vector(DepositDaily, DepositWeekly, DepositMonthly, LossDaily, LossWeekly, LossMonthly, SessionMinutes)
  val deposits: Vector[LimitType] = all.filter(_.kind == Kind.Deposit)
  val losses: Vector[LimitType]   = all.filter(_.kind == Kind.Loss)

  def parse(name: String): Option[LimitType] = all.find(_.name == name.trim.toUpperCase(java.util.Locale.ROOT))
  def named(name: String): LimitType          = parse(name).getOrElse(throw IllegalArgumentException(s"no limit type $name"))

/**
 * A limit's key: its type and, for a money limit, its currency (FR-006). `id` keys the profile's maps;
 * `timer` is the suffix of the pending timer's name. Neither holds anything personal.
 */
final case class LimitKey(limitType: String, currency: Option[String]):
  def id: String    = currency.fold(limitType)(c => s"$limitType/$c")
  def timer: String = currency.fold(limitType)(c => s"$limitType-$c")
  def kind: LimitType = LimitType.named(limitType)

object LimitKey:
  def of(t: LimitType, currency: Option[String]): LimitKey = LimitKey(t.name, if t.money then currency else None)

/** The original's rule and the staging it decides (FR-002 to FR-004; research R3). */
object Limits:

  /**
   * The original's `isMoreRestrictive`: a removal is looser; any value where none applies is tighter; a
   * smaller value is tighter; an equal or larger one is not (R16: setting the active value again stages it).
   */
  def isMoreRestrictive(current: Option[Long], next: Option[Long]): Boolean =
    next match
      case None    => false
      case Some(n) => current.forall(n < _)

  enum Decision:
    case ApplyNow, Stage

  /** An operator's change applies at once; a player's tighter change applies at once; a player's looser one is staged. */
  def decide(byOperator: Boolean, current: Option[Long], next: Option[Long]): Decision =
    if byOperator || isMoreRestrictive(current, next) then Decision.ApplyNow else Decision.Stage

  /** A positive whole number, as the value of every limit (FR-001, FR-003). */
  def parseValue(text: String): Option[Long] =
    val t = text.trim
    if t.nonEmpty && t.length <= 18 && t.forall(_.isDigit) then t.toLongOption.filter(_ > 0) else None
