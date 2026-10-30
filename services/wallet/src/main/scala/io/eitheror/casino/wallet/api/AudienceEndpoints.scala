package io.eitheror.casino.wallet.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.money.{Currency, Key}
import io.eitheror.casino.wallet.config.{BrandConfig, BrandConfigEntity, SetTurnoverTerms}
import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.views.*
import io.eitheror.casino.wallet.wire.*

import java.time.Instant

/** The player site's and backoffice's amounts: major units with two decimals (FR-003). */
final case class MajorBalance(realBalance: String, bonusBalance: String, currency: String, primary: Option[MajorPrimary],
    depositPoolBalance: String, convertedPoolBalance: String)
final case class MajorPrimary(grantRef: String, status: String, wageringRequirement: String, wagered: String, progressPercent: Long)
final case class MajorTransaction(transactionId: String, `type`: String, walletType: String, amount: String, balanceBefore: String,
    balanceAfter: String, status: String, roundId: Option[String], awardRef: Option[String], paymentReference: Option[String],
    createdAt: String, depositPart: String, convertedPart: String, depositPoolAfter: String, convertedPoolAfter: String)
final case class MajorTransactions(transactions: Vector[MajorTransaction], page: Int, pageSize: Int, total: Long)
final case class MajorLifetime(totalDeposited: String, totalWagered: String, totalWon: String)
final case class OperatorAdjustRequest(key: Option[String], walletType: String = "real", amount: String, reason: String = "", category: String = "other")

private[api] object Major:
  given JsonValueCodec[MajorBalance]          = Codecs.make[MajorBalance]
  given JsonValueCodec[MajorTransactions]     = Codecs.make[MajorTransactions]
  given JsonValueCodec[MajorLifetime]         = Codecs.make[MajorLifetime]
  given JsonValueCodec[OperatorAdjustRequest] = Codecs.make[OperatorAdjustRequest]
  given JsonValueCodec[TurnoverTermsRequest]  = Codecs.make[TurnoverTermsRequest]
  given JsonValueCodec[TurnoverOverrideRequest] = Codecs.make[TurnoverOverrideRequest]
  given JsonValueCodec[RestrictedFundsStatus] = Codecs.make[RestrictedFundsStatus]
  given JsonValueCodec[BrandConfig]           = Codecs.make[BrandConfig]

  def balance(b: Balance): MajorBalance =
    MajorBalance(Wire.major(b.realBalance), Wire.major(b.restrictedBalance), b.currency,
      b.primary.map(p => MajorPrimary(p.grantRef, p.status, Wire.major(p.requirement), Wire.major(p.wagered), p.progressPercent)),
      Wire.major(b.depositPoolBalance), Wire.major(b.convertedPoolBalance))

  def transactions(p: TransactionsPage): MajorTransactions =
    MajorTransactions(p.transactions.map(r => MajorTransaction(r.transactionId, r.`type`, r.walletType, Wire.major(r.amount),
      Wire.major(r.balanceBefore), Wire.major(r.balanceAfter), r.status, r.roundKey, r.awardRef, r.paymentReference, r.createdAt,
      Wire.major(r.depositPart), Wire.major(r.convertedPart), Wire.major(r.depositPoolAfter), Wire.major(r.convertedPoolAfter))),
      p.page, p.pageSize, p.total)

  /** "10.00" → 1000: at most two decimals; anything else names the amount (spec 001 FR-026). */
  def parse(text: String): Either[String, Long] =
    val Pattern = """(-?)(\d{1,17})(?:\.(\d{1,2}))?""".r
    text.trim match
      case Pattern(sign, whole, fraction) =>
        val cents = Option(fraction).fold(0L)(f => (f + "0").take(2).toLong)
        Right((if sign == "-" then -1 else 1) * (whole.toLong * 100 + cents))
      case _ => Left(s"the amount '$text' is not a whole number of minor units")

/**
 * The backoffice (`Routes.operator(slug)`: a staff token whose realm roles name this brand): the
 * adjustment with its personal reason (FR-026, FR-033), the turnover terms and override (FR-004a),
 * and the reads, in major units.
 */
