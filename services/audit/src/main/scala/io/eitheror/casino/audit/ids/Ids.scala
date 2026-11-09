package io.eitheror.casino.audit.ids

import java.nio.charset.StandardCharsets.UTF_8
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A key of the service's: 32 random bytes from its secret store (research R5). */
final class RegistryKeyBytes(private[audit] val bytes: Array[Byte]):
  require(bytes.length >= 32, "the registry key is at least 32 bytes")
  override def toString: String = "RegistryKeyBytes(…)"

/** How an id reaches the store (research R5): a person's by a pseudonym, an id built from one by a digest. */
object Ids:
  /** The kinds whose ids are people's: replaced by a pseudonym from the registry. Every approver is one too. */
  val PersonKinds: Set[String] = Set("player", "operator", "affiliate", "agent")

  def isPerson(kind: String): Boolean = PersonKinds(kind)

  private def hmac(key: RegistryKeyBytes, text: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.bytes, "HmacSHA256"))
    mac.doFinal(text.getBytes(UTF_8))

  /** A record, correlation or causation id as stored: `d-` and 32 hex of HMAC-SHA256 over `<brand>/<id>`. */
  def digest(key: RegistryKeyBytes, brand: String, id: String): String =
    "d-" + HexFormat.of().formatHex(hmac(key, s"$brand/$id")).take(32)

  /** The registry's entity id for a person: the whole HMAC in hex, so no real id is in an entity id, a log or a trace. */
  def registryId(key: RegistryKeyBytes, brand: String, kind: String, realId: String): String =
    HexFormat.of().formatHex(hmac(key, s"$brand/$kind/$realId"))

  /** Whether `id` carries any of the real ids, as a whole segment or a substring. Empty ids never match. */
  def contains(id: String, realIds: Iterable[String]): Boolean =
    realIds.exists(r => r.nonEmpty && id.contains(r))
