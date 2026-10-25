package io.eitheror.casino.players.registration

import java.nio.charset.StandardCharsets.UTF_8
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The tokens duplicates are found by (FR-036; research R9): a keyed digest of what two registrations
 * would share, so the index holds no name, date of birth or fingerprint and a reader of it learns none.
 * Hex HMAC-SHA256 under `players-lookup-key`; the identity token over the given name, the family name and
 * the date of birth, each trimmed and lower-cased, joined with `|`, and none when any part is empty.
 * Stands in for ankka 042's lookup tokens, which replace it.
 */
object LookupTokens:

  def identity(key: String, givenName: String, family: String, dateOfBirth: String): Option[String] =
    val parts = Vector(givenName, family, dateOfBirth).map(_.trim.toLowerCase)
    Option.when(parts.forall(_.nonEmpty))(digest(key, "identity|" + parts.mkString("|")))

  def device(key: String, fingerprint: String): Option[String] =
    Option(fingerprint).map(_.trim).filter(_.nonEmpty).map(f => digest(key, "device|" + f))

  private def digest(key: String, text: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.getBytes(UTF_8), "HmacSHA256"))
    HexFormat.of().formatHex(mac.doFinal(text.getBytes(UTF_8)))
