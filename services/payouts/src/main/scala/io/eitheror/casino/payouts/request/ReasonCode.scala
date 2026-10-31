package io.eitheror.casino.payouts.request

import io.eitheror.casino.contract.personal.Personal

/**
 * A reason from outside: a provider's, as psp-gateway passes it on the outcome route, an initiation or a
 * poll. Some are codes (`missing_credentials`, `93`); others are the provider's own message, which can
 * name the account holder. A code is kept and published; anything else is kept only as the request's
 * personal note, which erasure blanks, and is never put on a fact or an audit record (FR-025, FR-027).
 */
object ReasonCode:
  private val Shape = "[a-z0-9]+(?:_[a-z0-9]+)*".r

  def isCode(s: String): Boolean = s.length <= 64 && Shape.matches(s)

  /** The code, when the reason is one; else the text, personal. */
  def split(reason: Option[String]): (Option[String], Option[Personal[String]]) =
    reason.map(_.trim).filter(_.nonEmpty) match
      case Some(r) if isCode(r) => (Some(r), None)
      case Some(r)              => (None, Some(Personal(r)))
      case None                 => (None, None)
