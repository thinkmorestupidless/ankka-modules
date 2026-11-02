package io.eitheror.casino.programme.links

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.players.{ClickCodecs, ClickRecord, ClickState, PlayerFact, PlayerFacts}

/** A row per click (data-model.md *LinkStats*): a link's clicks are a count of these, never a counter (FR-024). */
final class ClicksView extends View[ClickState, ClickState]:
  def onChange(s: ClickState): Effect = effects.updateRow(s)

object Clicks extends View.Companion[ClicksView, ClickState, ClickState](
      componentId = ComponentId("clicks"),
      source = ChangeSource.stateOf(ClickRecord),
      rowSerializer = ClickCodecs.given_Serializer_ClickState
    ):
  def create(ctx: ViewComponentContext) = new ClicksView

/**
 * A link's clicks and registrations, counted from the facts received: a row is written once per fact,
 * so a redelivered change counts nothing twice, as a counter kept in a row would.
 */
object LinkStats:
  final case class Counts(clicks: Long, registrations: Long)

  def of(views: ViewClient, link: String): Counts =
    Counts(
      views.forView(Clicks).count(jsonText("link") ++ sql" = $link"),
      views.forView(PlayerFacts).count(jsonText("link") ++ sql" = $link AND " ++ jsonText("kind") ++ sql" = ${PlayerFact.Registration}")
    )
