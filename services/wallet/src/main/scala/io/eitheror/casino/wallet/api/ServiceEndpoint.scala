package io.eitheror.casino.wallet.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{Codecs, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewClient}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.Layout.GrantTarget
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.money.{Currency, Key}
import io.eitheror.casino.wallet.config.{BrandConfigEntity, SetBetSwitches}
import io.eitheror.casino.wallet.deposits.{DepositStart, DepositWorkflow}
import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.payments.{PaymentEntity, PaymentOutcome}
import io.eitheror.casino.wallet.publish.Facts
import io.eitheror.casino.wallet.views.*
import io.eitheror.casino.wallet.wire.*
import io.eitheror.casino.wallet.wire.Wire.given

import java.time.Instant

/**
 * The routes of the project's own services (`Routes.service()`, no token: the caller's identity is
 * the platform's) and the two routes the brand grants `payments`/`merchant` (spec 001 FR-016), under
 * `/v1/casino/brands/<brand>`. The brand is this project's, fixed in the prefix: another brand's path
 * matches no route (FR-032, spec 001 FR-005).
 *
 * Thin: boundary checks (key present, amount whole, currency a code); every rule is the entity's. A
 * deposit is a `DepositWorkflow` the route starts and polls (FR-022, research R17); a bet asks
 * nothing of anyone, the provider's workflow having asked `responsible-gambling` (spec 003).
 */
