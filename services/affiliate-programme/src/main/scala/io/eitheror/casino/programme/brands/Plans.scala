package io.eitheror.casino.programme.brands

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*

/** One row per plan: a brand's plans, for its managers and for the portal's available plans (M8). */
final class PlansView extends View[PlanState, PlanState]:
  def onChange(s: PlanState): Effect = effects.updateRow(s)

object Plans extends View.Companion[PlansView, PlanState, PlanState](
      componentId = ComponentId("plans"),
      source = ChangeSource.stateOf(Plan),
      rowSerializer = PlanCodecs.given_Serializer_PlanState
    ):
  val ofBrand = query("of-brand")(s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new PlansView
