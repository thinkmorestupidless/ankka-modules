package io.eitheror.casino.programme.brands

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.{Instant, LocalDate, ZoneId}
import scala.util.Try

/** Fee percentages as basis points (12.5% is 1250), applied per GGR fact without a floor (FR-014). */
final case class Fees(admin: Int = 0, payment: Int = 0, provider: Int = 0)

/** How many bonus costs and chargebacks raise a flag for a player (FR-032; the original's defaults 3 and 2). */
final case class Thresholds(bonusCost: Int = 3, chargeback: Int = 2)

/** The public page applicants read (FR-007), and the original's two programme fields. */
final case class ProgrammePage(
    terms: Option[String] = None,
    termsVersion: Option[String] = None,
    contact: Option[String] = None,
    description: Option[String] = None,
    payoutCycleDays: Option[Int] = None,
    minimumPayoutAmount: Option[Long] = None
)

/** How a brand's emails are sent (FR-035): Customer.io's region and the transactional message per kind. */
final case class Sender(region: Option[String] = None, messages: Map[String, String] = Map.empty)

/**
 * The brand's GGR source (FR-014): `daily` (`ggr-daily` and `ggr-adjustment`) or `rounds` (`ggr-round`).
 * A change takes effect from a named reporting day; before it the previous source holds.
 */
final case class GgrSource(current: String = GgrSource.Daily, from: Option[String] = None, previous: String = GgrSource.Daily):
  def on(day: LocalDate): String = if from.forall(f => !day.isBefore(LocalDate.parse(f))) then current else previous

object GgrSource:
  val Daily  = "daily"
  val Rounds = "rounds"
  val all    = Set(Daily, Rounds)

/** A brand the programme serves (data-model.md *BrandProgramme*). `origin` is empty until it is seeded or added. */
final case class BrandState(
    slug: String = "",
    origin: String = "",
    name: Option[String] = None,
    fees: Fees = Fees(),
    thresholds: Thresholds = Thresholds(),
    payoutCycleDay: Option[Int] = None,
    reportingZone: String = "UTC",
    ggrSource: GgrSource = GgrSource(),
    defaultPlan: Option[String] = None,
    page: ProgrammePage = ProgrammePage(),
    sender: Sender = Sender(),
    retired: Option[Instant] = None,
    duplicates: Long = 0,
    mismatches: Long = 0,
    addedBy: Option[String] = None,
    addedAt: Option[Instant] = None
):
  def exists: Boolean   = origin.nonEmpty
  def zone: ZoneId      = ZoneId.of(reportingZone)
  def live: Boolean     = exists && retired.isEmpty
  /** A brand outside this installation delivers by webhook: one elsewhere or hosted (contracts/inbound.md). */
  def byWebhook: Boolean = origin == BrandProgramme.Elsewhere || origin == BrandProgramme.Hosted

final case class SeedBrand(slug: String, at: Instant)
final case class AddBrand(slug: String, origin: String, name: Option[String], reportingZone: String, by: String, at: Instant)

/** A manager's change to the settings and the programme page; what is absent is left as it is. */
final case class UpdateBrand(
    fees: Option[Fees] = None,
    thresholds: Option[Thresholds] = None,
    payoutCycleDay: Option[Int] = None,
    reportingZone: Option[String] = None,
    defaultPlan: Option[String] = None,
    page: Option[ProgrammePage] = None,
    sender: Option[Sender] = None,
    name: Option[String] = None
)

final case class SetGgrSource(source: String, fromDay: String)

object BrandCodecs:
  given Serializer[BrandState]   = ProgrammeCodecs.serializer[BrandState]("brand")
  given Serializer[SeedBrand]    = ProgrammeCodecs.serializer[SeedBrand]("seed-brand")
  given Serializer[AddBrand]     = ProgrammeCodecs.serializer[AddBrand]("add-brand")
  given Serializer[UpdateBrand]  = ProgrammeCodecs.serializer[UpdateBrand]("update-brand")
  given Serializer[SetGgrSource] = ProgrammeCodecs.serializer[SetGgrSource]("set-ggr-source")
  given Serializer[Instant]      = ProgrammeCodecs.serializer[Instant]("instant")

import BrandCodecs.given

