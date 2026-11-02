package io.eitheror.casino.programme.settlement

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.{ProgrammeCodecs, Wiring}
import io.eitheror.casino.programme.accounts.Affiliate
import io.eitheror.casino.programme.ledger.{CloseAccount, Closing, CommissionAccount, UnsettledRow}

import java.time.Instant
import scala.concurrent.duration.*

/** A payout a close wrote or brought up to date, as the original's close answers it, in minor units. */
final case class ClosedPayout(affiliateId: String, payoutId: String, brandId: String, currency: String, totalPayable: Long)

/** The calls a close makes for one account, so its kit suite can route them to entity kits. */
trait CloseCalls:
  def payout(id: EntityId): PayoutState
  def threshold(affiliate: String, currency: String): Long
  def close(account: EntityId, c: CloseAccount): Closing
  def open(id: EntityId, c: OpenPayout): Unit

object CloseCalls:
  def of(client: ComponentClient): CloseCalls = new CloseCalls:
    def payout(id: EntityId): PayoutState = client.forEventSourcedEntity(id).call(Payout.get).invoke()
    def threshold(affiliate: String, currency: String): Long =
      client.forEventSourcedEntity(EntityId(affiliate)).call(Affiliate.get).invoke().minimumIn(currency)
    def close(account: EntityId, c: CloseAccount): Closing = client.forEventSourcedEntity(account).call(CommissionAccount.close).invoke(c)
    def open(id: EntityId, c: OpenPayout): Unit =
      client.forEventSourcedEntity(id).call(Payout.open).invoke(c): Unit

/**
 * One account's part of a close (research R8, FR-018): its payout for the period claims every unclaimed
 * payable fact up to the period's end, and is opened or brought up to date with what it claims. A paid
 * or rejected payout is frozen: its late facts wait for the next close. Below the account's threshold nothing is
 * claimed and no payout is written.
 */
final class Closer(calls: CloseCalls, now: () => Instant):
  def closeOne(row: UnsettledRow, period: String, periodEnd: Instant): Option[ClosedPayout] =
    val id = Payout.entityId(row.affiliate, row.brand, row.currency, period)
    if !calls.payout(id).status.forall(s => s == Payout.Pending || s == Payout.Approved) then None
    else
      val payoutId = Payout.idOf(row.affiliate, row.brand, row.currency, period)
      val closing  = calls.close(CommissionAccount.entityId(row.affiliate, row.brand, row.currency),
        CloseAccount(payoutId, periodEnd, calls.threshold(row.affiliate, row.currency)))
      Option.when(closing.claimed) {
        calls.open(id, OpenPayout(row.affiliate, row.brand, row.currency, period, closing.figures, now()))
        ClosedPayout(row.affiliate, Payouts.wireId(row.affiliate, row.currency, period), row.brand, row.currency, closing.figures.total)
      }

final case class CloseState(
    brand: String = "",
    period: String = "",
    run: Int = 0,
    periodEnd: Option[Instant] = None,
    cursor: String = "",
    payouts: Vector[ClosedPayout] = Vector.empty,
    by: String = "",
    startedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None
):
  def started: Boolean = brand.nonEmpty

/** `carried`: what the period's previous run wrote, which a close that finds nothing new still answers (FR-020). */
final case class StartClose(brand: String, period: String, run: Int, periodEnd: Instant, by: String, at: Instant,
    carried: Vector[ClosedPayout] = Vector.empty)

/**
 * A close of a brand's period (research R8), id `<brand>/<period>/<run>`: it pages through the accounts
 * the `Unsettled` view says are owed, 50 a step, so a step stays within its time whatever the brand's
 * size, and ends its run in `CloseRun`.
 */
