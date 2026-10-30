package io.eitheror.casino.kyc.policy

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.kyc.KycCodecs
import io.eitheror.casino.kyc.record.Category

/** What a gate asks of the account before a deposit or play (FR-019). */
enum Gate(val wire: String):
  case Off         extends Gate("NONE")
  case KycApproved extends Gate("KYC_APPROVED")

object Gate:
  def of(wire: String): Option[Gate] = values.find(_.wire == wire)
  given JsonValueCodec[Gate]          = KycCodecs.wire(values, _.wire)

/** The licence a policy was set from; `CUSTOM` once any field is changed by hand. */
enum Profile(val wire: String):
  case Ukgc    extends Profile("UKGC")
  case Mga     extends Profile("MGA")
  case Curacao extends Profile("CURACAO")
  case Custom  extends Profile("CUSTOM")

object Profile:
  given JsonValueCodec[Profile] = KycCodecs.wire(values, _.wire)

/**
 * A brand's KYC policy (data-model.md, R9). Thresholds are minor units of `currency`, the brand's one
 * KYC currency; a threshold without it is refused. `isDefault` says no one has set the policy.
 */
final case class Policy(
    depositGate: Gate,
    playGate: Gate,
    currency: Option[String],
    depositThreshold: Option[Long],
    sofThreshold: Option[Long],
    windowDays: Option[Int],
    required: Vector[Category],
    suspendOnExpiry: Boolean,
    profile: Profile,
    isDefault: Boolean = false
)

/**
 * A change to some of a policy's fields (`PUT /policy`). A field left out keeps its value; a nullable
 * field given as `Some(None)` is cleared. Gates and categories arrive as their wire names, so a refusal
 * can name what was wrong.
 */
final case class Change(
    depositGate: Option[String] = None,
    playGate: Option[String] = None,
    currency: Option[Option[String]] = None,
    depositThreshold: Option[Option[Long]] = None,
    sofThreshold: Option[Option[Long]] = None,
    windowDays: Option[Option[Int]] = None,
    required: Option[Vector[String]] = None,
    suspendOnExpiry: Option[Boolean] = None
)

/** Why a policy command was refused, with the code the routes answer (contracts/routes.md). */
enum PolicyRefusal(val code: String):
  case InvalidPolicy(field: String)              extends PolicyRefusal("invalid_policy")
  case CurrencyRequired                          extends PolicyRefusal("currency_required")
  case InvalidCategory(category: String)         extends PolicyRefusal("invalid_category")
  case ProfileRequired                           extends PolicyRefusal("profile_required")
  case UnknownProfile(known: Vector[String])     extends PolicyRefusal("unknown_profile")

object Policy:
  /**
   * With no event, the brand runs the Curaçao default, marked as one (R9). Lazy: `Profiles` builds
   * policies, so this companion is initialised while `Profiles` is.
   */
  lazy val Default: Policy = Profiles.Curacao.copy(isDefault = true)

  private val CurrencyCode = "[A-Z]{3}".r

  /**
   * The policy with `change` applied and the profile `CUSTOM`, each field checked: a gate `NONE` or
   * `KYC_APPROVED`, a threshold or window not negative, a currency of three capital letters, each required
   * category known and never `identity_back` (FR-013), and a threshold only with a currency.
   */
  def change(current: Policy, change: Change): Either[PolicyRefusal, Policy] =
    def gate(field: String, value: Option[String], was: Gate) =
      value.fold(Right(was))(v => Gate.of(v).toRight(PolicyRefusal.InvalidPolicy(field)))
    def notNegative[A](field: String, value: Option[Option[A]], was: Option[A])(using n: Numeric[A]) =
      value.fold(Right(was))(v => if v.exists(n.lt(_, n.zero)) then Left(PolicyRefusal.InvalidPolicy(field)) else Right(v))
    for
      depositGate <- gate("depositGate", change.depositGate, current.depositGate)
      playGate    <- gate("playGate", change.playGate, current.playGate)
      currency    <- change.currency.fold(Right(current.currency)) {
                       case Some(c) if !CurrencyCode.matches(c) => Left(PolicyRefusal.InvalidPolicy("currency"))
                       case c                                   => Right(c)
                     }
      deposit     <- notNegative("depositThreshold", change.depositThreshold, current.depositThreshold)
      sof         <- notNegative("sofThreshold", change.sofThreshold, current.sofThreshold)
      window      <- notNegative("windowDays", change.windowDays, current.windowDays)
      required    <- change.required.fold(Right(current.required))(requiredOf)
      _           <- Either.cond(currency.isDefined || (deposit.isEmpty && sof.isEmpty), (), PolicyRefusal.CurrencyRequired)
    yield Policy(
      depositGate,
      playGate,
      currency,
      deposit,
      sof,
      window,
      required,
      change.suspendOnExpiry.getOrElse(current.suspendOnExpiry),
      Profile.Custom
    )

  private def requiredOf(names: Vector[String]): Either[PolicyRefusal, Vector[Category]] =
    names.distinct.foldLeft[Either[PolicyRefusal, Vector[Category]]](Right(Vector.empty)) { (acc, name) =>
      acc.flatMap(cs =>
        Category.of(name).filter(_ != Category.IdentityBack).map(cs :+ _).toRight(PolicyRefusal.InvalidCategory(name)))
    }