final class BrandProgramme(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[BrandState]:
  def emptyState: BrandState = BrandState()

  /** A brand of `PROGRAMME_BRANDS`, made once at start; a repeat, or a brand already known, is nothing. */
  def seed(cmd: SeedBrand): Effect[BrandState] =
    if currentState.exists then effects.reply(currentState)
    else effects.updateState(BrandState(cmd.slug, BrandProgramme.Here, addedAt = Some(cmd.at))).thenReplyState

  /** A brand elsewhere or hosted, added by an operator of the installation (FR-040). */
  def add(cmd: AddBrand): Effect[BrandState] =
    if currentState.exists then effects.error("brand_exists", ErrorCode.Conflict)
    else if !Set(BrandProgramme.Elsewhere, BrandProgramme.Hosted).contains(cmd.origin) then
      effects.error(s"an added brand is elsewhere or hosted, not ${cmd.origin}", ErrorCode.BadRequest)
    else if Try(ZoneId.of(cmd.reportingZone)).isFailure then effects.error(s"reportingZone: '${cmd.reportingZone}' is not a time zone", ErrorCode.BadRequest)
    else
      effects.updateState(BrandState(cmd.slug, cmd.origin, cmd.name, reportingZone = cmd.reportingZone,
        addedBy = Some(cmd.by), addedAt = Some(cmd.at))).thenReplyState

  def update(cmd: UpdateBrand): Effect[BrandState] =
    BrandProgramme.problem(currentState, cmd) match
      case Some(p) => effects.error(p, ErrorCode.BadRequest)
      case None =>
        val s = currentState
        effects.updateState(s.copy(
          fees = cmd.fees.getOrElse(s.fees),
          thresholds = cmd.thresholds.getOrElse(s.thresholds),
          payoutCycleDay = cmd.payoutCycleDay.orElse(s.payoutCycleDay),
          reportingZone = cmd.reportingZone.getOrElse(s.reportingZone),
          defaultPlan = cmd.defaultPlan.orElse(s.defaultPlan),
          page = cmd.page.getOrElse(s.page),
          sender = cmd.sender.getOrElse(s.sender),
          name = cmd.name.orElse(s.name)
        )).thenReplyState

  /** The GGR source from a reporting day on; the source before that day stays what it was. */
  def setGgrSource(cmd: SetGgrSource): Effect[BrandState] =
    if !currentState.exists then effects.error("brand_unknown", ErrorCode.NotFound)
    else if !GgrSource.all(cmd.source) then effects.error(s"ggrSource is daily or rounds, not ${cmd.source}", ErrorCode.BadRequest)
    else if Try(LocalDate.parse(cmd.fromDay)).isFailure then effects.error(s"'${cmd.fromDay}' is not a day", ErrorCode.BadRequest)
    else
      val s      = currentState.ggrSource
      val before = s.on(LocalDate.parse(cmd.fromDay).minusDays(1))
      effects.updateState(currentState.copy(ggrSource = GgrSource(cmd.source, Some(cmd.fromDay), before))).thenReplyState

  def retire(at: Instant): Effect[BrandState] =
    if !currentState.exists then effects.error("brand_unknown", ErrorCode.NotFound)
    else if currentState.retired.isDefined then effects.reply(currentState)
    else effects.updateState(currentState.copy(retired = Some(at))).thenReplyState

  def countDuplicate: Effect[BrandState] = effects.updateState(currentState.copy(duplicates = currentState.duplicates + 1)).thenReplyState

  def countMismatch: Effect[BrandState] = effects.updateState(currentState.copy(mismatches = currentState.mismatches + 1)).thenReplyState

  def get: ReadOnlyEffect[BrandState] = effects.reply(currentState)

object BrandProgramme extends KeyValueEntity.Companion[BrandProgramme, BrandState](
      componentId = ComponentId("brand-programme"),
      stateSerializer = BrandCodecs.given_Serializer_BrandState
    ):
  val Here      = "here"
  val Elsewhere = "elsewhere"
  val Hosted    = "hosted"

  private def percent(bp: Int) = bp >= 0 && bp <= 10_000

  /** Why a settings change is refused: a fee outside 0 to 100%, a threshold below one, a day outside 1 to 28, a zone that is none. */
  def problem(s: BrandState, u: UpdateBrand): Option[String] =
    if !s.exists then Some("brand_unknown")
    else
      u.fees.collect {
        case f if !percent(f.admin)    => "adminFeePercent must be a number between 0 and 100"
        case f if !percent(f.payment)  => "paymentFeePercent must be a number between 0 and 100"
        case f if !percent(f.provider) => "providerFeePercent must be a number between 0 and 100"
      }.orElse(u.thresholds.collect { case t if t.bonusCost < 1 || t.chargeback < 1 => "a fraud threshold is at least 1" })
        .orElse(u.payoutCycleDay.collect { case d if d < 1 || d > 28 => "payoutCycleDay is 1 to 28" })
        .orElse(u.reportingZone.collect { case z if Try(ZoneId.of(z)).isFailure => s"reportingZone: '$z' is not a time zone" })

  def create(context: KeyValueEntityContext) = new BrandProgramme(context)

  val seed           = command("seed")(_.seed)
  val add            = command("add")(_.add)
  val update         = command("update")(_.update)
  val setGgrSource   = command("set-ggr-source")(_.setGgrSource)
  val retire         = command("retire")(_.retire)
  val countDuplicate = command("count-duplicate")(_.countDuplicate)
  val countMismatch  = command("count-mismatch")(_.countMismatch)
  val get            = query("get")(_.get)
