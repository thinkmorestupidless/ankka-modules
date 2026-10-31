package io.eitheror.casino.payouts.terminals

import java.time.Duration

/**
 * A withdrawal terminal as the gateway lists it (research R5; 005 contracts/payouts.md): what eligibility,
 * the request's checks and the payout read. `gateway` is the provider's name as `merchant` stores it.
 */
final case class Terminal(
    id: String,
    subBrandId: String,
    name: String,
    scheme: String,
    gateway: String,
    active: Boolean,
    supportsWithdrawal: Boolean,
    requiresPayoutDestination: Boolean,
    coins: Vector[String] = Vector.empty,
    hasStatus: Boolean = false,
    reviewWindow: Option[Duration] = None
):
  /** Cozeni and Morphosis pay out in crypto, to an address. */
  def crypto: Boolean = Gateways.crypto(gateway)

object Gateways:
  val UnityFinance = "unityfinance"
  val Cozeni       = "cozeni"
  val Morphosis    = "morphosis"
  val Gigadat      = "gigadat"

  /** The four gateways with an automated payout (R7). */
  val automated: Set[String] = Set(UnityFinance, Cozeni, Morphosis, Gigadat)

  def of(gateway: String): String         = gateway.trim.toLowerCase
  def crypto(gateway: String): Boolean    = Set(Cozeni, Morphosis).contains(of(gateway))
  def isGigadat(gateway: String): Boolean = of(gateway) == Gigadat

/**
 * The original's rule (`WithdrawalTerminalEligibility.scala:79-134`), unchanged: a terminal is offered when
 * it is active, it supports withdrawal, and, if its scheme is a VISA one, the player has deposited by VISA.
 * Offered terminals rank by a gateway the player deposited with first, then name, then id, so the order is
 * the same on every read (SC-007).
 */
object Eligibility:

  /**
   * Matched as a substring, as the original: scheme names are not written consistently, and a false
   * positive withholds an option where a false negative breaches the licence condition.
   */
  def isVisa(scheme: String): Boolean = scheme.toUpperCase.contains("VISA")

  /**
   * `deposits` is the gateway of each confirmed deposit with the schemes seen on it; a deposit recorded
   * without a scheme adds none, so it never reads as VISA.
   */
  def eligible(terminals: Vector[Terminal], deposits: Map[String, Set[String]]): Vector[Terminal] =
    val visaDeposit = deposits.values.exists(_.exists(isVisa))
    val gateways    = deposits.keySet.map(Gateways.of)
    terminals
      .filter(t => t.active && t.supportsWithdrawal && (!isVisa(t.scheme) || visaDeposit))
      .sortBy(t => (if gateways.contains(Gateways.of(t.gateway)) then 0 else 1, t.name, t.id))

  /** The named terminal when the player may withdraw to it; a blank id is never one. */
  def resolve(terminalId: String, terminals: Vector[Terminal], deposits: Map[String, Set[String]]): Option[Terminal] =
    if terminalId.isBlank then None else eligible(terminals, deposits).find(_.id == terminalId)
