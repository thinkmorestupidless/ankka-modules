package io.eitheror.casino.contract.facts

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.named
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant

/**
 * `affiliates.attribution`, produced by `attribution` (spec 012 FR-011, FR-012). Keyed by the
 * player's per-network pseudonym, by the link for a click, and carrying no player id (spec 001
 * FR-012). Each kind carries what spec 012 FR-012 lists; spec 012 owns any field added to a kind.
 */
sealed trait AttributionFact extends Fact:
  def protocolVersion: Int
  def factId: String
  def occurredAt: Instant
  def brand: String
  def network: String
  def affiliate: String
  def link: String
  def campaign: Option[String]
  def subIds: Vector[String]
  def clickId: Option[String]

/** Every kind but `click`: about one player, named by the pseudonym. */
sealed trait PlayerAttributionFact extends AttributionFact:
  def pseudonym: String
  def key: String = pseudonym

@named("click")
final case class Click(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String, network: String,
    affiliate: String, link: String, campaign: Option[String], subIds: Vector[String], clickId: Option[String],
    country: Option[String], deviceType: Option[String]) extends AttributionFact:
  def key: String = link

@named("registration")
final case class Registration(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String, network: String,
    affiliate: String, link: String, campaign: Option[String], subIds: Vector[String], clickId: Option[String],
    pseudonym: String, country: Option[String]) extends PlayerAttributionFact

@named("first-deposit")
final case class FirstDeposit(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String, network: String,
    affiliate: String, link: String, campaign: Option[String], subIds: Vector[String], clickId: Option[String],
    pseudonym: String, currency: Currency, amount: MinorUnits, reference: String) extends PlayerAttributionFact

@named("deposit")
final case class Deposit(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String, network: String,
    affiliate: String, link: String, campaign: Option[String], subIds: Vector[String], clickId: Option[String],
    pseudonym: String, currency: Currency, amount: MinorUnits, reference: String) extends PlayerAttributionFact

@named("ggr-daily")
final case class GgrDaily(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String, network: String,
    affiliate: String, link: String, campaign: Option[String], subIds: Vector[String], clickId: Option[String],
    pseudonym: String, currency: Currency, reportingDay: String, stake: MinorUnits, win: MinorUnits, ggr: SignedMinorUnits,
    reference: String) extends PlayerAttributionFact

@named("ggr-adjustment")
final case class GgrAdjustment(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String, network: String,
    affiliate: String, link: String, campaign: Option[String], subIds: Vector[String], clickId: Option[String],
    pseudonym: String, currency: Currency, reportingDay: String, ggr: SignedMinorUnits, reference: String)
    extends PlayerAttributionFact

@named("ggr-round")
final case class GgrRound(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String, network: String,
    affiliate: String, link: String, campaign: Option[String], subIds: Vector[String], clickId: Option[String],
    pseudonym: String, currency: Currency, stake: MinorUnits, win: MinorUnits, ggr: SignedMinorUnits, reference: String)
    extends PlayerAttributionFact

@named("bonus-cost")
final case class BonusCost(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String, network: String,
    affiliate: String, link: String, campaign: Option[String], subIds: Vector[String], clickId: Option[String],
    pseudonym: String, currency: Currency, amount: SignedMinorUnits, reference: String) extends PlayerAttributionFact

@named("chargeback")
final case class Chargeback(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String, network: String,
    affiliate: String, link: String, campaign: Option[String], subIds: Vector[String], clickId: Option[String],
    pseudonym: String, currency: Currency, amount: MinorUnits, reference: String) extends PlayerAttributionFact

@named("withdrawal-requested")
final case class AttributedWithdrawalRequested(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String,
    network: String, affiliate: String, link: String, campaign: Option[String], subIds: Vector[String],
    clickId: Option[String], pseudonym: String, currency: Currency, amount: MinorUnits, reference: String)
    extends PlayerAttributionFact

@named("withdrawal-completed")
final case class AttributedWithdrawalCompleted(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String,
    network: String, affiliate: String, link: String, campaign: Option[String], subIds: Vector[String],
    clickId: Option[String], pseudonym: String, currency: Currency, amount: MinorUnits, reference: String)
    extends PlayerAttributionFact

@named("withdrawal-failed")
final case class AttributedWithdrawalFailed(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String,
    network: String, affiliate: String, link: String, campaign: Option[String], subIds: Vector[String],
    clickId: Option[String], pseudonym: String, currency: Currency, amount: MinorUnits, reference: String)
    extends PlayerAttributionFact

@named("withdrawal-reversed")
final case class AttributedWithdrawalReversed(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String,
    network: String, affiliate: String, link: String, campaign: Option[String], subIds: Vector[String],
    clickId: Option[String], pseudonym: String, currency: Currency, amount: MinorUnits, reference: String)
    extends PlayerAttributionFact

@named("player-closed")
final case class PlayerClosedAttributed(protocolVersion: Int, factId: String, occurredAt: Instant, brand: String,
    network: String, affiliate: String, link: String, campaign: Option[String], subIds: Vector[String],
    clickId: Option[String], pseudonym: String, category: String) extends PlayerAttributionFact

object AttributionFact:
  given JsonValueCodec[AttributionFact] = FactCodecs.make

object AffiliatesAttribution extends Topic[AttributionFact]("affiliates.attribution", Vector("attribution")):
  val families = Vector(
    FamilyDef.of[Click]("click"),
    FamilyDef.of[Registration]("registration"),
    FamilyDef.of[FirstDeposit]("first-deposit"),
    FamilyDef.of[Deposit]("deposit"),
    FamilyDef.of[GgrDaily]("ggr-daily"),
    FamilyDef.of[GgrAdjustment]("ggr-adjustment"),
    FamilyDef.of[GgrRound]("ggr-round"),
    FamilyDef.of[BonusCost]("bonus-cost"),
    FamilyDef.of[Chargeback]("chargeback"),
    FamilyDef.of[AttributedWithdrawalRequested]("withdrawal-requested"),
    FamilyDef.of[AttributedWithdrawalCompleted]("withdrawal-completed"),
    FamilyDef.of[AttributedWithdrawalFailed]("withdrawal-failed"),
    FamilyDef.of[AttributedWithdrawalReversed]("withdrawal-reversed"),
    FamilyDef.of[PlayerClosedAttributed]("player-closed")
  )
