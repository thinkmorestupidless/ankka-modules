package io.eitheror.casino.simulator.core

import com.fasterxml.jackson.databind.JsonNode

/**
 * The seam of FR-006: everything the core needs from a provider's protocol, and nothing more. The
 * core keeps sessions, rounds, scheduled callbacks and their answers; an adapter says what a
 * callback's route is, what its body holds, how an answer reads and which answers are retried.
 * A second real provider is a second implementation of this trait (FR-015); `SeamSuite` holds the
 * core to naming no route, field or status word of its own.
 */
trait Protocol:

  /** The answer words retried when a scenario does not say (FR-005). */
  def defaultRetryOn: Set[String]

  /** The path a callback of this kind is posted to, under the brand's base address. */
  def route(kind: String): String

  /** The bytes of a callback's body, in the scenario's shape, exactly as they will be signed. */
  def body(shape: String, call: Call): Array[Byte]

  /** What an answer says, or why it says nothing the protocol knows. */
  def read(httpStatus: Int, body: Array[Byte]): Reading

  /** What a callback does to its round, so the core can keep round state without naming kinds. */
  def roleOf(kind: String, providerKind: Option[String], amount: Option[String]): RoundRole

  /** The provider kind a send of this kind carries when the scenario does not name one. */
  def defaultProviderKind(kind: String): Option[String]

  /** True when the answer is the protocol's acceptance (the brand did what was asked). */
  def accepted(reading: Reading): Boolean

  /** Fields of an answer that identify a person, replaced before the answer is recorded (FR-023). */
  def redact(kind: String, body: Array[Byte]): Array[Byte]

  /** The callback that settles an open round as a loss (FR-013: the orphaned bet, a stopped profile). */
  def loss(token: Option[String], round: String, currency: String, gameCode: String, transactionId: String): Call

  /** The callback that settles a round as a win of `amount`. */
  def win(token: Option[String], round: String, currency: String, gameCode: String, transactionId: String, amount: String): Call

  /** The callback that opens a round with a stake of `amount`. */
  def stake(token: Option[String], round: String, currency: String, gameCode: String, transactionId: String, amount: String): Call

  /** The callback that presents a launch ticket back to the brand. */
  def check(launchToken: String): Call

  /** The callback kind a balance read is, used to learn a balance before a judged send. */
  def balanceKind: String

/** Everything a callback can carry; an adapter writes the subset its protocol and shape name. */
final case class Call(
    kind: String,
    token: Option[String],
    transactionId: Option[String],
    round: Option[String],
    currency: Option[String],
    amount: Option[String],
    providerKind: Option[String],
    gameCode: Option[String],
    cancelId: Option[String],
    player: Option[String],
    site: Option[String],
    extra: Map[String, JsonNode] = Map.empty,
    malformed: Boolean = false
)

/** An answer as the protocol reads it. `malformedBody`: the brand could not parse what was sent. */
final case class Reading(
    status: Option[String],
    balance: Option[String],
    currency: Option[String],
    token: Option[String],
    malformedBody: Boolean,
    retryable: Boolean
)

enum RoundRole:
  case Stake, Win, Loss, Cancel, Outside
