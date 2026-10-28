package io.eitheror.casino.attribution.tag

import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A player's pseudonym for a network (contracts/protocol.md; research R4): HMAC-SHA256 of the player id
 * under the brand's key for that network, base64url without padding, 43 characters. Different per network
 * and per brand, the same for the player's life: the key is never rotated.
 */
object Pseudonyms:

  def derive(key: Array[Byte], playerId: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(playerId.getBytes(UTF_8)))

  /** A new key: 32 random bytes, base64, as the store keeps it. */
  def newKey(): String =
    val bytes = new Array[Byte](32)
    SecureRandom().nextBytes(bytes)
    Base64.getEncoder.encodeToString(bytes)

  /** A stored key's bytes. */
  def keyBytes(stored: String): Array[Byte] = Base64.getDecoder.decode(stored.trim)

  /** Where the network's key is kept in the service's store. */
  def secretName(network: String): String = s"network/$network/pseudonym-key"
