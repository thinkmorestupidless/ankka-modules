package io.eitheror.casino.programme.brands

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*

/** One row per brand the programme serves, for the operator of the installation's list (FR-040). */
final class BrandsView extends View[BrandState, BrandState]:
  def onChange(s: BrandState): Effect = if s.exists then effects.updateRow(s) else effects.ignore()

object Brands extends View.Companion[BrandsView, BrandState, BrandState](
      componentId = ComponentId("brands"),
      source = ChangeSource.stateOf(BrandProgramme),
      rowSerializer = BrandCodecs.given_Serializer_BrandState
    ):
  /** Every brand, by slug (`all` is a name the runtime keeps for itself). */
  val every = query("every")(s"SELECT payload FROM $table ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new BrandsView
