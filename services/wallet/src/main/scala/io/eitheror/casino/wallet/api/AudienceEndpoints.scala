package io.eitheror.casino.wallet.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.http.openapi.Description
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.access.{RoleBags, Routes}
import io.eitheror.casino.contract.money.{Currency, Key}
import io.eitheror.casino.contract.personal.Personal.reveal
import io.eitheror.casino.wallet.config.{BrandConfig, BrandConfigEntity, SetTurnoverTerms}
import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.views.*
import io.eitheror.casino.wallet.wire.*

import java.time.Instant
import io.eitheror.casino.contract.http.Json

/** The player site's and backoffice's amounts: major units by the currency's exponent (FR-003; amended by spec 027 FR-058). */
final case class MajorBalance(realBalance: String, bonusBalance: String, currency: String, primary: Option[MajorPrimary],
    depositPoolBalance: String, convertedPoolBalance: String, reserved: String, available: String)
final case class MajorPrimary(grantRef: String, status: String, wageringRequirement: String, wagered: String, progressPercent: Long)
final case class MajorTransaction(transactionId: String, `type`: String, walletType: String, amount: String, balanceBefore: String,
    balanceAfter: String, status: String, roundId: Option[String], awardRef: Option[String], paymentReference: Option[String],
    createdAt: String, depositPart: String, convertedPart: String, depositPoolAfter: String, convertedPoolAfter: String,
    reference: Option[String] = None,
    /** Spec 030 W2: an adjustment's reason (personal, FR-033), category, cause and actor. */
    reason: Option[String] = None, category: Option[String] = None, cause: Option[Cause] = None, actorId: Option[String] = None)
final case class MajorTransactions(transactions: Vector[MajorTransaction], page: Int, pageSize: Int, total: Long)
final case class MajorLifetime(totalDeposited: String, totalWagered: String, totalWon: String)
/** Spec 022 FR-009: an operator's adjustment of a non-player account, in major units; the reason names no player. */
final case class OperatorAccountAdjustRequest(key: Option[String], currency: String, amount: String, reason: String = "", category: String = "other")
final case class MajorAccountBalance(balance: String, creditLine: String, floor: String, owner: String, currency: String,
    txnId: Option[String] = None)
/**
 * Spec 030 W2: `amountMinor` (signed minor units) is accepted beside `amount` (signed major text);
 * both given must agree. `cause` is what the adjustment corrects.
 */
final case class OperatorAdjustRequest(key: Option[String], walletType: String = "real", amount: String = "", reason: String = "",
    category: String = "other", amountMinor: Option[Long] = None, cause: Option[Cause] = None)
/**
 * The adjustment's answer: the balance read as before (major units), and, from the adjustment's own
 * outcome (so a repeat under its key answers the first), the transaction, the signed amount and the
 * adjusted balance before and after in minor units, and the cause (spec 030 W2, FR-027).
 */
final case class MajorAdjustment(realBalance: String, bonusBalance: String, currency: String, primary: Option[MajorPrimary],
    depositPoolBalance: String, convertedPoolBalance: String, reserved: String, available: String,
    txnId: Option[String], walletType: String, amountMinor: Long, before: Long, after: Long, cause: Option[Cause])

