package io.eitheror.casino.support.clients

import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.support.{Json, Settings}

import Neighbour.segment

/*
 * The calls the tools make (contracts/neighbours.md), one trait per neighbour. The player id is always the
 * conversation's, passed in by the tool, never one the model produced (FR-011); a write's key is the approval id.
 */

trait Players:
  def status(playerId: String): Called
  def agentProfile(playerId: String, fields: Set[String]): Called
  def sessions(playerId: String): Called
  def endSession(gameSessionId: String, approvalId: String): Called

trait Wallet:
  def balance(playerId: String, currency: String): Called
  def transactions(playerId: String, currency: String, limit: Int): Called
  def holdPreview(playerId: String, currency: String, amount: Long): Called

trait Payouts:
  def requestWithdrawal(playerId: String, amount: Long, currency: String, key: String, confirmForfeit: Boolean): Called

trait Rewards:
  def grants(playerId: String): Called

trait Rg:
  def limits(playerId: String, currency: String): Called
  def setLimit(playerId: String, limitType: String, value: Long, currency: String, key: String): Called
  def coolOff(playerId: String, until: String, key: String): Called
  def exclude(playerId: String, key: String): Called

/** The five, as the tools are given them. */
final case class Neighbours(players: Players, wallet: Wallet, payouts: Payouts, rewards: Rewards, rg: Rg)

object Neighbours:
  def http(services: ServiceClients, s: Settings): Neighbours =
    val brand = segment(s.brand)
    Neighbours(
      HttpPlayers(Neighbour(services, s.players), brand),
      HttpWallet(Neighbour(services, s.wallet), brand),
      HttpPayouts(Neighbour(services, s.payouts), brand),
      HttpRewards(Neighbour(services, s.rewards), brand),
      HttpRg(Neighbour(services, s.rg), brand)
    )

final class HttpPlayers(n: Neighbour, brand: String) extends Players:
  private def path(rest: String) = s"/v1/casino/brands/$brand/players$rest"
  def status(p: String): Called = n.get(path(s"/${segment(p)}/status"))
  def agentProfile(p: String, fields: Set[String]): Called =
    n.get(path(s"/${segment(p)}/agent-profile?fields=${fields.toVector.sorted.map(segment).mkString(",")}"))
  def sessions(p: String): Called = n.get(path(s"/${segment(p)}/game-sessions"))
  def endSession(s: String, approvalId: String): Called =
    n.post(path(s"/game-sessions/${segment(s)}/end"), Json.obj("approvalId" -> approvalId))

final class HttpWallet(n: Neighbour, brand: String) extends Wallet:
  private def path(p: String, c: String, rest: String) = s"/v1/casino/brands/$brand/wallets/${segment(p)}/${segment(c)}$rest"
  def balance(p: String, c: String): Called = n.get(path(p, c, "/balance"))
  def transactions(p: String, c: String, limit: Int): Called = n.get(path(p, c, s"/transactions?page=0&pageSize=$limit"))
  def holdPreview(p: String, c: String, amount: Long): Called = n.get(path(p, c, s"/holds/preview?amount=$amount"))

final class HttpPayouts(n: Neighbour, brand: String) extends Payouts:
  def requestWithdrawal(p: String, amount: Long, c: String, key: String, confirmForfeit: Boolean): Called =
    n.post(s"/v1/casino/payouts-service/$brand/players/${segment(p)}/withdrawal-requests",
      Json.obj("amount" -> amount, "currency" -> c, "key" -> key, "confirmForfeit" -> confirmForfeit))

final class HttpRewards(n: Neighbour, brand: String) extends Rewards:
  def grants(p: String): Called = n.get(s"/v1/casino/brands/$brand/rewards/${segment(p)}/grants")

final class HttpRg(n: Neighbour, brand: String) extends Rg:
  private def path(p: String, rest: String) = s"/v1/casino/rg-service/$brand/${segment(p)}$rest"
  def limits(p: String, c: String): Called = n.get(path(p, s"/limits?currency=${segment(c)}"))
  def setLimit(p: String, t: String, value: Long, c: String, key: String): Called =
    n.put(path(p, s"/limits/${segment(t)}"), Json.obj("value" -> value.toString, "currency" -> c, "key" -> key))
  def coolOff(p: String, until: String, key: String): Called = n.put(path(p, "/cooling-off"), Json.obj("until" -> until, "key" -> key))
  def exclude(p: String, key: String): Called = n.put(path(p, "/permanent-exclusion"), Json.obj("key" -> key))
