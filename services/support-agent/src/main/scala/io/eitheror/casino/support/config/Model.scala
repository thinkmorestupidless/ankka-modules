package io.eitheror.casino.support.config

import java.time.Instant

/** A brand's agent configuration (data-model.md `ConfigBody`): one version, as a conversation reads it. */
final case class ConfigBody(
    enabled: Boolean = false,
    prompt: String = ConfigBody.DefaultPrompt,
    handOffMessage: String = "I can't help with that here. Our support team can, on live chat {hours}.",
    supportHours: String = "every day, 08:00–24:00 UK time",
    unavailableMessage: String = "Support chat is busy right now. Please try live chat.",
    closingMessage: String = "Your account is now excluded. This conversation is closed.",
    safeguardingMessage: Option[String] = None,
    helpline: Option[String] = None,
    safeguardingOptIn: Boolean = false,
    personalFields: Set[String] = Set.empty,
    model: String = "claude-sonnet-4-6",
    maxInputChars: Int = 2000,
    turnsPerMinute: Int = 10,
    dailyTokens: Long = 2_000_000L,
    approvalMinutes: Int = 15,
    retentionDays: Int = 90
):
  /** The hand-off message with the hours in it (R12). */
  def handOff: String = handOffMessage.replace("{hours}", supportHours)

  /** Safeguarding is on for a real-money brand, and for another when it opted in (FR-023). */
  def safeguarding(realMoney: Boolean): Boolean = realMoney || safeguardingOptIn

  /** The safeguarding reply: the message and the helpline (FR-023). */
  def safeguardingReply: String = (safeguardingMessage.toVector ++ helpline.toVector).mkString(" ")

object ConfigBody:
  /** The original's prompt (`agents/motoko.py:30-47`) with the two lines contracts/tools.md adds. */
  val DefaultPrompt: String =
    """You are Motoko, a helpful player support agent for this casino.
      |
      |You have access to the player's account information and can help them with:
      |- Checking their balance, bonuses, transactions, and game sessions
      |- Account actions: withdrawals, deposit limits, session management
      |- Responsible gambling controls: cool-off periods and self-exclusion
      |
      |Rules:
      |- Load the player's context on the first message using get_player_state and get_wallet_balance.
      |- Be concise, accurate, and gaming-fluent. No corporate filler language.
      |- Never discuss other players or casino internals.
      |- Always state the action clearly before asking for confirmation. Never execute a write action without confirmation.
      |- For deposit limits, always explain upfront that there is a regulatory delay before the limit takes effect.
      |- For cool-off and self-exclusion: state consequences plainly. No hedging. These are serious controls.
      |- For self-exclusion specifically: state that it is permanent and cannot be reversed.
      |- Currency amounts are stored in minor units (pence/cents). Convert to major units (£/€/$) when displaying to players.
      |- For cool-off: always state the end date and that it cannot be shortened. For self-exclusion: state explicitly that it is permanent and cannot be reversed. One confirmation suffices for both — do not ask again.
      |- When you cannot help, end your reply with ⟦handoff⟧ and nothing after it.
      |- The player's state and balance are given to you as context; you need not call those tools on the first message.""".stripMargin

/**
 * A change of a brand's configuration, for its audit record: the version it made, the operator, the fields
 * changed by name, and the values of those whose values may be recorded (contracts/audit.md).
 */
final case class ConfigNote(version: Int, by: String, fields: Vector[String], values: Map[String, String], at: Instant)

/** `AgentConfiguration`'s state: the current version and its body, and the last [[AgentConfigState.Kept]] notes. */
final case class AgentConfigState(version: Int = 0, current: ConfigBody = ConfigBody(), recentChanges: Vector[ConfigNote] = Vector.empty)

object AgentConfigState:
  val Kept = 16

/** A `put`: the whole body, after its fields were read and checked, and the operator. */
final case class ConfigChange(body: ConfigBody, by: String, at: Instant)

/** A `put`'s answer: the version now, and the fields it changed (empty: nothing changed). */
final case class Put(version: Int, changed: Vector[String])

/** `ConfigVersion`'s state: one version's body, written once. */
final case class VersionState(body: Option[ConfigBody] = None)
