package io.eitheror.casino.publicapi.wire

import java.time.Instant
import java.time.format.DateTimeFormatter

/** The contract's `DateTime`: ISO 8601 in UTC with `Z` (research R12). */
object Times:
  def of(at: Instant): String = DateTimeFormatter.ISO_INSTANT.format(at)