final class OperatorEndpoint(client: ComponentClient, views: ViewClient, brand: String,
    authenticate: => Acl = Oidc.authenticate(), clock: () => Instant = () => Instant.now())
    extends HttpEndpoint(s"/v1/casino/backoffice/$brand"):
  import Major.given
  import ServiceEndpoint.given

  val acl: Acl = Routes.operator(brand, authenticate)

  private val transactions = views.forView(TransactionsView)
  private val lifetime     = views.forView(LifetimeTotalsView)

  private def wallet(player: String, currency: String) = client.forEventSourcedEntity(WalletEntity.id(player, currency))
  private def checked(currency: String)(answer: => Respond[Bytes]): Respond[Bytes] =
    Currency.from(currency).fold(m => Wire.refusal("INVALID_REQUEST", m), _ => answer)

  postBody("/wallets/{player}/{currency}/adjustments") { (player: String, currency: String, r: OperatorAdjustRequest) =>
    checked(currency) {
      (Key.from(r.key), Major.parse(r.amount)) match
        case (Left(_), _)        => Wire.missingKey
        case (_, Left(message))  => Wire.refusal("INVALID_REQUEST", message, Map("amount" -> r.amount))
        case (Right(k), Right(a)) =>
          val target = if r.walletType == "bonus" || r.walletType == "restricted" then "restricted" else "real"
          val o = wallet(player, currency).call(WalletEntity.adjust).invoke(Adjust(k.value, target, a, r.reason, r.category, principal.subject, clock()))
          if o.accepted then Wire.json(200, Major.balance(wallet(player, currency).call(WalletEntity.balance).invoke()))
          else Wire.outcome(o, currency)
    }
  }

  putBody("/wallets/{player}/{currency}/turnover-override") { (player: String, currency: String, r: TurnoverOverrideRequest) =>
    checked(currency) {
      val terms = r.multiplier.map(m => TurnoverTerms(m, r.definition.getOrElse("gross_wagered")))
      if terms.exists(_.multiplier < 0) then Wire.refusal("INVALID_REQUEST", "a turnover multiplier is a whole, non-negative number")
      else
        wallet(player, currency).call(WalletEntity.setTurnoverOverride).invoke(SetTurnoverOverride(terms, principal.subject, clock())): Unit
        Wire.json(200, Major.balance(wallet(player, currency).call(WalletEntity.balance).invoke()))
    }
  }

  get("/wallets/{player}/{currency}/balance") { (player: String, currency: String) =>
    checked(currency)(Wire.json(200, Major.balance(wallet(player, currency).call(WalletEntity.balance).invoke())))
  }

  get("/wallets/{player}/{currency}/restricted-funds") { (player: String, currency: String) =>
    checked(currency)(Wire.json(200, wallet(player, currency).call(WalletEntity.restrictedFundsStatus).invoke()))
  }

  get("/wallets/{player}/{currency}/transactions") { (player: String, currency: String) =>
    checked(currency)(Wire.json(200, Major.transactions(TransactionsQuery.page(transactions, s"$player/$currency",
      query.optional[String]("type"), query.optional[String]("walletType"), query.optional[Long]("from"), query.optional[Long]("to"),
      query.optional[Int]("page").getOrElse(0), query.optional[Int]("pageSize").getOrElse(50)))))
  }

  get("/wallets/{player}/{currency}/lifetime-totals") { (player: String, currency: String) =>
    val t = lifetime.get(s"$player/$currency").getOrElse(LifetimeTotals(s"$player/$currency", 0L, 0L, 0L))
    checked(currency)(Wire.json(200, MajorLifetime(Wire.major(t.deposited), Wire.major(t.wagered), Wire.major(t.won))))
  }

  putBody("/wallet-config/turnover") { (r: TurnoverTermsRequest) =>
    if r.multiplier < 0 then Wire.refusal("INVALID_REQUEST", "a turnover multiplier is a whole, non-negative number")
    else Wire.json(200, client.forKeyValueEntity(BrandConfigEntity.Id).call(BrandConfigEntity.setTurnoverTerms)
      .invoke(SetTurnoverTerms(TurnoverTerms(r.multiplier, r.definition), principal.subject, clock())))
  }

  get("/wallet-config") { () =>
    Wire.json(200, client.forKeyValueEntity(BrandConfigEntity.Id).call(BrandConfigEntity.get).invoke())
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
  private def checked(currency: String)(answer: => Respond[Bytes]): Respond[Bytes] =
    if Routes.playerId(principal).isEmpty then Wire.refusal("player_id_mismatch", "the token names no player")
    else Currency.from(currency).fold(m => Wire.refusal("INVALID_REQUEST", m), _ => answer)

  get("/{currency}/balance") { (currency: String) =>
    checked(currency)(Wire.json(200, Major.balance(wallet(currency).call(WalletEntity.balance).invoke())))
  }

  get("/{currency}/restricted-funds") { (currency: String) =>
    checked(currency)(Wire.json(200, wallet(currency).call(WalletEntity.restrictedFundsStatus).invoke()))
  }

  get("/{currency}/transactions") { (currency: String) =>
    checked(currency)(Wire.json(200, Major.transactions(TransactionsQuery.page(transactions, s"$player/$currency",
      query.optional[String]("type"), query.optional[String]("walletType"), query.optional[Long]("from"), query.optional[Long]("to"),
      query.optional[Int]("page").getOrElse(0), query.optional[Int]("pageSize").getOrElse(50)))))
  }
