package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.engagement.consumers.Sends
import io.eitheror.casino.engagement.profile.{MoneyFact, PlayersFact, Profile}
import io.eitheror.casino.engagement.send.{Outcome, Send, SendState}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** The suites' players, facts and sends, over the kit. */
final class Facts(kit: Kit):
  val eur = Currency.unsafe("EUR")

  /**
   * A registered player with an address and email consent, an hour ago, on the profile itself (no call of its own),
   * who has deposited once, so a deposit published is not their first and makes one send.
   */
  def player(prefix: String = "f", consented: Boolean = true): String =
    val id = s"$prefix-${UUID.randomUUID().toString.take(8)}"
    val at = Instant.now().minusSeconds(3600)
    players(f.PlayerRegistered(id, at, None, None, None, Some("ZA"), Some("EUR"), Some("bronze"), at,
      email = Some(Personal(s"$id@example.com")), givenName = Some(Personal("Ada"))))
    if consented then players(f.PlayerConsentChanged(id, "email", true, "registration", at))
    kit.components.forKeyValueEntity(EntityId(id)).call(Profile.money).invoke(MoneyFact(deposit(id, at.plusSeconds(60)))): Unit
    id

  def players(fact: f.CasinoPlayersFact): Unit =
    kit.components.forKeyValueEntity(EntityId(fact.playerId)).call(Profile.players).invoke(PlayersFact(fact)): Unit

  def deposit(playerId: String, at: Instant = Instant.now(), cents: Long = 2500L): f.DepositConfirmed =
    val tx = UUID.randomUUID().toString
    f.DepositConfirmed(playerId, eur, tx, MinorUnits.unsafe(cents), s"ref-$tx", 2, SignedMinorUnits(cents), MinorUnits.unsafe(0L), at)

  def publish(fact: f.MoneyTransactionsFact): Unit = kit.publish(f.MoneyTransactions, fact.key, fact)

  def key(event: String, fact: f.MoneyTransactionsMessage): String = s"$event/${Sends.factId(f.MoneyTransactions, fact)}"

  def state(key: String): SendState = kit.components.forWorkflow(EntityId(key)).call(Send.outcome).invoke()

  def settled(key: String, within: FiniteDuration = 30.seconds): SendState =
    kit.eventually(s"send $key settled", within)(Option(state(key)).filter(s => s.request.isDefined && (s.ended || s.outcome == Outcome.Parked)))

  /** The CRM calls that name the player. */
  def calls(playerId: String): Vector[CrmCall] = kit.crm.calls.filter(_.raw.contains(playerId))
