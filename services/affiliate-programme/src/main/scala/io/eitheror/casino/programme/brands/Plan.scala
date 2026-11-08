package io.eitheror.casino.programme.brands

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.commission.Window

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID

/**
 * A brand's commission plan (FR-006): type, the CPA amount in its own currency (change 11), RevShare as
 * basis points, the minimum first deposit for CPA, and whether negative NGR carries over.
 */
final case class PlanState(
    id: String = "",
    brand: String = "",
    name: String = "",
    planType: String = Plan.Cpa,
    cpaAmount: Long = 0,
    cpaCurrency: String = "EUR",
    revShareBp: Int = 0,
    minimumDeposit: Long = 0,
    negativeCarryover: Boolean = false,
    status: String = Plan.Active,
    createdBy: Option[String] = None,
    createdAt: Option[Instant] = None
):
  def exists: Boolean = id.nonEmpty
  def window: Window  = Window.fromCarryover(negativeCarryover)
  def paysCpa: Boolean      = planType != Plan.RevShare && cpaAmount > 0
  def paysRevShare: Boolean = planType != Plan.Cpa && revShareBp > 0

final case class CreatePlan(brand: String, name: String, planType: String, cpaAmount: Long, cpaCurrency: String,
    revShareBp: Int, minimumDeposit: Long, negativeCarryover: Boolean, by: String, at: Instant)

final case class UpdatePlan(cpaAmount: Option[Long] = None, cpaCurrency: Option[String] = None, revShareBp: Option[Int] = None,
    minimumDeposit: Option[Long] = None, negativeCarryover: Option[Boolean] = None, status: Option[String] = None)

object PlanCodecs:
  given Serializer[PlanState]  = ProgrammeCodecs.serializer[PlanState]("plan")
  given Serializer[CreatePlan] = ProgrammeCodecs.serializer[CreatePlan]("create-plan")
  given Serializer[UpdatePlan] = ProgrammeCodecs.serializer[UpdatePlan]("update-plan")

import PlanCodecs.given

final class Plan(context: KeyValueEntityContext) extends KeyValueEntity[PlanState]:
  def emptyState: PlanState = PlanState()

  /** Refused `plan_already_exists` when a plan of the name exists at the brand: the id is the name's. */
  def create(cmd: CreatePlan): Effect[PlanState] =
    if currentState.exists then effects.error("plan_already_exists", ErrorCode.Conflict)
    else
      Plan.problem(cmd.planType, cmd.cpaAmount, cmd.cpaCurrency, cmd.revShareBp, cmd.minimumDeposit) match
        case Some(p) => effects.error(p, ErrorCode.BadRequest)
        case None =>
          val id = context.entityId.toString.split('/').last
          effects.updateState(PlanState(id, cmd.brand, cmd.name.trim, cmd.planType, cmd.cpaAmount, cmd.cpaCurrency, cmd.revShareBp,
            cmd.minimumDeposit, cmd.negativeCarryover, Plan.Active, Some(cmd.by), Some(cmd.at))).thenReplyState

  def update(cmd: UpdatePlan): Effect[PlanState] =
    val s = currentState
    if !s.exists then effects.error("plan_not_found", ErrorCode.NotFound)
    else
      val next = s.copy(
        cpaAmount = cmd.cpaAmount.getOrElse(s.cpaAmount),
        cpaCurrency = cmd.cpaCurrency.getOrElse(s.cpaCurrency),
        revShareBp = cmd.revShareBp.getOrElse(s.revShareBp),
        minimumDeposit = cmd.minimumDeposit.getOrElse(s.minimumDeposit),
        negativeCarryover = cmd.negativeCarryover.getOrElse(s.negativeCarryover),
        status = cmd.status.getOrElse(s.status)
      )
      Plan.problem(next.planType, next.cpaAmount, next.cpaCurrency, next.revShareBp, next.minimumDeposit)
        .orElse(Option.when(!Plan.statuses(next.status))(s"status is active or inactive, not ${next.status}")) match
        case Some(p) => effects.error(p, ErrorCode.BadRequest)
        case None    => effects.updateState(next).thenReplyState

  def get: ReadOnlyEffect[PlanState] = effects.reply(currentState)

object Plan extends KeyValueEntity.Companion[Plan, PlanState](
      componentId = ComponentId("plan"),
      stateSerializer = PlanCodecs.given_Serializer_PlanState
    ):
  val Cpa      = "cpa"
  val RevShare = "revshare"
  val Hybrid   = "hybrid"
  val types    = Set(Cpa, RevShare, Hybrid)
  val Active   = "active"
  val Inactive = "inactive"
  val statuses = Set(Active, Inactive)

  private val CurrencyCode = "[A-Z]{3}".r

  def problem(planType: String, cpa: Long, currency: String, revShareBp: Int, minimumDeposit: Long): Option[String] =
    if !types(planType) then Some(s"planType is cpa, revshare or hybrid, not $planType")
    else if cpa < 0 then Some("cpaAmount is not negative")
    else if !CurrencyCode.matches(currency) then Some(s"cpaCurrency: '$currency' is not a currency code")
    else if revShareBp < 0 || revShareBp > 10_000 then Some("revSharePercent must be a number between 0 and 100")
    else if minimumDeposit < 0 then Some("minimumDepositThreshold is not negative")
    else None

  /** The plan id the original gives a name at a brand: `nameUUIDFromBytes("aff.plan:<brand>:<name>")` (`S/OperatorRoutes.scala:652-730`). */
  def idOf(brand: String, name: String): String = UUID.nameUUIDFromBytes(s"aff.plan:$brand:${name.trim}".getBytes(UTF_8)).toString

  def entityId(brand: String, plan: String): EntityId = EntityId(s"$brand/$plan")

  def create(context: KeyValueEntityContext) = new Plan(context)

  val createPlan = command("create")(_.create)
  val update = command("update")(_.update)
  val get    = query("get")(_.get)
