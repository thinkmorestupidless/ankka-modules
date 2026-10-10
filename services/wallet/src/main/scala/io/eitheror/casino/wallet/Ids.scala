package io.eitheror.casino.wallet

import java.util.UUID

/** Ids derived, never minted: the same on every replay, retry and reader. */
object Ids:
  /** A money command's transaction id: the wallet and the caller's key (FR-002). */
  def transactionId(walletId: String, key: String): String =
    UUID.nameUUIDFromBytes(s"$walletId/$key".getBytes("UTF-8")).toString
