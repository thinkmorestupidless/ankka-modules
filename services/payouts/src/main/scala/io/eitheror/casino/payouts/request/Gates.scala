package io.eitheror.casino.payouts.request

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.limits.{Counted, Limits, Pair}
import io.eitheror.casino.payouts.player.Kyc
import io.eitheror.casino.payouts.terminals.{Eligibility, Gateways, Terminal}

import java.time.Instant

/** The amount as the body gave it: absent, a whole number, or anything else (a decimal, text). */
enum Amount:
  case Absent
  case Whole(value: Long)
  case NotWhole

/** A withdrawal request's body, in the original's field names (contracts/routes.md). Blank is absent. */
final case class Draft(
    key: Option[String],
    amount: Amount,
    currency: Option[String],
    terminalId: Option[String],
    destinationId: Option[String] = None,
    walletAddress: Option[String] = None,
    cryptoCurrency: Option[String] = None,
    payoutMethod: Option[String] = None,
    mobile: Option[String] = None,
    bankAccount: Option[String] = None,
    institutionCode: Option[String] = None,
    transitNumber: Option[String] = None,
    confirmForfeit: Boolean = false
)

/** What the wallet's hold preview says (`HoldPreview`): its first refusal by code, and the forfeit. */
final case class Preview(
    refusal: Option[String] = None,
    confirmationRequired: Boolean = false,
    bonusBalance: Long = 0L,
    clawback: Long = 0L,
    totalForfeit: Long = 0L,
    mostWithdrawable: Long = 0L
)

/**
 * What the request's checks read of the player: the identity status, the deposits' gateways and schemes,
 * the requests the limits count, and the holder and IBAN of an active destination of theirs by id.
 */
final case class Standing(
    kyc: Kyc,
    deposits: Map[String, Set[String]] = Map.empty,
    recent: Vector[Counted] = Vector.empty,
    destination: String => Option[(Holder, Personal[String])] = _ => None
)

/**
 * Everything one request's checks read. The preview is a call to the wallet, so it is made only when the
 * checks reach it; the rest is read before.
 */
final case class Inputs(
    draft: Draft,
    standing: Option[Standing],
    terminals: Vector[Terminal],
    limits: String => Pair,
    now: Instant,
    preview: (Currency, Long) => Either[Refusal, Preview]
)

/** A request every check passed: what the hold and the request record. */
final case class Passed(key: String, amount: Long, currency: Currency, terminal: Terminal, payee: Payee, preview: Preview,
    confirmForfeit: Boolean)

/**
 * A withdrawal request's checks in the original's order (research R4; `WithdrawalRoutes.scala:180-873`),
 * the first refusal answered. The token's player is the endpoint's check, before these.
 */
