package io.eitheror.casino.attribution.record

import io.eitheror.casino.attribution.{Binding, TagOutcome, Touch}

import java.time.Instant

/**
 * Later touches (FR-009): what a tag, code or link presented for a player is recorded as when it binds
 * nothing. A touch never moves a binding; it is the record's history of what else the player came by.
 */
object Touches:
  /** A valid tag or a known code for a player already bound. */
  val Later = "later"
  /** A code no network's document names. */
  val Unknown = "unknown"
  /** A code whose document could not be read when it was presented. */
  val Unresolved = "unresolved"

  /** A tag presented for a bound player, or refused: `later` when valid, else its refusal reason. */
  def ofTag(tag: String, outcome: TagOutcome, at: Instant): Touch = outcome match
    case TagOutcome.Valid(_, _, _)  => Touch(Touch.Tag, tag, at, Later)
    case TagOutcome.Refused(reason) => Touch(Touch.Tag, tag, at, reason)

  /** A code that bound nothing: `later` (the player is bound, or the window passed), `unknown` or `unresolved`. */
  def ofCode(code: String, outcome: String, at: Instant): Touch = Touch(Touch.Code, code.toLowerCase, at, outcome)

  /** A binding refused because the record is bound: kept as a touch of the binding's link. */
  def ofBinding(b: Binding): Touch =
    Touch(if b.touch == Binding.Tag then Touch.Tag else Touch.Code, b.link, b.boundAt, Record.AlreadyBound)