private[api] object Major:
  /** reserved and available are written at "0.00" too: the read reports both (spec 022). */
  given JsonValueCodec[MajorBalance] =
    com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make(com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig.withDiscriminatorFieldName(Some("type")).withAllowRecursiveTypes(true).withRequireDiscriminatorFirst(false).withTransientEmpty(false).withTransientNone(false).withTransientDefault(false))
  given JsonValueCodec[MajorAdjustment] =
    com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make(com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig.withTransientEmpty(false).withTransientNone(true).withTransientDefault(false))
  given JsonValueCodec[MajorTransactions]     = Codecs.make[MajorTransactions]
  given JsonValueCodec[MajorLifetime]         = Codecs.make[MajorLifetime]
  given JsonValueCodec[OperatorAdjustRequest] = Codecs.make[OperatorAdjustRequest]
  given JsonValueCodec[TurnoverTermsRequest]  = Codecs.make[TurnoverTermsRequest]
  given JsonValueCodec[TurnoverOverrideRequest] = Codecs.make[TurnoverOverrideRequest]
  given JsonValueCodec[RestrictedFundsStatus] = Codecs.make[RestrictedFundsStatus]
  given JsonValueCodec[BrandConfig]           = Codecs.make[BrandConfig]
  given JsonValueCodec[OperatorAccountAdjustRequest] = Codecs.make[OperatorAccountAdjustRequest]
  given JsonValueCodec[MajorAccountBalance]   = Codecs.make[MajorAccountBalance]

  // Each takes the route's currency, which the route has checked, and writes every amount by its exponent.

  def accountBalance(b: io.eitheror.casino.wallet.accounts.AccountBalance, c: Currency, txnId: Option[String] = None): MajorAccountBalance =
    MajorAccountBalance(Wire.major(b.balance, c), Wire.major(b.creditLine, c), Wire.major(b.floor, c), b.owner, b.currency, txnId)

  def balance(b: Balance, c: Currency): MajorBalance =
    MajorBalance(Wire.major(b.realBalance, c), Wire.major(b.restrictedBalance, c), b.currency,
      b.primary.map(p => MajorPrimary(p.grantRef, p.status, Wire.major(p.requirement, c), Wire.major(p.wagered, c), p.progressPercent)),
      Wire.major(b.depositPoolBalance, c), Wire.major(b.convertedPoolBalance, c), Wire.major(b.reserved, c), Wire.major(b.available, c))

  def transactions(p: TransactionsPage, c: Currency): MajorTransactions =
    MajorTransactions(p.transactions.map(r => MajorTransaction(r.transactionId, r.`type`, r.walletType, Wire.major(r.amount, c),
      Wire.major(r.balanceBefore, c), Wire.major(r.balanceAfter, c), r.status, r.roundKey, r.awardRef, r.paymentReference, r.createdAt,
      Wire.major(r.depositPart, c), Wire.major(r.convertedPart, c), Wire.major(r.depositPoolAfter, c), Wire.major(r.convertedPoolAfter, c),
      r.reference, r.reason.map(_.reveal), r.category, r.cause, r.actorId)),
      p.page, p.pageSize, p.total)

  def adjustment(b: Balance, o: Outcome, target: String, c: Currency): MajorAdjustment =
    val m = balance(b, c)
    val before = o.balanceBefore.getOrElse(if target == "restricted" then o.restrictedBalance else o.realBalance)
    MajorAdjustment(m.realBalance, m.bonusBalance, m.currency, m.primary, m.depositPoolBalance, m.convertedPoolBalance, m.reserved,
      m.available, o.transactionId, if target == "restricted" then "bonus" else "real", o.amount.getOrElse(0L), before,
      o.balanceAfter.getOrElse(before), o.cause)

  /** The amount an adjustment names: `amount`, `amountMinor`, or both when they agree (spec 030 W2). */
  def adjustAmount(r: OperatorAdjustRequest, currency: Currency): Either[(String, Map[String, String]), Long] =
    (Option(r.amount).map(_.trim).filter(_.nonEmpty), r.amountMinor) match
      case (None, None)        => Left(("an adjustment names its amount, as amount or amountMinor", Map.empty))
      case (None, Some(minor)) => Right(minor)
      case (Some(text), minor) =>
        parse(text, currency) match
          case Left(message) => Left((message, Map("amount" -> text)))
          case Right(a) if minor.exists(_ != a) =>
            Left((s"amount '$text' and amountMinor ${minor.get} name different amounts", Map("amount" -> text, "amountMinor" -> minor.get.toString)))
          case Right(a) => Right(a)

  /** A cause names one of the contract's kinds and a reference of at most 200 characters (spec 030 W2). */
  def checkCause(cause: Option[Cause]): Either[(String, Map[String, String]), Option[Cause]] =
    cause match
      case None => Right(None)
      case Some(c) if !io.eitheror.casino.contract.facts.AdjustmentCause.Kinds(c.kind) =>
        Left((s"a cause's kind is one of ${io.eitheror.casino.contract.facts.AdjustmentCause.Kinds.toVector.sorted.mkString(", ")}", Map("kind" -> c.kind)))
      case Some(c) if c.ref.trim.isEmpty || c.ref.trim.length > 200 =>
        Left(("a cause names its reference, at most 200 characters", Map("kind" -> c.kind)))
      case Some(c) => Right(Some(c.copy(ref = c.ref.trim)))

  /**
   * An operator's amount in major units by the currency's exponent, as the reads write it (spec 027
   * FR-058): EUR "10.00" → 1000, CLP "5000" → 5000, KWD "1.234" → 1234; a sign is kept (an
   * adjustment may debit); more decimals than the currency has, or anything else, names the amount
   * (spec 001 FR-026).
   */
  def parse(text: String, currency: Currency): Either[String, Long] =
    val exp     = io.eitheror.casino.contract.money.Amounts.exponent(currency)
    val Pattern = """(-?)(\d{1,15})(?:\.(\d+))?""".r
    text.trim match
      case Pattern(sign, whole, fraction) if Option(fraction).forall(f => f.length <= exp) =>
        val minor = Option(fraction).fold(0L)(f => (f + "0" * exp).take(exp).toLong)
        // Exact arithmetic: 15 digits of a currency of 4 decimals pass a Long (review).
        scala.util.Try(Math.multiplyExact(Math.addExact(Math.multiplyExact(whole.toLong, BigInt(10).pow(exp).toLong), minor), if sign == "-" then -1L else 1L))
          .toOption.toRight(s"the amount '$text' is not an amount of ${currency.code}")
      case _ => Left(s"the amount '$text' is not an amount of ${currency.code}")

