package io.eitheror.casino.markets.terms

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*

/**
 * A market's limits as its draft or last change set them (spec 023 FR-014, data-model.md): either may
 * be absent, and the book then opens with the brand's. The market's definition has no place for them,
 * so they are kept here, written by the operator's route before the library sees the draft.
 */
final case class Terms(maxOrderCost: Option[Long] = None, maxPosition: Option[Long] = None):
  /** The limits a book opens with: these, else the brand's. */
  def orDefaults(cost: Long, position: Long): (Long, Long) = (maxOrderCost.getOrElse(cost), maxPosition.getOrElse(position))

object Terms:
  given Serializer[Terms] = Codecs.serializer[Terms]("market-terms")

final class MarketTermsEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[Terms]:
  def emptyState: Terms = Terms()

  def set(terms: Terms): Effect[Terms] = effects.updateState(terms).thenReplyState

  def get: ReadOnlyEffect[Terms] = effects.reply(currentState)

object MarketTerms extends KeyValueEntity.Companion[MarketTermsEntity, Terms](ComponentId("market-terms"), summon[Serializer[Terms]]):
  def create(context: KeyValueEntityContext) = new MarketTermsEntity(context)

  val set = command("set")(_.set)
  val get = query("get")(_.get)
