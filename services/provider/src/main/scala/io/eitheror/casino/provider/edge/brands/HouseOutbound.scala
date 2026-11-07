package io.eitheror.casino.provider.edge.brands

import io.eitheror.casino.provider.core.{Attempt, Outbound}

import scala.concurrent.duration.FiniteDuration

/**
 * The outbound path under the house provider's name: a brand serves ST8 at `/st8/<callback>` and
 * the house provider, its second provider configuration, at `/eitheror/<callback>` (spec 003
 * FR-002, `contracts/brand-amendments.md`). The protocol's routes are ST8's; only the prefix the
 * brand reaches them under changes, so a callback's record keeps the protocol's route.
 */
final class HouseOutbound(sender: Outbound, prefix: String = HouseOutbound.Prefix) extends Outbound:
  def send(brand: String, route: String, body: Array[Byte], key: String, answerWait: FiniteDuration, attempt: Int): Attempt =
    sender.send(brand, HouseOutbound.under(route, prefix), body, key, answerWait, attempt)

object HouseOutbound:
  /** The provider name a brand's `casino-providers` gives the house provider. */
  val Prefix = "/eitheror"

  def under(route: String, prefix: String = Prefix): String =
    if route.startsWith("/st8/") then prefix + route.stripPrefix("/st8") else route
