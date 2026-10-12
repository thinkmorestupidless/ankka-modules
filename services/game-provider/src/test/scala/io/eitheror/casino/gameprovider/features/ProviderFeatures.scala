package io.eitheror.casino.gameprovider.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.it.{Kit, Reply}
import io.eitheror.casino.gameprovider.money.Amounts

import java.util.UUID
import scala.concurrent.duration.*

/** One kit for every suite in the test JVM: one Postgres, one service, the fakes. */
object SharedKit:
  @volatile private var started = false

  lazy val kit: Kit =
    val k = Kit()
    sys.addShutdownHook(k.stop())
    started = true
    k

  /** A suite that ran a kit of its own hands the process-wide wiring back to the shared one. */
  def restore(): Unit = if started then kit.rewire()

/**
 * A feature file of features/game-provider bound against the whole service (constitution VII): the
 * provider's calls go through the signed routes, the player's and operator's through theirs, and every
 * id a scenario names is made its own, since the database outlives scenarios.
 */
abstract class ProviderFeatures(file: String) extends GherkinSuite(s"../../features/game-provider/$file") with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit

  protected var prefix = ""
  protected var player = ""
  protected var token  = ""
  protected var currency = "EUR"
  protected var last: Reply = null
  protected var before: Long = 0

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()
    assertEquals(kit.configureSt8().status, 204)
    prefix = UUID.randomUUID().toString.take(8)
    player = id("player")
    token = ""
    currency = "EUR"
    last = null
    before = 0

  /** A scenario's own id for a name it uses. */
  protected def id(name: String): String = s"$prefix-$name"

  protected def minor(amount: String): Long = Amounts.parse(amount, Currency.unsafe(currency)).toOption.get.value

  protected def render(minor: Long): String = Amounts.render(minor, Currency.unsafe(currency))

  protected def balance: Long = kit.wallet.balance(player, currency) match { case (r, x) => r + x }

  protected def json(fields: (String, Any)*): JsonNode = Json.obj(fields*)

  // ── The provider's calls ────────────────────────────────────────────────────────────────────────

  protected def debit(tx: String, amount: String, round: String, kind: Option[String] = None, game: Option[String] = None,
      ticket: String = token): Reply =
    last = kit.callback("debit", json("token" -> ticket, "transaction_id" -> id(tx), "round" -> id(round), "amount" -> amount,
      "currency" -> currency, "provider_kind" -> kind, "game_code" -> game))
    last

  protected def credit(tx: String, amount: String, round: Option[String], kind: Option[String] = None): Reply =
    last = kit.callback("credit", json("token" -> token, "transaction_id" -> id(tx), "round" -> round.map(id), "amount" -> amount,
      "currency" -> currency, "provider_kind" -> kind))
    last

  protected def cancel(cancelId: String, ref: String, withTicket: Boolean = true): Reply =
    last = kit.callback("cancel", json("token" -> Option.when(withTicket)(token), "player" -> player, "cancel_id" -> id(cancelId),
      "transaction_id" -> id(ref), "currency" -> currency))
    last

  protected def answered(word: String): Unit = assertEquals(last.word, word, last.text)

  protected def answeredWithBalance(word: String, amount: String): Unit =
    answered(word)
    assertEquals(last.json.text("balance"), Some(amount), last.text)

  /** "Given a player with a session ticket for a wallet in …", shared by the money features. */
  protected def sessionTicketSteps(): Unit =
    Given("a player with a session ticket for a wallet in {string}") { (cur: String) =>
      currency = cur
      token = kit.sessionTicket(player, currency = cur)
      kit.wallet.set(player, cur, 10000)
    }

  /** Waits for the round_settled the round publishes. */
  protected def settled(round: String): io.eitheror.casino.contract.facts.RoundSettled =
    kit.eventually(s"round_settled for ${id(round)}")(kit.roundsPublished.reverse.find(_.providerRoundId == id(round)))
