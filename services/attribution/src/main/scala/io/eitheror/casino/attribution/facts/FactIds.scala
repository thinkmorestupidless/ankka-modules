package io.eitheror.casino.attribution.facts

/**
 * Every fact's id (research R13): deterministic, the same on every emission, and carrying no player id,
 * only the click id, the pseudonym and the input's own reference (FR-012, FR-022).
 */
object FactIds:
  def click(clickId: String): String                    = s"click/$clickId"
  def registration(pseudonym: String): String           = s"registration/$pseudonym"
  def firstDeposit(pseudonym: String): String           = s"first-deposit/$pseudonym"
  def deposit(pseudonym: String, paymentId: String): String = s"deposit/$pseudonym/$paymentId"
  def money(kind: String, pseudonym: String, reference: String): String = s"$kind/$pseudonym/$reference"
  def ggrDaily(pseudonym: String, currency: String, day: String): String = s"ggr-daily/$pseudonym/$currency/$day"
  def ggrAdjustment(pseudonym: String, currency: String, day: String, dedupeId: String): String =
    s"ggr-adjustment/$pseudonym/$currency/$day/$dedupeId"
  def ggrRound(pseudonym: String, roundId: String, dedupeId: String): String = s"ggr-round/$pseudonym/$roundId/$dedupeId"
  def playerClosed(pseudonym: String): String = s"player-closed/$pseudonym"
