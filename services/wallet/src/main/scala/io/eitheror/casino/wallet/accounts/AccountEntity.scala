package io.eitheror.casino.wallet.accounts

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.{Answer, Key}
import io.eitheror.casino.wallet.{Ids, WalletCodecs}
import io.eitheror.casino.wallet.accounts.AccountEvent.*
import io.eitheror.casino.wallet.entity.{MoneyCommand, Outcome, Refusals}
import io.eitheror.casino.wallet.funding.Codes

/**
 * A non-player account (spec 022 FR-006, FR-007, FR-009): id `<kind>/<name>/<currency>`. The only
 * place the account's floor and owner are checked with the change they guard, so a pool account
 * never goes below zero and only its owner moves it.
 *
 * The money commands consult the key store as the wallet's do: a repeat answers the first outcome
 * and persists nothing; another command under a key is `KEY_REUSED`; a refusal of a new key is
 * persisted as `CommandRefused`, so a flow's re-run step is refused the same way.
 */
final class AccountEntity(context: EventSourcedEntityContext) extends EventSourcedEntity[AccountState, AccountEvent]:
  import AccountEntity.*

  private val accountId = context.entityId

  def emptyState: AccountState = AccountState(accountId)

  def applyEvent(event: AccountEvent): AccountState =
    val s = currentState
    event match
      case e: AccountClaimed  => s.copy(created = true, owner = e.owner)
      case e: AccountDebited  => s.copy(balance = e.balanceAfter).remember(e.key, e.digest, e.outcome)
      case e: AccountCredited => s.copy(balance = e.balanceAfter).remember(e.key, e.digest, e.outcome)
      case e: AccountAdjusted => s.copy(balance = e.balanceAfter).remember(e.key, e.digest, e.outcome)
      case e: CreditLineSet   => s.copy(creditLine = e.creditLine)
      case e: CommandRefused  => s.remember(e.key, e.digest, e.outcome)

  // ── the key store and refusals, as the wallet's ───────────────────────────

  private def keyed(c: AccountMoneyCommand, wireName: String)(decide: String => Effect[Outcome]): Effect[Outcome] =
    val digest = MoneyCommand.digestOf(wireName, c)
    currentState.seen.check(Key.unsafe(c.key), digest) match
      case Some(Answer.Repeated(first)) => effects.reply(first)
      case Some(Answer.Reused(first)) =>
        val outcome = refusal(Refusals.KeyReused, Refusals.keyReused(c.key, first.outcome), Map("key" -> c.key), wireName, c.amount)
        effects.persist(CommandRefused(s"${c.key}#reused#$digest", digest, wireName, Refusals.KeyReused, outcome.message.getOrElse(""),
          outcome.details, c.at, outcome)).thenReply(_ => outcome)
      case Some(Answer.First(first)) => effects.reply(first)
      case None                      => decide(digest)

  private def refusal(code: String, message: String, details: Map[String, String], wireName: String, amount: Long): Outcome =
    Outcome(code = Some(code), message = Some(message), details = details, commandType = wireName, amount = Some(amount),
      accountBalance = Some(currentState.balance))

  private def refuse(c: AccountMoneyCommand, wireName: String, digest: String, code: String, message: String,
      details: Map[String, String] = Map.empty): Effect[Outcome] =
    val outcome = refusal(code, message, details, wireName, c.amount)
    effects.persist(CommandRefused(c.key, digest, wireName, code, message, details, c.at, outcome)).thenReply(_ => outcome)

  private def accepted(wireName: String, c: AccountMoneyCommand, txId: String, balance: Long): Outcome =
    Outcome(commandType = wireName, amount = Some(c.amount), transactionId = Some(txId), accountBalance = Some(balance),
      balanceBefore = Some(currentState.balance), balanceAfter = Some(balance))

  private def transactionId(key: String): String = Ids.transactionId(accountId, key)

  private def notFound: String = Refusals.accountNotFound(accountId)

  // ── the first move's claim ────────────────────────────────────────────────

  /** Not keyed: a claim by the owner is answered and writes nothing; by another, `NOT_OWNER`. */
  def claim(c: Claim): Effect[Outcome] =
    val s = currentState
    if !s.created then
      effects.persist(AccountClaimed(c.owner, c.at)).thenReply(after => Outcome(commandType = "claim", accountBalance = Some(after.balance)))
    else if s.owner != c.owner then
      effects.reply(Outcome(code = Some(Refusals.NotOwner), message = Some(Refusals.notOwner(accountId, s.owner)),
        details = Map("owner" -> s.owner), commandType = "claim"))
    else effects.reply(Outcome(commandType = "claim", accountBalance = Some(s.balance)))

  // ── moves (the move flow's steps) ─────────────────────────────────────────

  def debit(c: Debit): Effect[Outcome] = keyed(c, "debit") { digest =>
    val s = currentState
    if !s.created then refuse(c, "debit", digest, Refusals.AccountNotFound, notFound)
    else if s.owner != c.owner then refuse(c, "debit", digest, Refusals.NotOwner, Refusals.notOwner(accountId, s.owner), Map("owner" -> s.owner))
    else if s.balance - c.amount < s.floor then
      val details = Map("amount" -> c.amount.toString, "balance" -> s.balance.toString, "floor" -> s.floor.toString)
      if s.kind == AccountState.Pool then refuse(c, "debit", digest, Codes.InsufficientFunds, Refusals.poolShort(c.amount, s.balance), details)
      else refuse(c, "debit", digest, Refusals.CreditLineReached, Refusals.creditLineReached(c.amount, s.balance, s.floor), details)
    else
      val txId    = transactionId(c.key)
      val after   = s.balance - c.amount
      val outcome = accepted("debit", c, txId, after)
      effects.persist(AccountDebited(c.key, digest, txId, c.amount, c.purpose, c.counterpartyPlayerId, c.reference, after, c.at, outcome))
        .thenReply(_ => outcome)
  }

  def credit(c: Credit): Effect[Outcome] = keyed(c, "credit") { digest =>
    val s = currentState
    if !s.created then refuse(c, "credit", digest, Refusals.AccountNotFound, notFound)
    else if s.owner != c.owner then refuse(c, "credit", digest, Refusals.NotOwner, Refusals.notOwner(accountId, s.owner), Map("owner" -> s.owner))
    else
      val txId    = transactionId(c.key)
      val after   = Math.addExact(s.balance, c.amount)
      val outcome = accepted("credit", c, txId, after)
      effects.persist(AccountCredited(c.key, digest, txId, c.amount, c.purpose, c.counterpartyPlayerId, c.reference, after, c.at, outcome))
        .thenReply(_ => outcome)
  }

  // ── the operator (FR-009) ─────────────────────────────────────────────────

  def adjust(c: AdjustAccount): Effect[Outcome] = keyed(c, "adjust") { digest =>
    val s = currentState
    if !s.created then refuse(c, "adjust", digest, Refusals.AccountNotFound, notFound)
    else if c.reason.trim.isEmpty then refuse(c, "adjust", digest, Refusals.ReasonRequired, Refusals.reasonRequired)
    else if c.amount == 0 then effects.reply(Outcome(commandType = "adjust", amount = Some(0L), accountBalance = Some(s.balance)))
    else if s.balance + c.amount < s.floor then
      refuse(c, "adjust", digest, Codes.DebitExceedsBalance, Refusals.debitExceeds(-c.amount, "account", s.balance),
        Map("amount" -> c.amount.toString, "balance" -> s.balance.toString))
    else
      val txId    = transactionId(c.key)
      val after   = Math.addExact(s.balance, c.amount)
      val outcome = accepted("adjust", c, txId, after)
      effects.persist(AccountAdjusted(c.key, digest, txId, c.amount, c.reason, c.category, c.actorId, after, s.creditLine, c.at, outcome))
        .thenReply(_ => outcome)
  }

  // ── the clearing owner's credit line (FR-007) ─────────────────────────────

  def setCreditLine(c: SetCreditLine): Effect[Outcome] =
    val s = currentState
    def refused(code: String, message: String, details: Map[String, String] = Map.empty) =
      effects.reply(Outcome(code = Some(code), message = Some(message), details = details, commandType = "set-credit-line",
        amount = Some(c.creditLine), accountBalance = Some(s.balance)))
    def answer(after: AccountState) = Outcome(commandType = "set-credit-line", amount = Some(c.creditLine), accountBalance = Some(after.balance))
    if s.created && s.owner != c.owner then refused(Refusals.NotOwner, Refusals.notOwner(accountId, s.owner), Map("owner" -> s.owner))
    else if s.kind != AccountState.Clearing then refused(Refusals.NotClearing, Refusals.notClearing(accountId))
    else if c.creditLine < 0 then refused("INVALID_REQUEST", "a credit line is a whole, non-negative number of minor units")
    else if s.balance < -c.creditLine then
      refused(Refusals.CreditLineBelowBalance, Refusals.creditLineBelowBalance(c.creditLine, -s.balance),
        Map("creditLine" -> c.creditLine.toString, "balance" -> s.balance.toString))
    else if s.created && s.creditLine == c.creditLine then effects.reply(answer(s))
    else if !s.created then effects.persist(AccountClaimed(c.owner, c.at), CreditLineSet(c.creditLine, c.owner, c.at)).thenReply(answer)
    else effects.persist(CreditLineSet(c.creditLine, c.owner, c.at)).thenReply(answer)

  // ── reads ─────────────────────────────────────────────────────────────────

  def balance: ReadOnlyEffect[AccountBalance] =
    val s = currentState
    effects.reply(AccountBalance(accountId, s.created, s.balance, s.owner, s.creditLine, s.floor, s.currency))

  def outcomeOf(q: AccountOutcomeOf): ReadOnlyEffect[Option[Outcome]] =
    effects.reply(currentState.seen.outcomeOf(Key.unsafe(q.key)))

