package io.eitheror.casino.programme.sealing

import java.nio.charset.StandardCharsets.UTF_8
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The blind index of an email (research R9): HMAC-SHA256 of the lowercased, trimmed address under the
 * service's `email-index-key`, in hex. It finds an account by email without the email being stored.
 */
object Digest:
  def normalised(email: String): String = email.trim.toLowerCase(java.util.Locale.ROOT)

  def of(email: String, key: Array[Byte]): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    HexFormat.of().formatHex(mac.doFinal(normalised(email).getBytes(UTF_8)))
