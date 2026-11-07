package io.eitheror.casino.engagement.config

/**
 * The configuration route's secrets as the original showed and took them (`CustomerIOConfigRoutes`,
 * research R12): shown as the first three characters and asterisks; a value sent back masked keeps
 * the stored one.
 */
object Masking:

  /** First three characters and `len − 3` asterisks; a secret of three or fewer is shown whole, as the original. */
  def mask(secret: Option[String]): String =
    secret.filter(_.nonEmpty).fold("")(s => s.take(3) + "*" * math.max(0, s.length - 3))

  /** Non-empty and all `*` after the third character: what `mask` writes, sent back unchanged. */
  def isMasked(incoming: String): Boolean = incoming.nonEmpty && incoming.drop(3).forall(_ == '*')

  /** The secret to store: a masked value keeps the stored one, empty clears it, anything else replaces it. */
  def merge(incoming: String, stored: Option[String]): Option[String] =
    if isMasked(incoming) then stored.filter(_.nonEmpty)
    else Option(incoming).filter(_.nonEmpty)