/**
 * The backoffice: a staff token of this brand whose roles' bags hold the route's permission (spec 030
 * amendment W1, `Routes.operatorMay` over the brand's roles as `bags` last read them): the adjustment
 * with its personal reason (FR-026, FR-033), the turnover terms and override (FR-004a), and the reads,
 * in major units.
 */
final class OperatorEndpoint(client: ComponentClient, views: ViewClient, brand: String,
    authenticate: => Acl = Oidc.authenticate(), clock: () => Instant = () => Instant.now(), bags: RoleBags)
    extends HttpEndpoint(s"/v1/casino/backoffice/$brand"):
  import Major.given
  import ServiceEndpoint.given

  private def may(permission: String): Acl = Routes.operatorMay(brand, permission, bags, authenticate)

  // every route names its own permission; this is never reached
  val acl: Acl = Acl.DenyAll

  private val transactions = views.forView(TransactionsView)
  private val lifetime     = views.forView(LifetimeTotalsView)

  private def wallet(player: String, currency: String) = client.forEventSourcedEntity(WalletEntity.id(player, currency))
  private def checked(currency: String)(answer: Currency => Respond[Json]): Respond[Json] =
    Currency.from(currency).fold(m => Wire.refusal("INVALID_REQUEST", m), answer)

  withAcl(may("adjustment.make")) {
  postBody("/wallets/{player}/{currency}/adjustments") { (player: String, currency: String, r: OperatorAdjustRequest) =>
    checked(currency) { c =>
      (Key.from(r.key), Major.adjustAmount(r, c), Major.checkCause(r.cause)) match
        case (Left(_), _, _)                  => Wire.missingKey
        case (_, Left((message, details)), _) => Wire.refusal("INVALID_REQUEST", message, details)
        case (_, _, Left((message, details))) => Wire.refusal("INVALID_REQUEST", message, details)
        case (Right(k), Right(a), Right(cause)) =>
          val target = if r.walletType == "bonus" || r.walletType == "restricted" then "restricted" else "real"
          val o = wallet(player, currency).call(WalletEntity.adjust)
            .invoke(Adjust(k.value, target, a, r.reason, r.category, principal.subject, clock(), cause))
          if o.accepted then Wire.json(200, Major.adjustment(wallet(player, currency).call(WalletEntity.balance).invoke(), o, target, c))
          else Wire.outcome(o, currency)
    }
  }
  }

  withAcl(may("turnover.override")) {
  putBody("/wallets/{player}/{currency}/turnover-override") { (player: String, currency: String, r: TurnoverOverrideRequest) =>
    checked(currency) { c =>
      val terms = r.multiplier.map(m => TurnoverTerms(m, r.definition.getOrElse("gross_wagered")))
      if terms.exists(_.multiplier < 0) then Wire.refusal("INVALID_REQUEST", "a turnover multiplier is a whole, non-negative number")
      else
        wallet(player, currency).call(WalletEntity.setTurnoverOverride).invoke(SetTurnoverOverride(terms, principal.subject, clock())): Unit
        Wire.json(200, Major.balance(wallet(player, currency).call(WalletEntity.balance).invoke(), c))
    }
  }
  }

  withAcl(may("wallet.read")) {
  get("/wallets/{player}/{currency}/balance") { (player: String, currency: String) =>
    checked(currency)(c => Wire.json(200, Major.balance(wallet(player, currency).call(WalletEntity.balance).invoke(), c)))
  }
  }

  withAcl(may("wallet.read")) {
  get("/wallets/{player}/{currency}/restricted-funds") { (player: String, currency: String) =>
    checked(currency)(_ => Wire.json(200, wallet(player, currency).call(WalletEntity.restrictedFundsStatus).invoke()))
  }
  }

  withAcl(may("wallet.read")) {
  get("/wallets/{player}/{currency}/transactions") { (player: String, currency: String) =>
    checked(currency)(c => Wire.json(200, Major.transactions(TransactionsQuery.page(transactions, s"$player/$currency",
      query.optional[String]("type"), query.optional[String]("walletType"), query.optional[Long]("from"), query.optional[Long]("to"),
      query.optional[Int]("page").getOrElse(0), query.optional[Int]("pageSize").getOrElse(50)), c)))
  }
  }

  withAcl(may("wallet.read")) {
  get("/wallets/{player}/{currency}/lifetime-totals") { (player: String, currency: String) =>
    val t = lifetime.get(s"$player/$currency").getOrElse(LifetimeTotals(s"$player/$currency", 0L, 0L, 0L))
    checked(currency)(c => Wire.json(200, MajorLifetime(Wire.major(t.deposited, c), Wire.major(t.wagered, c), Wire.major(t.won, c))))
  }
  }

  // ── spec 022: non-player accounts (FR-009, FR-012) ────────────────────────

  private def account(kind: String, name: String, currency: String) =
    client.forEventSourcedEntity(io.eitheror.casino.wallet.accounts.AccountEntity.id(kind, name, currency))

  private def accountChecked(kind: String, currency: String)(answer: Currency => Respond[Json]): Respond[Json] =
    if !io.eitheror.casino.wallet.accounts.AccountState.Kinds(kind) then Wire.refusal("INVALID_REQUEST", s"an account is pool or clearing, not '$kind'")
    else checked(currency)(answer)

  withAcl(may("adjustment.make")) {
  postBody("/accounts/{kind}/{name}/adjustments") { (kind: String, name: String, r: OperatorAccountAdjustRequest) =>
    accountChecked(kind, r.currency) { c =>
      (Key.from(r.key), Major.parse(r.amount, c)) match
        case (Left(_), _)       => Wire.missingKey
        case (_, Left(message)) => Wire.refusal("INVALID_REQUEST", message, Map("amount" -> r.amount))
        case (Right(k), Right(a)) =>
          val acc = account(kind, name, r.currency)
          val o = acc.call(io.eitheror.casino.wallet.accounts.AccountEntity.adjust)
            .invoke(io.eitheror.casino.wallet.accounts.AdjustAccount(k.value, a, r.reason, r.category, principal.subject, clock()))
          if !o.accepted then Wire.outcome(o, r.currency)
          else Wire.json(200, Major.accountBalance(acc.call(io.eitheror.casino.wallet.accounts.AccountEntity.balance).invoke(), c, o.transactionId))
    }
  }
  }

  withAcl(may("wallet.read")) {
  get("/accounts/{kind}/{name}/balance") { (kind: String, name: String) =>
    val currency = query.required[String]("currency")
    accountChecked(kind, currency) { c =>
      val b = account(kind, name, currency).call(io.eitheror.casino.wallet.accounts.AccountEntity.balance).invoke()
      if !b.found then Wire.refusal(Refusals.AccountNotFound, Refusals.accountNotFound(b.accountId))
      else Wire.json(200, Major.accountBalance(b, c))
    }
  }
  }

  withAcl(may("config.brand")) {
  putBody("/wallet-config/turnover") { (r: TurnoverTermsRequest) =>
    if r.multiplier < 0 then Wire.refusal("INVALID_REQUEST", "a turnover multiplier is a whole, non-negative number")
    else Wire.json(200, client.forKeyValueEntity(BrandConfigEntity.Id).call(BrandConfigEntity.setTurnoverTerms)
      .invoke(SetTurnoverTerms(TurnoverTerms(r.multiplier, r.definition), principal.subject, clock())))
  }
  }

  withAcl(may("wallet.read")) {
  get("/wallet-config") { () =>
    Wire.json(200, client.forKeyValueEntity(BrandConfigEntity.Id).call(BrandConfigEntity.get).invoke())
  }
  }

