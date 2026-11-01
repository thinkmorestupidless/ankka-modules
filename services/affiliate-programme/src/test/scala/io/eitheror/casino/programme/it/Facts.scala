package io.eitheror.casino.programme.it

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant
import java.util.UUID

/**
 * Builders for the fourteen kinds (spec 012 FR-012), as a brand's `attribution` would publish them:
 * one affiliate's link at one brand, a player by its pseudonym, every id fresh unless named.
 */
final case class Facts(brand: String, affiliate: String, link: String, network: String = "programme",
    campaign: Option[String] = None, subIds: Vector[String] = Vector.empty, clickId: Option[String] = None):
  private def id(kind: String) = s"$kind-${UUID.randomUUID().toString.take(12)}"
  private def eur(c: String)   = Currency.unsafe(c)
  private def ref              = UUID.randomUUID().toString.take(12)

  def click(at: Instant, factId: String = id("click"), country: Option[String] = Some("ZA")): Click =
    Click(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, country, Some("desktop"))

  def registration(pseudonym: String, at: Instant, factId: String = id("reg"), country: Option[String] = Some("ZA")): Registration =
    Registration(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, country)

  def firstDeposit(pseudonym: String, amount: Long, at: Instant, currency: String = "EUR", factId: String = id("fd")): FirstDeposit =
    FirstDeposit(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, eur(currency), MinorUnits.unsafe(amount), ref)

  def deposit(pseudonym: String, amount: Long, at: Instant, currency: String = "EUR", factId: String = id("dep")): Deposit =
    Deposit(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, eur(currency), MinorUnits.unsafe(amount), ref)

  def ggrDaily(pseudonym: String, day: String, stake: Long, win: Long, at: Instant, currency: String = "EUR",
      factId: String = id("ggr"), beforeBinding: Option[Boolean] = None): GgrDaily =
    GgrDaily(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, eur(currency), day,
      MinorUnits.unsafe(stake), MinorUnits.unsafe(win), SignedMinorUnits(stake - win), ref, beforeBinding)

  def ggrAdjustment(pseudonym: String, day: String, ggr: Long, at: Instant, currency: String = "EUR", factId: String = id("adj")): GgrAdjustment =
    GgrAdjustment(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, eur(currency), day, SignedMinorUnits(ggr), ref)

  def ggrRound(pseudonym: String, stake: Long, win: Long, at: Instant, currency: String = "EUR", factId: String = id("round")): GgrRound =
    GgrRound(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, eur(currency),
      MinorUnits.unsafe(stake), MinorUnits.unsafe(win), SignedMinorUnits(stake - win), ref)

  def bonusCost(pseudonym: String, amount: Long, at: Instant, currency: String = "EUR", factId: String = id("bonus")): BonusCost =
    BonusCost(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, eur(currency), SignedMinorUnits(amount), ref)

  def chargeback(pseudonym: String, amount: Long, at: Instant, currency: String = "EUR", factId: String = id("cb")): Chargeback =
    Chargeback(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, eur(currency), MinorUnits.unsafe(amount), ref)

  def withdrawalRequested(pseudonym: String, amount: Long, at: Instant, currency: String = "EUR", factId: String = id("wr")) =
    AttributedWithdrawalRequested(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, eur(currency), MinorUnits.unsafe(amount), ref)

  def withdrawalCompleted(pseudonym: String, amount: Long, at: Instant, currency: String = "EUR", factId: String = id("wc")) =
    AttributedWithdrawalCompleted(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, eur(currency), MinorUnits.unsafe(amount), ref)

  def withdrawalFailed(pseudonym: String, amount: Long, at: Instant, currency: String = "EUR", factId: String = id("wf")) =
    AttributedWithdrawalFailed(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, eur(currency), MinorUnits.unsafe(amount), ref)

  def withdrawalReversed(pseudonym: String, amount: Long, at: Instant, currency: String = "EUR", factId: String = id("wv")) =
    AttributedWithdrawalReversed(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, eur(currency), MinorUnits.unsafe(amount), ref)

  def playerClosed(pseudonym: String, at: Instant, category: String = "self-exclusion", factId: String = id("closed")) =
    PlayerClosedAttributed(1, factId, at, brand, network, affiliate, link, campaign, subIds, clickId, pseudonym, category)

object Facts:
  /** The kind as the topic's family names it, and the webhook's `X-Attribution-Kind`. */
  def kindOf(fact: AttributionFact): String = fact match
    case _: Click                         => "click"
    case _: Registration                  => "registration"
    case _: FirstDeposit                  => "first-deposit"
    case _: Deposit                       => "deposit"
    case _: GgrDaily                      => "ggr-daily"
    case _: GgrAdjustment                 => "ggr-adjustment"
    case _: GgrRound                      => "ggr-round"
    case _: BonusCost                     => "bonus-cost"
    case _: Chargeback                    => "chargeback"
    case _: AttributedWithdrawalRequested => "withdrawal-requested"
    case _: AttributedWithdrawalCompleted => "withdrawal-completed"
    case _: AttributedWithdrawalFailed    => "withdrawal-failed"
    case _: AttributedWithdrawalReversed  => "withdrawal-reversed"
    case _: PlayerClosedAttributed        => "player-closed"
