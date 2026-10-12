package io.eitheror.casino.simulator.core

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** One scenario as the library lists it. */
final case class ScenarioRow(name: String, version: Int, shipped: Boolean)

/** The scenario library, listable (FR-019): a row per stored scenario. */
final class ScenarioLibraryView extends View[Option[StoredScenario], ScenarioRow]:
  def onChange(change: Option[StoredScenario]): Effect =
    change.fold(effects.deleteRow())(s => effects.updateRow(ScenarioRow(s.name, s.version, s.shipped)))

object ScenarioLibrary
    extends View.Companion[ScenarioLibraryView, Option[StoredScenario], ScenarioRow](
      componentId = ComponentId("scenario-library"),
      source = ChangeSource.stateOf(ScenarioEntity),
      rowSerializer = Codecs.serializer[ScenarioRow]("scenario-row")
    ):
  def create(ctx: ViewComponentContext) = new ScenarioLibraryView