/**
 * The player site (`Routes.player(slug)`: the brand realm's token; the player is the token's
 * `eitheror_twin_id`, `Routes.playerId`, never a path segment), in major units (FR-032).
 */
final class PlayerEndpoint(client: ComponentClient, views: ViewClient, brand: String, authenticate: => Acl = Oidc.authenticate())
    extends HttpEndpoint(s"/v1/casino/players/$brand/wallets"):
  import Major.given

  val acl: Acl = Routes.player(brand, authenticate)

  private val transactions = views.forView(TransactionsView)

  /** The token's player (`Routes.playerId`); `checked` refuses a token naming none before this is read. */
  private def player: String = Routes.playerId(principal).getOrElse("")
  private def wallet(currency: String) = client.forEventSourcedEntity(WalletEntity.id(player, currency))
  private def checked(currency: String)(answer: Currency => Respond[Json]): Respond[Json] =
    if Routes.playerId(principal).isEmpty then Wire.refusal("player_id_mismatch", "the token names no player")
    else Currency.from(currency).fold(m => Wire.refusal("INVALID_REQUEST", m), answer)

  // Spec 031: each route described for the player SDK; the refusals are `checked`'s.
  override protected def description: Description = Described.refusals

  described(_.summary("The signed-in player's balance in one currency, in major units")
    .answers[MajorBalance](200).refuses(403, "player_id_mismatch").refuses(400, "INVALID_REQUEST").tag("player")) {
    get("/{currency}/balance") { (currency: String) =>
      checked(currency)(c => Wire.json(200, Major.balance(wallet(currency).call(WalletEntity.balance).invoke(), c)))
    }
  }

  described(_.summary("The signed-in player's restricted funds in one currency: each bonus record and the headline one")
    .answers[RestrictedFundsStatus](200).refuses(403, "player_id_mismatch").refuses(400, "INVALID_REQUEST").tag("player")) {
    get("/{currency}/restricted-funds") { (currency: String) =>
      checked(currency)(_ => Wire.json(200, wallet(currency).call(WalletEntity.restrictedFundsStatus).invoke()))
    }
  }

  described(_.summary("One page of the signed-in player's transactions in one currency, newest first, in major units")
    .answers[MajorTransactions](200).refuses(403, "player_id_mismatch").refuses(400, "INVALID_REQUEST")
    .query[String]("type").query[String]("walletType").query[Long]("from").query[Long]("to")
    .query[Int]("page").query[Int]("pageSize").tag("player")) {
    get("/{currency}/transactions") { (currency: String) =>
      checked(currency)(c => Wire.json(200, Major.transactions(TransactionsQuery.page(transactions, s"$player/$currency",
        query.optional[String]("type"), query.optional[String]("walletType"), query.optional[Long]("from"), query.optional[Long]("to"),
        query.optional[Int]("page").getOrElse(0), query.optional[Int]("pageSize").getOrElse(50)), c)))
    }
  }
