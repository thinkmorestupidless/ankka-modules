package io.eitheror.casino.exchange.terms

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*

/**
 * A book's currency and outcomes as the open route was given them (research R10): the exchange's
 * domain knows neither currency nor brand, so the ledger port and the bet gate read the currency here.
 */
final case class Terms(currency: String = "", outcomes: Vector[String] = Vector.empty):
  def known: Boolean = currency.nonEmpty

object Terms:
  given Serializer[Terms] = Codecs.serializer[Terms]("exchange-market-terms")

final class MarketTermsEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[Terms]:
  def emptyState: Terms = Terms()

  def set(terms: Terms): Effect[Terms] = effects.updateState(terms).thenReplyState

  def get: ReadOnlyEffect[Terms] = effects.reply(currentState)

object MarketTerms extends KeyValueEntity.Companion[MarketTermsEntity, Terms](ComponentId("exchange-market-terms"), summon[Serializer[Terms]]):
  def create(context: KeyValueEntityContext) = new MarketTermsEntity(context)

  val set = command("set")(_.set)
  val get = query("get")(_.get)
