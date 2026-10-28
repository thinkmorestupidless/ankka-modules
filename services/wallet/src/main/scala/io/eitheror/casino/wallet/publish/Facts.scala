package io.eitheror.casino.wallet.publish

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.wallet.entity.WalletEvent
import io.eitheror.casino.wallet.entity.WalletEvent.*
import io.eitheror.casino.wallet.funding.{Forfeit, Parts, RecordUpdate}
import io.eitheror.casino.wallet.payments.{PaymentEvent, PaymentOutcome}

/**
 * The wallet's events as the contract's facts (contracts/facts.md), as pure functions so the mapping
 * is tested with nothing running. Each fact names the id a reader deduplicates on (`dedupeId`): the
 * transaction id, or `<transactionId>/<recordId>` where one event yields a fact per record.
 *
 * The journal's event types never leave the service; these shapes are the contract's (constitution IV).
 */
object Facts:

  final case class Published[F](fact: F, dedupeId: String)

  private def units(v: Long): MinorUnits = MinorUnits.unsafe(math.max(0L, v))

  private def grantOf(updates: Vector[RecordUpdate], recordId: String): String =
    updates.find(_.id == recordId).fold("")(_.grantRef)

  /** `money.transactions` for one wallet event; audit-only events yield nothing. */
  def moneyTransactions(walletId: String, event: WalletEvent): Vector[Published[f.MoneyTransactionsFact]] =
    val playerId = walletId.takeWhile(_ != '/')
    val currency = Currency.unsafe(walletId.drop(walletId.indexOf('/') + 1))
    def real(v: Long)  = SignedMinorUnits(v)
    def one(fact: f.MoneyTransactionsFact, id: String) = Vector(Published(fact, id))

    // Spec 025: every fact carries both pools after it; each that moves real balance its parts.
    def dep(v: Long)   = Some(real(v))
    def part(v: Long)  = Some(units(v))

    def forfeited(txId: String, forfeits: Vector[Forfeit], cause: String, clawback: Long, clawbackParts: Parts,
        updates: Vector[RecordUpdate], realAfter: Long, restrictedAfter: Long, depositAfter: Long, convertedAfter: Long,
        at: java.time.Instant): Vector[Published[f.MoneyTransactionsFact]] =
      if forfeits.isEmpty && clawback > 0 then
        // A cancel with no open record still claws back (FR-017): the movement is on the topic.
        one(f.RestrictedFundsForfeited(playerId, currency, txId, "", units(0), cause, units(clawback), real(realAfter),
          units(restrictedAfter), at, dep(depositAfter), dep(convertedAfter), part(clawbackParts.deposit),
          part(clawbackParts.converted)), txId)
      else
        forfeits.zipWithIndex.map { (x, i) =>
          val parts = if i == 0 then clawbackParts else Parts.zero
          Published(
            f.RestrictedFundsForfeited(playerId, currency, txId, grantOf(updates, x.recordId), units(x.amount), cause,
              units(if i == 0 then clawback else 0L), real(realAfter), units(restrictedAfter), at, dep(depositAfter),
              dep(convertedAfter), part(parts.deposit), part(parts.converted)),
            s"$txId/${x.recordId}"
          )
        }

    event match
      case e: DepositConfirmed =>
        one(f.DepositConfirmed(playerId, currency, e.transactionId, units(e.amount), e.key, e.depositOrdinal, real(e.realAfter),
          units(e.restrictedAfter), e.at, dep(e.depositAfter), dep(e.convertedAfter), part(e.parts.deposit),
          part(e.parts.converted)), e.transactionId)

      case e: BetPlaced =>
        val parts = e.bet.parts.getOrElse(Parts.zero)
        val bet = one(f.BetPlaced(playerId, currency, e.bet.transactionId, e.bet.roundKey, e.bet.gameId, units(e.bet.amount),
          e.bet.walletType, real(e.realAfter), units(e.restrictedAfter), e.at, dep(e.depositAfter), dep(e.convertedAfter),
          part(parts.deposit), part(parts.converted)), e.bet.transactionId)
        bet ++ e.conversions.map { c =>
          Published(f.RestrictedFundsConverted(playerId, currency, e.bet.transactionId, grantOf(e.recordUpdates, c.recordId),
            units(c.amount), real(e.realAfter), units(e.restrictedAfter), e.at, dep(e.depositAfter), dep(e.convertedAfter),
            part(0L), part(c.amount)), s"${e.bet.transactionId}/${c.recordId}")
        }

      case e: WinCredited =>
        one(f.WinCredited(playerId, currency, e.transactionId, e.roundKey, e.gameId, units(e.amount), e.walletType,
          real(e.realAfter), units(e.restrictedAfter), e.at, dep(e.depositAfter), dep(e.convertedAfter), part(e.parts.deposit),
          part(e.parts.converted)), e.transactionId)

      case e: BetRolledBack =>
        one(f.BetRolledBack(playerId, currency, e.transactionId, e.roundKey, units(e.amount), real(e.realAfter),
          units(e.restrictedAfter), e.at, dep(e.depositAfter), dep(e.convertedAfter), part(e.parts.deposit),
          part(e.parts.converted)), e.transactionId)

      case e: RoundSettledAsLoss =>
        one(f.RoundSettledAsLoss(playerId, currency, e.transactionId, e.roundKey, real(e.realAfter), units(e.restrictedAfter), e.at,
          dep(e.depositAfter), dep(e.convertedAfter)), e.transactionId) ++
          forfeited(e.transactionId, e.forfeited, "loss", 0L, Parts.zero, e.recordUpdates, e.realAfter, e.restrictedAfter,
            e.depositAfter, e.convertedAfter, e.at)

      case e: RestrictedFundsAwarded =>
        one(f.RestrictedFundsAwarded(playerId, currency, e.transactionId, e.awarded.grantRef, units(e.awarded.record.balance),
          units(e.awarded.record.releaseRequirement), real(e.realAfter), units(e.restrictedAfter), e.at, dep(e.depositAfter),
          dep(e.convertedAfter)), e.transactionId)

      case e: RestrictedFundsForfeited =>
        forfeited(e.transactionId, e.forfeits, e.cause, 0L, Parts.zero, e.recordUpdates, e.realAfter, e.restrictedAfter,
          e.depositAfter, e.convertedAfter, e.at)

      case e: RestrictedFundsCancelled =>
        forfeited(e.transactionId, e.forfeits, "cancel", e.clawback, e.parts, e.recordUpdates, e.realAfter, e.restrictedAfter,
          e.depositAfter, e.convertedAfter, e.at)

      case e: HoldPlaced =>
        // The hold's amount is paid + voided; its parts are those two together, the voided pair apart.
        val held = e.paidParts + e.voidedParts
        one(f.HoldPlaced(playerId, currency, e.transactionId, e.requestId, units(e.amount), units(e.voided), real(e.realAfter),
          units(e.restrictedAfter), e.at, dep(e.depositAfter), dep(e.convertedAfter), part(held.deposit), part(held.converted),
          part(e.voidedParts.deposit), part(e.voidedParts.converted)), e.transactionId) ++
          forfeited(e.transactionId, e.forfeits, "hold", e.clawback, e.clawbackParts, e.recordUpdates, e.realAfter,
            e.restrictedAfter, e.depositAfter, e.convertedAfter, e.at)

      case e: HoldReleased =>
        one(f.HoldReleased(playerId, currency, e.transactionId, e.requestId, units(e.amount), real(e.realAfter),
          units(e.restrictedAfter), e.at, dep(e.depositAfter), dep(e.convertedAfter), part(e.parts.deposit),
          part(e.parts.converted)), e.transactionId)

      case e: WithdrawalReversed =>
        one(f.WalletWithdrawalReversed(playerId, currency, e.transactionId, e.requestId, units(e.amount), real(e.realAfter),
          units(e.restrictedAfter), e.at, dep(e.depositAfter), dep(e.convertedAfter), part(e.parts.deposit),
          part(e.parts.converted)), e.transactionId)

      case e: AdminAdjusted =>
        // The reason on the fact is the adjustment's category, never the operator's text (FR-033, contracts/facts.md).
        one(f.AdminAdjusted(playerId, currency, e.transactionId, SignedMinorUnits(e.amount), e.target, e.category, e.actorId,
          real(e.realAfter), units(e.restrictedAfter), e.at, dep(e.depositAfter), dep(e.convertedAfter), part(e.parts.deposit),
          part(e.parts.converted)), e.transactionId) ++
          forfeited(e.transactionId, e.forfeited.toVector, "adjustment", 0L, Parts.zero, e.recordUpdates, e.realAfter,
            e.restrictedAfter, e.depositAfter, e.convertedAfter, e.at)

      case e: ProviderCredited =>
        one(f.ProviderCredited(playerId, currency, e.transactionId, e.providerRef, units(e.amount), e.landedOn,
          e.recordId.map(grantOf(e.recordUpdates, _)).filter(_.nonEmpty), real(e.realAfter), units(e.restrictedAfter), e.at,
          dep(e.depositAfter), dep(e.convertedAfter), part(e.parts.deposit), part(e.parts.converted)), e.transactionId)
      case _: WalletOpenedFromMigration | _: PendingCapClosed | _: PrimarySwitched | _: HoldConfirmed | _: TurnoverOverrideSet |
          _: CommandRefused =>
        Vector.empty

  /** `payment.captured` from the wallet's own deposit (research R8). */
  def paymentCaptured(walletId: String, event: WalletEvent): Option[Published[f.PaymentsPaymentsFact]] = event match
    case e: DepositConfirmed =>
      val currency = Currency.unsafe(walletId.drop(walletId.indexOf('/') + 1))
      Some(Published(f.PaymentCaptured(e.key, walletId.takeWhile(_ != '/'), e.paymentProvider, units(e.amount), currency, e.at),
        s"${e.key}/captured"))
    case _ => None

  /** The other seven `payments.payments` families, from the outcomes `merchant` reported (FR-022a). */
  def payments(event: PaymentEvent): Option[Published[f.PaymentsPaymentsFact]] = event match
    case PaymentEvent.PaymentOutcomeRecorded(paymentId, o) =>
      def cur = o.currency.flatMap(c => Currency.from(c).toOption)
      val fact: Option[f.PaymentsPaymentsFact] = o.outcome match
        case "initiated" =>
          for c <- cur yield f.PaymentInitiated(paymentId, o.playerId, o.paymentProvider, o.terminal.getOrElse(""), units(o.amount.getOrElse(0L)), c, o.at)
        case "failed"    => Some(f.PaymentFailed(paymentId, o.playerId, o.paymentProvider, o.code.getOrElse(""), o.reason.getOrElse(""), o.at))
        case "expired"   => Some(f.PaymentExpired(paymentId, o.playerId, o.paymentProvider, o.at))
        case "abandoned" => Some(f.PaymentAbandoned(paymentId, o.playerId, o.paymentProvider, o.at))
        case "refunded" =>
          for c <- cur yield f.PaymentRefunded(paymentId, o.playerId, o.paymentProvider, units(o.amount.getOrElse(0L)), c, o.at)
        case "dispute_opened" =>
          for c <- cur yield f.PaymentDisputeOpened(paymentId, o.playerId, o.paymentProvider, units(o.amount.getOrElse(0L)), c, o.reason.getOrElse(""), o.at)
        case "dispute_closed" => Some(f.PaymentDisputeClosed(paymentId, o.playerId, o.paymentProvider, o.disputeOutcome.getOrElse(""), o.at))
        case _                => None
      fact.map(Published(_, s"$paymentId/${o.outcome}"))

  /** The outcomes that carry a currency must name one; checked at the route so the fact can always be written. */
  def needsCurrency(outcome: String): Boolean = Set("initiated", "refunded", "dispute_opened")(outcome)

  /** One `platform.audit` record per money event and refusal (FR-031; spec 018 FR-001). No personal field. */
  def audit(brand: String, walletId: String, event: WalletEvent): Option[Published[f.PlatformAuditFact]] =
    final case class A(key: String, action: String, outcome: String, actorKind: String, actorId: String, txId: String,
        reason: Option[String], at: java.time.Instant)
    val a: Option[A] = event match
      case e: CommandRefused =>
        Some(A(e.key, e.commandType, e.code, "service", "", "", Some(e.code), e.at))
      case e: TurnoverOverrideSet =>
        Some(A(s"turnover-override/${e.at.toEpochMilli}", "set-turnover-override", "accepted", "operator", e.actorId, "", None, e.at))
      case e: AdminAdjusted =>
        Some(A(e.key, "adjust", "accepted", "operator", e.actorId, e.transactionId, Some(e.category), e.at))
      case e: ProviderCredited => Some(A(e.key, "provider-credit", "accepted", "service", e.actorId, e.transactionId, None, e.at))
      case e: WalletOpenedFromMigration => Some(A(e.key, "open-migrated", "accepted", "service", "", "", None, e.at))
      case e: DepositConfirmed       => Some(A(e.key, "deposit", "accepted", "service", "", e.transactionId, None, e.at))
      case e: BetPlaced              => Some(A(e.key, "place-bet", "accepted", "service", "", e.bet.transactionId, None, e.at))
      case e: WinCredited            => Some(A(e.key, "credit-win", "accepted", "service", "", e.transactionId, None, e.at))
      case e: BetRolledBack          => Some(A(e.key, "rollback-bet", "accepted", "service", "", e.transactionId, None, e.at))
      case e: RoundSettledAsLoss     => Some(A(e.key, "settle-loss", "accepted", "service", "", e.transactionId, None, e.at))
      case e: RestrictedFundsAwarded => Some(A(e.key, "award", "accepted", "service", "", e.transactionId, None, e.at))
      case e: RestrictedFundsForfeited => Some(A(e.key, "forfeit", "accepted", "service", "", e.transactionId, None, e.at))
      case e: RestrictedFundsCancelled => Some(A(e.key, "cancel", "accepted", "service", "", e.transactionId, None, e.at))
      case e: PendingCapClosed       => Some(A(e.key, "close-pending-cap", "accepted", "service", "", e.transactionId, None, e.at))
      case e: PrimarySwitched        => Some(A(e.key, "switch", "accepted", "service", "", "", None, e.at))
      case e: HoldPlaced             => Some(A(e.key, "hold", "accepted", "service", "", e.transactionId, None, e.at))
      case e: HoldReleased           => Some(A(e.key, "release", "accepted", "service", "", e.transactionId, Some(e.releasedAs), e.at))
      case e: HoldConfirmed          => Some(A(e.key, "confirm", "accepted", "service", "", "", None, e.at))
      case e: WithdrawalReversed     => Some(A(e.key, "reverse", "accepted", "service", "", e.transactionId, None, e.at))
    a.map { x =>
      val recordId = s"wallet/$walletId/${x.key}"
      Published(
        f.AuditRecord(recordId = recordId, brand = brand, service = "wallet", action = x.action, actorKind = x.actorKind,
          actorId = x.actorId, approverId = None, subjectKind = "player", subjectId = walletId.takeWhile(_ != '/'),
          outcome = x.outcome, reasonCode = x.reason, changes = Vector.empty, correlationId = x.key, causationId = x.txId,
          recordClass = "money", occurredAt = x.at),
        recordId
      )
    }

  /** The kinds of outcome `merchant` may report, re-exported for the route. */
  val outcomeKinds: Set[String] = PaymentOutcome.Kinds