object AccountEntity
    extends EventSourcedEntity.Companion[AccountEntity, AccountState, AccountEvent](
      componentId = ComponentId("account"),
      stateSerializer = Serializer.json[AccountState]("wallet.account.state")(using WalletCodecs.stored[AccountState]),
      eventSerializer = Serializer.json[AccountEvent]("wallet.account.event")(using WalletCodecs.stored[AccountEvent])
    ):

  given Serializer[Claim]            = Codecs.serializer[Claim]("wallet.account.claim")
  given Serializer[Debit]            = Codecs.serializer[Debit]("wallet.account.debit")
  given Serializer[Credit]           = Codecs.serializer[Credit]("wallet.account.credit")
  given Serializer[AdjustAccount]    = Codecs.serializer[AdjustAccount]("wallet.account.adjust")
  given Serializer[SetCreditLine]    = Codecs.serializer[SetCreditLine]("wallet.account.set-credit-line")
  given Serializer[AccountOutcomeOf] = Codecs.serializer[AccountOutcomeOf]("wallet.account.outcome-of-key")
  given Serializer[Outcome]          = Codecs.serializer[Outcome]("wallet.outcome")
  given Serializer[Option[Outcome]]  = Codecs.serializer[Option[Outcome]]("wallet.outcome-of")
  given Serializer[AccountBalance]   = Codecs.serializer[AccountBalance]("wallet.account.balance")

  def create(context: EventSourcedEntityContext) = new AccountEntity(context)

  /** The account's id: its kind, its name (the market or the pool) and the currency. */
  def id(kind: String, name: String, currency: String): EntityId = EntityId(s"$kind/$name/$currency")

  val claim         = command("claim")(_.claim)
  val debit         = command("debit")(_.debit)
  val credit        = command("credit")(_.credit)
  val adjust        = command("adjust")(_.adjust)
  val setCreditLine = command("set-credit-line")(_.setCreditLine)
  val balance       = query("balance")(_.balance)
  val outcomeOf     = query("outcome-of")(_.outcomeOf)