final class ServiceEndpoint(
    client: ComponentClient,
    views: ViewClient,
    brand: String,
    clock: () => Instant = () => Instant.now()
) extends HttpEndpoint(s"/v1/casino/brands/$brand"):
  import ServiceEndpoint.{*, given}

  val acl: Acl = Routes.service()

  private val transactions = views.forView(TransactionsView)
  private val sums         = views.forView(SumsView)
  private val lifetime     = views.forView(LifetimeTotalsView)

  private def wallet(player: String, currency: String) = client.forEventSourcedEntity(WalletEntity.id(player, currency))
  private def brandConfig = client.forKeyValueEntity(BrandConfigEntity.Id).call(BrandConfigEntity.get).invoke()

  /** The boundary: a currency code, a key, a whole amount; then the command, rendered. */
  private def money(currency: String)(command: => Either[Respond[Bytes], Outcome]): Respond[Bytes] =
    Currency.from(currency) match
      case Left(message) => Wire.refusal("INVALID_REQUEST", message)
      case Right(_)      => command.fold(identity, Wire.outcome(_, currency))

  private def key(k: Option[String]): Either[Respond[Bytes], String] = Key.from(k).map(_.value).left.map(_ => Wire.missingKey)
  private def amount(a: AmountText): Either[Respond[Bytes], Long]   = a.minorUnits.map(_.value).left.map(Wire.invalidAmount)

  // ── bets (game-provider) ──────────────────────────────────────────────────

  postBody("/wallets/{player}/{currency}/bets") { (player: String, currency: String, r: BetRequest) =>
    money(currency) {
      for k <- key(r.key); a <- amount(r.amount) yield
        val switches = brandConfig.switchesFor(r.gameType)
        wallet(player, currency).call(WalletEntity.placeBet)
          .invoke(PlaceBet(k, r.roundId, r.gameId, r.sessionId, r.provider, r.category, a, r.allowNegative.getOrElse(false), switches, clock()))
    }
  }

  postBody("/wallets/{player}/{currency}/wins") { (player: String, currency: String, r: WinRequest) =>
    money(currency)(for k <- key(r.key); a <- amount(r.amount) yield wallet(player, currency).call(WalletEntity.creditWin).invoke(CreditWin(k, r.roundId, a, clock(), r.betKey)))
  }

  postBody("/wallets/{player}/{currency}/rollbacks") { (player: String, currency: String, r: RollbackRequest) =>
    money(currency)(key(r.key).map(k => wallet(player, currency).call(WalletEntity.rollbackBet).invoke(RollbackBet(k, r.roundId, r.betKey, clock()))))
  }

  postBody("/wallets/{player}/{currency}/losses") { (player: String, currency: String, r: LossRequest) =>
    money(currency)(key(r.key).map(k => wallet(player, currency).call(WalletEntity.settleLoss).invoke(SettleLoss(k, r.roundId, clock(), r.betKey))))
  }

  postBody("/wallets/{player}/{currency}/provider-credits") { (player: String, currency: String, r: ProviderCreditRequest) =>
    money(currency)(for k <- key(r.key); a <- amount(r.amount) yield
      wallet(player, currency).call(WalletEntity.providerCredit).invoke(ProviderCredit(k, a, r.providerRef, r.reason, r.actor, clock())))
  }

  // ── restricted funds (rewards) ────────────────────────────────────────────

  postBody("/wallets/{player}/{currency}/awards") { (player: String, currency: String, r: AwardRequest) =>
    money(currency) {
      for k <- key(r.key); a <- amount(r.amount) yield
        val rules = Rules(r.maxStake, r.maxStakeEnforcement, r.contributionDefault, r.contributionOverrides, r.scopeInclude,
          r.scopeExclude, r.scopeViolationMode, r.fundingOrder, r.winAllocation, r.releaseProgressScope, r.forfeitOnWithdrawal)
        wallet(player, currency).call(WalletEntity.award).invoke(Award(k, r.bonusRef.getOrElse(""), a, r.wageringMultiplier, r.maxWin,
          r.priority.getOrElse(0L), r.expiresAt, rules, brandConfig.maxConcurrentAwards, clock()))
    }
  }

  postBody("/wallets/{player}/{currency}/awards/forfeit") { (player: String, currency: String, r: GrantRequest) =>
    money(currency)(key(r.key).map(k => wallet(player, currency).call(WalletEntity.forfeit).invoke(ForfeitAward(k, r.grantRef, clock()))))
  }

  postBody("/wallets/{player}/{currency}/awards/cancel") { (player: String, currency: String, r: GrantRequest) =>
    money(currency)(key(r.key).map(k => wallet(player, currency).call(WalletEntity.cancel).invoke(CancelAward(k, r.grantRef, clock()))))
  }

  postBody("/wallets/{player}/{currency}/awards/close-pending-cap") { (player: String, currency: String, r: KeyRequest) =>
    money(currency)(key(r.key).map(k => wallet(player, currency).call(WalletEntity.closePendingCap).invoke(ClosePendingCap(k, clock()))))
  }

  /** The grant to make primary rides in the body: a handler takes at most two path parameters. */
  postBody("/wallets/{player}/{currency}/awards/switch") { (player: String, currency: String, r: GrantRequest) =>
    money(currency) {
      for
        k <- key(r.key)
        g <- r.grantRef.filter(_.nonEmpty).toRight(Wire.refusal("INVALID_REQUEST", "grantRef_required"))
      yield wallet(player, currency).call(WalletEntity.switchPrimary).invoke(SwitchPrimary(k, g, clock()))
    }
  }

  get("/wallets/{player}/{currency}/restricted-funds") { (player: String, currency: String) =>
    Wire.json(200, wallet(player, currency).call(WalletEntity.restrictedFundsStatus).invoke())
  }

  putBody("/wallet-config/bet-switches") { (r: BetSwitchesRequest) =>
    val by = caller match
      case Caller.Service(_, name) => name
      case other                   => other.toString
    Wire.json(200, client.forKeyValueEntity(BrandConfigEntity.Id).call(BrandConfigEntity.setBetSwitches)
      .invoke(SetBetSwitches(r.contributionRateByGameType, r.bonusWageringCounts, r.partialSplitsAllowed, r.maxConcurrentAwards, by, clock())))
  }

  // ── holds (payouts) ───────────────────────────────────────────────────────

  postBody("/wallets/{player}/{currency}/holds") { (player: String, currency: String, r: HoldRequest) =>
    money(currency)(for k <- key(r.key); a <- amount(r.amount) yield
      wallet(player, currency).call(WalletEntity.hold).invoke(Hold(k, a, r.playerConfirmed, clock())))
  }

  get("/wallets/{player}/{currency}/holds/preview") { (player: String, currency: String) =>
    AmountText(query.required[String]("amount")).minorUnits match
      case Left(e)  => Wire.invalidAmount(e)
      case Right(a) => Wire.json(200, wallet(player, currency).call(WalletEntity.previewHold).invoke(PreviewHold(a.value)))
  }

  /** `requestId` rides in the body for the same reason as the grant (two path parameters at most). */
  postBody("/wallets/{player}/{currency}/holds/release") { (player: String, currency: String, r: HoldLifecycleRequest) =>
    money(currency) {
      for
        k <- key(r.key)
        o <- r.outcome.filter(Set("cancelled", "rejected", "failed")).toRight(Wire.refusal("INVALID_REQUEST", "outcome is one of cancelled, rejected, failed"))
      yield wallet(player, currency).call(WalletEntity.release).invoke(Release(k, r.requestId, o, clock()))
    }
  }

  postBody("/wallets/{player}/{currency}/holds/confirm") { (player: String, currency: String, r: HoldLifecycleRequest) =>
    money(currency)(key(r.key).map(k => wallet(player, currency).call(WalletEntity.confirm).invoke(Confirm(k, r.requestId, clock()))))
  }

  postBody("/wallets/{player}/{currency}/holds/reverse") { (player: String, currency: String, r: HoldLifecycleRequest) =>
    money(currency)(key(r.key).map(k => wallet(player, currency).call(WalletEntity.reverse).invoke(Reverse(k, r.requestId, clock()))))
  }

  // ── adjustments (support-agent) ───────────────────────────────────────────

  postBody("/wallets/{player}/{currency}/adjustments") { (player: String, currency: String, r: AdjustRequest) =>
    money(currency) {
      for
        k <- key(r.key)
        a <- r.amount.signed.map(_.value).left.map(Wire.invalidAmount)
      yield wallet(player, currency).call(WalletEntity.adjust)
        .invoke(Adjust(k, if r.walletType == "bonus" || r.walletType == "restricted" then "restricted" else "real", a, r.reason, r.category, r.actor, clock()))
    }
  }

  // ── reads ─────────────────────────────────────────────────────────────────

  get("/wallets/{player}/{currency}/balance") { (player: String, currency: String) =>
    Wire.json(200, wallet(player, currency).call(WalletEntity.balance).invoke())
  }

  get("/wallets/{player}/{currency}/deposit-sums") { (player: String, currency: String) =>
    Wire.json(200, wallet(player, currency).call(WalletEntity.depositSums).invoke(DepositSumsAt(clock())))
  }

  get("/wallets/{player}/{currency}/lifetime-totals") { (player: String, currency: String) =>
    val id = s"$player/$currency"
    Wire.json(200, lifetime.get(id).getOrElse(LifetimeTotals(id, 0L, 0L, 0L)))
  }

  get("/wallets/{player}/{currency}/sums") { (player: String, currency: String) =>
    val kind = query.required[String]("type")
    val from = query.optional[String]("from").getOrElse("0000-01-01")
    val to   = query.optional[String]("to").getOrElse("9999-12-31")
    val rows = sums.where(
      jsonText("walletId") ++ sql" = ${s"$player/$currency"} AND " ++ jsonText("type") ++ sql" = $kind AND " ++
        jsonText("day") ++ sql" >= $from AND " ++ jsonText("day") ++ sql" <= $to"
    )
    Wire.json(200, SumReply(kind, rows.map(_.total).sum, currency, rows.map(_.count).sum))
  }

  get("/wallets/{player}/{currency}/transactions") { (player: String, currency: String) =>
    Wire.json(200, TransactionsQuery.page(transactions, s"$player/$currency", query.optional[String]("type"),
      query.optional[String]("walletType"), query.optional[Long]("from"), query.optional[Long]("to"),
      query.optional[Int]("page").getOrElse(0), query.optional[Int]("pageSize").getOrElse(50)))
  }

  // ── erasure (FR-033) ──────────────────────────────────────────────────────

  /**
   * The wallet holds one personal field, an adjustment's reason, under the player's subject; destroying
   * the subject's key (ankka 042) erases it, so there is nothing else to delete, and the wallet confirms.
   * Until 042 ships this is a route `players` calls, admitted by name (Layout's erasure grant).
   */
  withAcl(Acl.allowCallers(Callers.service("players"))) {
    post("/erasure/players/{playerId}") { (playerId: String) =>
      Wire.json(200, ErasureReply(s"player/$playerId", confirmed = true, destroyed = Vector.empty,
        note = "the wallet's only personal field, an adjustment's reason, is held under this subject's key"))
    }
  }

  // ── granted to payments/merchant (FR-022, FR-022a) ────────────────────────

  withAcl(Routes.service(Some(GrantTarget.Route("wallet", "POST", "/v1/casino/brands/{brand}/wallets/{player}/{currency}/deposits")))) {
    postBody("/wallets/{player}/{currency}/deposits") { (player: String, currency: String, r: DepositRequest) =>
      money(currency) {
        for
          k <- key(r.paymentId)
          a <- amount(r.amount)
        yield
          val deposit = Deposit(k, a, r.paymentProvider, r.terminal, r.psp, r.scheme, brandConfig.turnover, clock())
          val flow    = client.forWorkflow(DepositWorkflow.id(player, currency, k))
          Flows.claim(flow.call(DepositWorkflow.start).invoke(DepositStart(player, currency, deposit)))
          Flows.await(flow.call(DepositWorkflow.status).invoke().outcome).getOrElse(Flows.pending)
      }
    }
  }

  withAcl(Routes.service(Some(GrantTarget.Route("wallet", "POST", "/v1/casino/brands/{brand}/payments/{paymentId}/outcome")))) {
    postBody("/payments/{paymentId}/outcome") { (paymentId: String, r: OutcomeRequest) =>
      if !Facts.outcomeKinds.contains(r.outcome) then Wire.refusal("INVALID_REQUEST", s"'${r.outcome}' is not a payment outcome")
      else if Facts.needsCurrency(r.outcome) && r.currency.forall(c => Currency.from(c).isLeft) then
        Wire.refusal("INVALID_REQUEST", s"a ${r.outcome} outcome names its currency")
      else
        val recorded = client.forEventSourcedEntity(EntityId(paymentId)).call(PaymentEntity.record).invoke(
          PaymentOutcome(r.outcome, r.playerId, r.paymentProvider, r.terminal, r.amount, r.currency, r.code, r.reason, r.disputeOutcome, clock()))
        Wire.json(200, OutcomeReply(recorded = true, firstRecorded = recorded.recorded))
    }
  }

