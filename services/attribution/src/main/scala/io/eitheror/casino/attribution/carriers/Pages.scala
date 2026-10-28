package io.eitheror.casino.attribution.carriers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.tag.{Jcs, Signing}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.util.Try

/** A feed's cursor (research R14): base64url of `<network>:<seq>`, opaque to the reader. */
object Cursor:
  def encode(network: String, seq: Long): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(s"$network:$seq".getBytes(UTF_8))

  /** The network and the sequence a cursor names; the empty cursor is the network's start. */
  def decode(network: String, text: Option[String]): Either[String, Long] =
    text.map(_.trim).filter(_.nonEmpty) match
      case None => Right(0L)
      case Some(t) =>
        Try(String(Base64.getUrlDecoder.decode(t), UTF_8)).toOption.flatMap { s =>
          val i = s.lastIndexOf(':')
          Option.when(i > 0)((s.take(i), s.drop(i + 1).toLongOption))
        } match
          case Some((n, Some(seq))) if n == network && seq >= 0 => Right(seq)
          case Some((n, Some(_))) if n != network               => Left("the cursor is another network's")
          case _                                                => Left("the cursor cannot be read")

/**
 * A feed page (contracts/protocol.md): the facts after the cursor, the next cursor, and the brand's
 * Ed25519 signature over the JCS form of the `facts` array under the key `kid` names.
 */
object Pages:
  val MaxLimit = 1000

  /** `facts` as their JSON, in sequence order; `lastSeq` the last one's, or the cursor's when empty. */
  def assemble(facts: Vector[JsonNode], network: String, lastSeq: Long, kid: String, privateKey: String): JsonNode =
    val array: ArrayNode = Json.arr(facts*)
    Json.obj(
      "facts"     -> array,
      "cursor"    -> Cursor.encode(network, lastSeq),
      "kid"       -> kid,
      "signature" -> Signing.encode(Signing.sign(privateKey, Jcs.bytes(array)))
    )

  /** Whether a page's signature is the key's over its facts, as a reader checks it. */
  def verify(page: JsonNode, publicKey: String): Boolean =
    (for
      facts     <- Option(page.get("facts")).filter(_.isArray)
      signature <- Option(page.get("signature")).map(_.asText).flatMap(Signing.decode)
    yield Signing.verify(publicKey, Jcs.bytes(facts), signature)).getOrElse(false)