final class Close(context: WorkflowContext) extends Workflow[CloseState]:
  def emptyState: CloseState = CloseState()

  override def settings: WorkflowSettings = WorkflowSettings.builder.defaultStepTimeout(30.seconds).build

  /** A start of one already started rejoins it. */
  def start(c: StartClose): Effect[CloseState] =
    if currentState.started then effects.reply(currentState)
    else
      effects.updateState(CloseState(c.brand, c.period, c.run, Some(c.periodEnd), payouts = c.carried, by = c.by, startedAt = Some(c.at)))
        .transitionTo(Close.page.ref).thenReplyState

  def pageStep: StepEffect =
    val s      = currentState
    val rows   = Wiring.owed.get()(s.brand, s.period, s.cursor)
    val closer = Closer(CloseCalls.of(context.componentClient), () => Wiring.clock.get().instant())
    val closed = rows.flatMap(r => closer.closeOne(r, s.period, s.periodEnd.get))
    val swept  = closed.map(_.payoutId).toSet
    val next   = s.copy(cursor = rows.lastOption.fold(s.cursor)(_.account), payouts = s.payouts.filterNot(p => swept(p.payoutId)) ++ closed)
    if rows.size < Close.PageSize then stepEffects.updateState(next).thenTransitionTo(Close.finish.ref)
    else stepEffects.updateState(next).thenTransitionTo(Close.page.ref)

  /** The payouts carried from the previous run as they stand now: a paid or rejected one is no longer the close's. */
  def finishStep: StepEffect =
    val calls = CloseCalls.of(context.componentClient)
    val s = currentState.copy(payouts = currentState.payouts.flatMap { p =>
      val now = calls.payout(Payout.entityId(p.affiliateId, p.brandId, p.currency, currentState.period))
      Option.when(now.status.exists(st => st == Payout.Pending || st == Payout.Approved))(p.copy(totalPayable = now.total))
    })
    context.componentClient.forKeyValueEntity(CloseRun.entityId(s.brand, s.period)).call(CloseRun.ended).invoke(s.run): Unit
    stepEffects.updateState(s.copy(endedAt = Some(Wiring.clock.get().instant()))).thenEnd

  def get: ReadOnlyEffect[CloseState] = effects.reply(currentState)

object Close extends Workflow.Companion[Close, CloseState](
      componentId = ComponentId("close"),
      stateSerializer = ProgrammeCodecs.serializer[CloseState]("close")
    ):
  val PageSize = 50

  given Serializer[StartClose] = ProgrammeCodecs.serializer[StartClose]("start-close")

  def entityId(brand: String, period: String, run: Int): EntityId = EntityId(s"$brand/$period/$run")

  def create(context: WorkflowContext) = new Close(context)

  val page   = step("page")(_.pageStep)
  val finish = step("finish")(_.finishStep)

  val start = command("start")(_.start)
  val get   = query("get")(_.get)

/** Which run of a brand's period is the current close, and whether it is still going. */
final case class CloseRunState(run: Int = 0, running: Boolean = false)

/**
 * The runs of a brand's period's closes, id `<brand>/<period>`: a close asked for while one runs joins
 * it, so two at once give one result (FR-020); one asked for after the last ended is a new run, which
 * sweeps the facts that arrived late into the period's payouts that are not paid.
 */
final class CloseRun(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[CloseRunState]:
  def emptyState: CloseRunState = CloseRunState()

  def begin: Effect[CloseRunState] =
    if currentState.running then effects.reply(currentState)
    else effects.updateState(CloseRunState(currentState.run + 1, running = true)).thenReplyState

  def ended(run: Int): Effect[CloseRunState] =
    if currentState.run == run && currentState.running then effects.updateState(currentState.copy(running = false)).thenReplyState
    else effects.reply(currentState)

  def get: ReadOnlyEffect[CloseRunState] = effects.reply(currentState)

object CloseRun extends KeyValueEntity.Companion[CloseRun, CloseRunState](
      componentId = ComponentId("close-run"),
      stateSerializer = ProgrammeCodecs.serializer[CloseRunState]("close-run")
    ):
  given Serializer[Int] = ProgrammeCodecs.serializer[Int]("run")

  def entityId(brand: String, period: String): EntityId = EntityId(s"$brand/$period")

  def create(context: KeyValueEntityContext) = new CloseRun(context)

  val begin = command("begin")(_.begin)
  val ended = command("ended")(_.ended)
  val get   = query("get")(_.get)
