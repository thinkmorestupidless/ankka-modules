package io.eitheror.casino.players.registration

import java.nio.charset.StandardCharsets.UTF_8
import java.util.{HexFormat, Locale}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The tokens duplicates are found by (FR-036; research R9): a keyed digest of what two registrations
 * would share, so the index holds no name, date of birth, fingerprint, email or network address and a
 * reader of it learns none. Hex HMAC-SHA256 under `players-lookup-key`; the identity token over the given
 * name, the family name and the date of birth, each trimmed and lower-cased, joined with `|`, and none when
 * any part is empty. Spec 011 FR-035 adds the email variant ([[NormalisedEmail]]) and the registration's
 * network address, trimmed and lower-cased. Stands in for ankka 042's lookup tokens, which replace it.
 */
object LookupTokens:

  def identity(key: String, givenName: String, family: String, dateOfBirth: String): Option[String] =
    val parts = Vector(givenName, family, dateOfBirth).map(_.trim.toLowerCase)
    Option.when(parts.forall(_.nonEmpty))(digest(key, "identity|" + parts.mkString("|")))

  def device(key: String, fingerprint: String): Option[String] =
    Option(fingerprint).map(_.trim).filter(_.nonEmpty).map(f => digest(key, "device|" + f))

  /** The email's variant (spec 011 FR-035): none when it does not parse or its local part is too short to tell. */
  def emailVariant(key: String, email: String): Option[String] =
    Option(email).flatMap(NormalisedEmail.of).map((local, domain) => digest(key, s"email|$local@$domain"))

  /** The registration's network address (spec 011 FR-035), for the 24-hour rate and the same-address pair. */
  def address(key: String, networkAddress: String): Option[String] =
    Option(networkAddress).map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty).map(a => digest(key, "address|" + a))

  /** Every token a registration presents, tagged with its signal, in the order the match reads them. */
  def of(key: String, givenName: String, family: String, dateOfBirth: String, fingerprint: Option[String], email: String,
      networkAddress: Option[String]): Vector[String] =
    identity(key, givenName, family, dateOfBirth).map(tagged(Identity, _)).toVector ++
      fingerprint.flatMap(device(key, _)).map(tagged(Device, _)) ++
      emailVariant(key, email).map(tagged(EmailVariant, _)) ++
      networkAddress.flatMap(address(key, _)).map(tagged(Address, _))

  /** The signal a token was found by, as the workflow's state keeps it: `identity:<token>`, `device:<token>`, … */
  def tagged(signal: String, token: String): String = s"$signal:$token"

  /** The signal and the token (the index key) of a tagged token. */
  def split(tagged: String): (String, String) =
    tagged.split(":", 2) match
      case Array(signal, token) => (signal, token)
      case _                    => ("identity", tagged)

  val Identity     = "identity"
  val Device       = "device"
  val EmailVariant = "email_variant"
  val Address      = "address"

  /** Three or more registrations from the new player's address within 24 hours: a signal only beside an email variant. */
  val AddressRate = "address_rate"

  /** A comparison that could not be made: a weak signal, as the original's `check_failed`. */
  val CheckFailed = "check_failed"

  private def digest(key: String, text: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.getBytes(UTF_8), "HmacSHA256"))
    HexFormat.of().formatHex(mac.doFinal(text.getBytes(UTF_8)))

/**
 * An email's variant (spec 011 FR-035; the original's `MultiAccountDetector.normalizeEmailLocal`): the
 * address lower-cased, its local part without a `+` tag, without dots on Gmail (which ignores them) and
 * then without digits, with its domain. None when the address does not parse or the local part left is
 * shorter than six characters, which match unrelated people too often to count.
 */
object NormalisedEmail:
  private val GmailDomains = Set("gmail.com", "googlemail.com")

  val MinLocalLength = 6

  def of(email: String): Option[(String, String)] =
    email.trim.toLowerCase(Locale.ROOT).split("@", -1) match
      case Array(local, domain) if domain.nonEmpty =>
        val untagged   = local.takeWhile(_ != '+')
        val undotted   = if GmailDomains(domain) then untagged.replace(".", "") else untagged
        val normalised = undotted.replaceAll("\\d", "")
        Option.when(normalised.length >= MinLocalLength)(normalised -> domain)
      case _ => None
