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

  /** The first terms stand: a repeated open is refused by the book, and its terms must not replace the first. */
  def set(terms: Terms): Effect[Terms] =
    if currentState.known then effects.reply(currentState) else effects.updateState(terms).thenReplyState

  def get: ReadOnlyEffect[Terms] = effects.reply(currentState)

/**
 * Terms once known, held in the instance: they are written once and never change, so the gate, the
 * ledger port and the place route read a book's terms from its entity only until they are known.
 */
object KnownTerms:
  private val known = java.util.concurrent.ConcurrentHashMap[String, Terms]()

  def of(market: String)(read: => Terms): Terms =
    Option(known.get(market)).getOrElse {
      val t = read
      if t.known then known.put(market, t): Unit
      t
    }

object MarketTerms extends KeyValueEntity.Companion[MarketTermsEntity, Terms](ComponentId("exchange-market-terms"), summon[Serializer[Terms]]):
  def create(context: KeyValueEntityContext) = new MarketTermsEntity(context)

  val set = command("set")(_.set)
  val get = query("get")(_.get)
