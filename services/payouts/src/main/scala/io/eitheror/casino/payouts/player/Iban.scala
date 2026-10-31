package io.eitheror.casino.payouts.player

/**
 * IBAN format and checksum (ISO 13616, mod-97-10), the original's `IbanValidator` unchanged (R13). The
 * stored form is compact and upper case, so the last four, the display and the payout read one value.
 */
object Iban:
  private val Structure = "^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$".r

  def normalise(raw: String): String = raw.replaceAll("\\s", "").toUpperCase

  def isValid(raw: String): Boolean =
    val iban = normalise(raw)
    Structure.matches(iban) && {
      val rearranged = iban.substring(4) + iban.substring(0, 4)
      rearranged.foldLeft(0) { (acc, ch) =>
        if ch.isDigit then (acc * 10 + (ch - '0')) % 97 else (acc * 100 + (ch - 'A' + 10)) % 97
      } == 1
    }

  def lastFour(iban: String): String = normalise(iban).takeRight(4)