object Gates:
  private val WalletCodes = Map(
    "INSUFFICIENT_FUNDS"          -> Refusals.Insufficient,
    "WITHDRAWAL_BLOCKED_TURNOVER" -> Refusals.Turnover
  )
  private val AfterForfeit = "WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT"

  def check(in: Inputs): Either[Refusal, Passed] =
    val d = in.draft
    for
      key      <- d.key.toRight(Refusals.MissingKey)
      // The original asks for all three before it reads the amount (W:213-220).
      _        <- Either.cond(d.amount != Amount.Absent, (), Refusals.required("amount"))
      currency <- currencyOf(d.currency)
      tid      <- d.terminalId.toRight(Refusals.required("terminalId"))
      amount   <- amountOf(d.amount)
      method   <- gigadat(d, in.terminals.find(_.id == tid))
      standing <- in.standing.toRight(Refusals.PlayerNotFound)
      _        <- Either.cond(standing.kyc == Kyc.Verified, (), Refusals.kyc(standing.kyc.wire))
      preview  <- in.preview(currency, amount)
      _        <- previewRefusal(preview)
      _        <- Limits.check(in.limits(currency.code), standing.recent, currency, amount, in.now)
                    .map(Refusals.limit(_, currency.code)).toLeft(())
      _        <- Either.cond(!(short(preview) && preview.confirmationRequired), (), Refusals.Insufficient)
      _        <- Either.cond(!preview.refusal.contains(AfterForfeit), (),
                    Refusals.afterForfeit(preview.mostWithdrawable, preview.totalForfeit))
      _        <- Either.cond(!preview.confirmationRequired || d.confirmForfeit, (),
                    Refusals.confirmForfeit(preview.bonusBalance, preview.clawback, preview.totalForfeit))
      terminal <- Eligibility.resolve(tid, in.terminals, standing.deposits).toRight(Refusals.terminalNotEligible(tid))
      payee    <- payeeOf(d, terminal, standing, method)
      _        <- holdRefusal(preview)
    yield Passed(key, amount, currency, terminal, payee, preview, d.confirmForfeit)

  private def amountOf(a: Amount): Either[Refusal, Long] = a match
    case Amount.Whole(v) if v > 0 => Right(v)
    case Amount.Absent            => Left(Refusals.required("amount"))
    case _                        => Left(Refusals.InvalidAmount)

  private def currencyOf(c: Option[String]): Either[Refusal, Currency] =
    c.toRight(Refusals.required("currency")).flatMap(code => Currency.from(code.toUpperCase).left.map(_ => Refusals.required("currency")))

  /** R4 2a: a Gigadat terminal needs its method's fields at the request (settled with the user, 2026-10-31). */
  private def gigadat(d: Draft, terminal: Option[Terminal]): Either[Refusal, Option[Method]] =
    if !terminal.exists(t => Gateways.isGigadat(t.gateway)) then Right(None)
    else
      for
        text   <- d.payoutMethod.toRight(Refusals.required("payoutMethod"))
        method <- Method.from(text).toRight(Refusals.required("payoutMethod"))
        _ <- method match
          case Method.Eto => d.mobile.toRight(Refusals.required("mobile"))
          case Method.Ach =>
            for
              _ <- d.bankAccount.toRight(Refusals.required("bankAccount"))
              _ <- d.institutionCode.toRight(Refusals.required("institutionCode"))
              _ <- d.transitNumber.toRight(Refusals.required("transitNumber"))
            yield ()
      yield Some(method)

  private val AlreadyPending = "WITHDRAWAL_REQUEST_ALREADY_PENDING"

  private def short(p: Preview) = p.refusal.contains("INSUFFICIENT_FUNDS")

  /**
   * The preview's refusals where the original asks them (R4): turnover here, after identity (W:298); a
   * shortfall with forfeitable records after the limits (W:775-795, `confirmationRequired` is set exactly
   * then); the forfeit's after the limits too (rows 8, 9); and the rest at the hold, after the terminal and
   * destination checks, where the original's wallet refused them (W:622-652).
   */
  private def previewRefusal(p: Preview): Either[Refusal, Unit] = p.refusal match
    case None | Some(AfterForfeit) | Some(AlreadyPending) => Right(())
    case Some("INSUFFICIENT_FUNDS")                      => Right(())
    case Some(code) if WalletCodes.contains(code)        => Left(WalletCodes(code))
    case Some(code)                                      => Left(Refusal(409, code, "the wallet refused the withdrawal"))

  private def holdRefusal(p: Preview): Either[Refusal, Unit] = p.refusal match
    case Some(AlreadyPending) => Left(Refusals.AlreadyPending)
    case _ if short(p)        => Left(Refusals.Insufficient)
    case _                    => Right(())

  private def payeeOf(d: Draft, t: Terminal, standing: Standing, method: Option[Method]): Either[Refusal, Payee] =
    val coin = d.cryptoCurrency.map(_.toUpperCase)
    for
      _ <- Either.cond(!(t.requiresPayoutDestination && d.destinationId.isEmpty), (), Refusals.DestinationRequired)
      _ <- Either.cond(!(t.crypto && d.walletAddress.isEmpty), (), Refusals.AddressRequired)
      _ <- coin.filter(c => t.crypto && t.coins.nonEmpty && !t.coins.contains(c)).map(Refusals.coin(_, t.coins)).toLeft(())
      destination <- d.destinationId match
        case None     => Right(None)
        case Some(id) => standing.destination(id).map(Some(_)).toRight(Refusals.InvalidDestination)
    yield
      // Only what the terminal's gateway pays with is kept: a value the payout never sends is not held.
      def kept(value: Option[String], when: Boolean) = value.filter(_ => when).map(Personal(_))
      Payee(
        destinationId = d.destinationId,
        holder = destination.map(_._1),
        iban = destination.map(_._2),
        walletAddress = kept(d.walletAddress, t.crypto),
        coin = coin.filter(_ => t.crypto),
        method = method,
        mobile = kept(d.mobile, method.contains(Method.Eto)),
        account = kept(d.bankAccount, method.contains(Method.Ach)),
        institution = kept(d.institutionCode, method.contains(Method.Ach)),
        transit = kept(d.transitNumber, method.contains(Method.Ach))
      )