object ServiceEndpoint:
  given JsonValueCodec[BetRequest]            = Codecs.make[BetRequest]
  given JsonValueCodec[WinRequest]            = Codecs.make[WinRequest]
  given JsonValueCodec[RollbackRequest]       = Codecs.make[RollbackRequest]
  given JsonValueCodec[LossRequest]           = Codecs.make[LossRequest]
  given JsonValueCodec[ProviderCreditRequest] = Codecs.make[ProviderCreditRequest]
  given JsonValueCodec[AwardRequest]          = Codecs.make[AwardRequest]
  given JsonValueCodec[GrantRequest]          = Codecs.make[GrantRequest]
  given JsonValueCodec[KeyRequest]            = Codecs.make[KeyRequest]
  given JsonValueCodec[HoldRequest]           = Codecs.make[HoldRequest]
  given JsonValueCodec[HoldLifecycleRequest]  = Codecs.make[HoldLifecycleRequest]
  given JsonValueCodec[AdjustRequest]         = Codecs.make[AdjustRequest]
  given JsonValueCodec[DepositRequest]        = Codecs.make[DepositRequest]
  given JsonValueCodec[OutcomeRequest]        = Codecs.make[OutcomeRequest]
  given JsonValueCodec[BetSwitchesRequest]    = Codecs.make[BetSwitchesRequest]
  given JsonValueCodec[OutcomeReply]          = Codecs.make[OutcomeReply]
  given JsonValueCodec[ErasureReply]          = Codecs.make[ErasureReply]
  given JsonValueCodec[SumReply]              = Codecs.make[SumReply]
  /** Every field of a row is present, null where it does not apply, as the original's rows were (reads.feature). */
  given JsonValueCodec[TransactionsPage] =
    com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make(
      com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig.withTransientNone(false).withTransientDefault(false).withTransientEmpty(false))
  given JsonValueCodec[Balance]               = Codecs.make[Balance]
  given JsonValueCodec[RestrictedFundsStatus] = Codecs.make[RestrictedFundsStatus]
  given JsonValueCodec[DepositSums]           = Codecs.make[DepositSums]
  given JsonValueCodec[LifetimeTotals]        = Codecs.make[LifetimeTotals]
  given JsonValueCodec[HoldPreview]           = Codecs.make[HoldPreview]
  given JsonValueCodec[io.eitheror.casino.wallet.config.BrandConfig] = Codecs.make[io.eitheror.casino.wallet.config.BrandConfig]

