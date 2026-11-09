package io.eitheror.casino.engagement.features

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.engagement.config.{ConfigChange, Configuration, CrmConfig}
import io.eitheror.casino.engagement.consumers.Sends
import io.eitheror.casino.engagement.it.{FakeCrm, Kit, Reply, SharedKit}
import io.eitheror.casino.engagement.profile.{MoneyFact, PlayersFact, Profile}
import io.eitheror.casino.engagement.send.{Outcome, Send, SendState}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * A feature file of features/engagement bound against the whole service (constitution VII): facts published on the
 * kit's broker, the routes called as their callers, Customer.io, `players` and `rewards` as the kit's fakes. Each
 * scenario has a player of its own, since the database outlives scenarios; "a player" is one registered with an
 * address and email consent, made on the profile directly so the background makes no call of its own.
 */
abstract class EngagementFeatures(file: String) extends GherkinSuite(s"../../features/engagement/$file") with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit

  protected var player      = ""
  protected var last: Reply = null

  protected val eur = Currency.unsafe("EUR")

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(EngagementFeatures.Triggers)

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.crm.reset()
    kit.crm.expect(FakeCrm.SiteId, FakeCrm.TrackKey, FakeCrm.AppKey)
    player = newPlayer()
    last = null

  override def afterEach(context: AfterEach): Unit =
    if !kit.components.forKeyValueEntity(EntityId(kit.slug)).call(Configuration.get).invoke().configured then
      kit.configure(EngagementFeatures.Triggers): Unit
    super.afterEach(context)

  protected def newPlayer(): String = s"p-${UUID.randomUUID().toString.take(8)}"

  protected def players(fact: f.CasinoPlayersFact): Unit =
    kit.components.forKeyValueEntity(EntityId(fact.playerId)).call(Profile.players).invoke(PlayersFact(fact)): Unit

  protected def money(fact: f.MoneyTransactionsFact): Unit =
    kit.components.forKeyValueEntity(EntityId(fact.playerId)).call(Profile.money).invoke(MoneyFact(fact)): Unit

  /** A registered player with an address and email consent, an hour ago, on the profile itself. */
  protected def register(id: String = player, consented: Boolean = true): Unit =
    val at = Instant.now().minusSeconds(3600)
    players(f.PlayerRegistered(id, at, None, None, None, Some("ZA"), Some("EUR"), Some("bronze"), at,
      email = Some(Personal(s"$id@example.com")), givenName = Some(Personal("Ada"))))
    if consented then players(f.PlayerConsentChanged(id, "email", true, "registration", at))

  protected def deposit(id: String = player, at: Instant = Instant.now(), cents: Long = 2500L): f.DepositConfirmed =
    val tx = UUID.randomUUID().toString
    f.DepositConfirmed(id, eur, tx, MinorUnits.unsafe(cents), s"ref-$tx", 1, SignedMinorUnits(cents), MinorUnits.unsafe(0L), at)

  protected def publishMoney(fact: f.MoneyTransactionsFact): Unit = kit.publish(f.MoneyTransactions, fact.key, fact)
  protected def publishPlayers(fact: f.CasinoPlayersFact): Unit  = kit.publish(f.CasinoPlayers, fact.key, fact)

  protected def sendKey(event: String, fact: f.MoneyTransactionsMessage): String =
    s"$event/${Sends.factId(f.MoneyTransactions, fact)}"

  protected def sendState(key: String): SendState = kit.components.forWorkflow(EntityId(key)).call(Send.outcome).invoke()

  /** The send once it has ended or parked. */
  protected def settled(key: String, within: FiniteDuration = 30.seconds): SendState =
    eventually(s"send $key settled", within)(Option(sendState(key)).filter(s => s.request.isDefined && (s.ended || s.outcome == Outcome.Parked)))

  protected def unconfigure(): Unit =
    kit.components.forKeyValueEntity(EntityId(kit.slug)).call(Configuration.put).invoke(ConfigChange(CrmConfig(), "features")): Unit

  protected def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = kit.eventually(what, within)(f)

object EngagementFeatures:
  /** A transactional id for every trigger the features send. */
  val Triggers: Map[String, String] = Map("DepositConfirmed" -> "7", "FirstDepositConfirmed" -> "8", "PaymentFailed" -> "9",
    "SelfExclusionActivated" -> "10", "EmailAddressVerification" -> "11", "WithdrawalFailed" -> "12")

  /** Every scenario of `file` named, pending on `task`. */
  def pending(task: String, names: String*): Map[String, String] = names.map(_ -> s"$task (pending)").toMap
