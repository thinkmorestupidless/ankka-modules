package io.eitheror.casino.engagement.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewQueries}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.engagement.EngagementCodecs
import io.eitheror.casino.engagement.profile.{Profile, ProfileState}

import java.time.LocalDate

/** One player's row (data-model.md `ProfilesView`): what the deposit-day reset reads, and no personal value. */
final case class ProfileRow(playerId: String, dailyDepositCount: Int = 0, depositDay: String = "", status: String = "",
    erased: Boolean = false)

object ProfileRow:
  given Serializer[ProfileRow] = EngagementCodecs.row[ProfileRow]("profile-row")

  def of(p: ProfileState): ProfileRow =
    ProfileRow(p.playerId, p.dailyDepositCount, p.depositDay.fold("")(_.toString), p.status, p.erased)

final class ProfilesViewUpdater extends View[ProfileState, ProfileRow]:
  def onChange(p: ProfileState): Effect =
    val row = ProfileRow.of(p.copy(playerId = updateContext.subject))
    if rowState.contains(row) then effects.ignore() else effects.updateRow(row)

object ProfilesView extends View.Companion[ProfilesViewUpdater, ProfileState, ProfileRow](
      componentId = ComponentId("profiles-view"),
      source = ChangeSource.stateOf(Profile),
      rowSerializer = ProfileRow.given_Serializer_ProfileRow
    ):
  def create(ctx: ViewComponentContext) = new ProfilesViewUpdater

  val PageSize = 500

  /**
   * FR-009's players: a count above zero stamped with a day before `day`, by player id after `after`, 500 at a time,
   * so the reset pages through every one without an offset. ISO dates order as text.
   */
  def dailyCountAbove(view: ViewQueries[ProfileRow], day: LocalDate, after: String = ""): Vector[ProfileRow] =
    val d = day.toString
    view.ordered(
      SqlFragment.raw("(payload::jsonb->>'dailyDepositCount')::int > 0 AND payload::jsonb->>'depositDay' <> '' AND ") ++
        jsonText("depositDay") ++ sql" < $d AND " ++ jsonText("playerId") ++ sql" > $after",
      SqlFragment.raw("payload::jsonb->>'playerId' ASC"), PageSize)
