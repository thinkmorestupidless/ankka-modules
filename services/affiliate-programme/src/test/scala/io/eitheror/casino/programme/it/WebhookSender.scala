package io.eitheror.casino.programme.it

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import io.eitheror.casino.contract.facts.AttributionFact

import java.nio.charset.StandardCharsets.UTF_8
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A brand elsewhere, posting its facts to the programme as spec 012's webhook does (contracts/inbound.md):
 * the four headers, the body signed with the secret the programme answered when the brand was added.
 */
final class WebhookSender(kit: Kit, val brand: String, @volatile var secret: String):
  def body(fact: AttributionFact): String = writeToString(fact)

  def signature(body: String, key: String = secret): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.getBytes(UTF_8), "HmacSHA256"))
    "sha256=" + mac.doFinal(body.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  def headers(fact: AttributionFact, body: String): Seq[(String, String)] = Seq(
    "X-Attribution-Protocol"  -> "1",
    "X-Attribution-Fact-Id"   -> fact.factId,
    "X-Attribution-Kind"      -> Facts.kindOf(fact),
    "X-Attribution-Signature" -> signature(body)
  )

  /** Posts one fact, signed; `tamper` changes the headers before sending. */
  def post(fact: AttributionFact, tamper: Seq[(String, String)] => Seq[(String, String)] = identity, path: String = brand): Reply =
    val b = body(fact)
    kit.request("POST", s"/v1/programme/inbound/$path", Some(b), kit.fromInternet ++ tamper(headers(fact, b)))