/** The erasure handler's confirmation (FR-033). */
final case class ErasureReply(subject: String, confirmed: Boolean, destroyed: Vector[String], note: String)

/** A hold's release, confirmation or reversal names the request in the body. */
final case class HoldLifecycleRequest(key: Option[String], requestId: String, outcome: Option[String] = None)

/** The outcome route's answer: recorded, and whether this call was the first to record it. */
final case class OutcomeReply(recorded: Boolean, firstRecorded: Boolean)

/** The original's `transaction-sum` answer. */
final case class SumReply(`type`: String, total: Long, currency: String, count: Long)

/** The original's transactions answer: `{transactions, page, pageSize, total}`. */
final case class TransactionsPage(transactions: Vector[TransactionRow], page: Int, pageSize: Int, total: Long)

/** The transactions read (FR-029): by wallet, optional type, wallet type and time range, newest first, paged. */
object TransactionsQuery:
  val MaxPageSize = 200

  def page(rows: com.thinkmorestupidless.ankka.runtime.ViewQueries[TransactionRow], walletId: String, kind: Option[String],
      walletType: Option[String], fromMillis: Option[Long], toMillis: Option[Long], page: Int, pageSize: Int): TransactionsPage =
    val size = math.max(1, math.min(pageSize, MaxPageSize))
    val condition =
      Vector(
        Some(jsonText("walletId") ++ sql" = $walletId"),
        kind.map(k => jsonText("type") ++ sql" = $k"),
        walletType.map(w => jsonText("walletType") ++ sql" = $w"),
        fromMillis.map(f => jsonNumber("createdAtMillis") ++ sql" >= $f"),
        toMillis.map(t => jsonNumber("createdAtMillis") ++ sql" <= $t")
      ).flatten.reduce((a, b) => a ++ SqlFragment.raw(" AND ") ++ b)
    val total = rows.count(condition)
    val found = rows.ordered(condition, jsonNumber("createdAtMillis") ++ SqlFragment.raw(" DESC, ") ++ jsonText("transactionId"),
      limit = (page + 1) * size)
    TransactionsPage(found.drop(page * size), page, size, total)
