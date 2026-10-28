package io.eitheror.casino.attribution.clicks

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.{Named, Wiring}
import io.eitheror.casino.attribution.facts.{Facts, Publish}
import io.eitheror.casino.contract.facts.AttributionFact

/** A `click` fact for a landing whose tag was valid, keyed by the link; nothing for a refused click (FR-006). */
final class ClickFacts(brand: String) extends Consumer[ClickState, AttributionFact]:
  def onMessage(state: ClickState): Effect =
    state.click match
      case Some(c) if c.refused.isEmpty =>
        (c.network, c.affiliate, c.link) match
          case (Some(n), Some(a), Some(l)) =>
            val fact = Facts.click(brand, c.clickId, Named(n, a, l, c.campaign, c.subIds), c.at, c.country, Some(c.deviceType))
            effects.produceAll(Seq(effects.message(fact: AttributionFact).withKey(fact.key).withMetadata(Publish.of(fact))))
          case _ => effects.ignore()
      case _ => effects.ignore()

object ClickFacts extends Consumer.Companion[ClickFacts, ClickState, AttributionFact](
      componentId = ComponentId("click-facts"),
      source = ChangeSource.stateOf(Click)
    ):
  def create(ctx: ConsumerContext) = new ClickFacts(Wiring.brand.get())
  override val outputSerializer: Option[Serializer[AttributionFact]] = Publish.outputSerializer
  override val produceTo: Option[String]                             = Publish.produceTo
  override val produces: Option[Publication]                         = Publish.produces
