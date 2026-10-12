package io.eitheror.casino.simulator.st8

import com.thinkmorestupidless.ankka.sdk.SecretStore
import io.eitheror.casino.simulator.core.KeyRing

import java.security.{PrivateKey, PublicKey}
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * The simulator's signing keys in the secret store (FR-010, FR-011; research R10): never in
 * configuration, never in an entity. `keys/<kid>/private` and `keys/<kid>/public` hold a pair;
 * `keys/current`, `keys/next` and `keys/withdrawn` hold a key id each. The withdrawn pair is kept
 * so the rotated-key scenario can sign with a key the document no longer lists.
 *
 * Every read is a round trip to the store; one signature reads the pair once.
 */
final class Keys(store: SecretStore) extends KeyRing:

  private val wrongPair = AtomicReference(Option.empty[java.security.KeyPair])

  /** A current key, generated once when the store has none. Answers its id. */
  def ensureCurrent(): String =
    store.get("keys/current") match
      case Some(kid) if store.get(s"keys/$kid/private").isDefined => kid
      case _ =>
        val kid = put(Signer.generate())
        store.put("keys/current", kid)
        kid

  def currentKeyId: String       = store.get("keys/current").getOrElse(ensureCurrent())
  def nextKeyId: Option[String]  = store.get("keys/next")
  def withdrawnKeyId: Option[String] = store.get("keys/withdrawn")

  def publishNext(): String =
    nextKeyId.getOrElse {
      val kid = put(Signer.generate())
      store.put("keys/next", kid)
      kid
    }

  def promote(): String =
    nextKeyId match
      case None => currentKeyId
      case Some(next) =>
        val old = currentKeyId
        store.put("keys/withdrawn", old)
        store.put("keys/current", next)
        store.delete("keys/next")
        next

  /**
   * The old key left the document when the next was promoted; its pair stays under
   * `keys/withdrawn` so the rotated-key scenario can sign with a key the document no longer lists.
   */
  def withdrawOld(): Unit = ()

  /** The key a choice names, with its id, or `None` when there is no such key yet. */
  def signingKey(choice: String): Option[(String, PrivateKey)] = choice match
    case "current"   => Some(currentKeyId).flatMap(kid => privateOf(kid).map(kid -> _))
    case "next"      => nextKeyId.orElse(Some(publishNext())).flatMap(kid => privateOf(kid).map(kid -> _))
    case "withdrawn" => withdrawnKeyId.flatMap(kid => privateOf(kid).map(kid -> _))
    case "wrong" =>
      val pair = wrongPair.updateAndGet(p => p.orElse(Some(Signer.generate()))).get
      Some("unpublished" -> pair.getPrivate)
    case other => throw IllegalArgumentException(s"no key '$other': current, next, wrong or withdrawn")

  def publicOf(kid: String): Option[PublicKey] = store.get(s"keys/$kid/public").map(Signer.publicKey)

  /** The key document (contracts/st8-wire.md): the current key and, during a rotation, the next. */
  def document(): GoJson.Value =
    val current = currentKeyId
    def entry(kid: String) = store.get(s"keys/$kid/public").map(spki => GoJson.obj("kid" -> GoJson.str(kid), "spki" -> GoJson.str(spki)))
    val fields = Vector(
      Some("current" -> entry(current).get),
      nextKeyId.flatMap(entry).map("next" -> _),
      Some("cacheSeconds" -> GoJson.Value.Num("60"))
    ).flatten
    GoJson.Value.Obj(fields)

  /** The current public key as the stand-in registers it with an original adapter (research R8). */
  def currentPublicText: String = store.get(s"keys/$currentKeyId/public").get

  private def privateOf(kid: String): Option[PrivateKey] = store.get(s"keys/$kid/private").map(Signer.privateKey)

  private def put(pair: java.security.KeyPair): String =
    val kid = s"k-${LocalDate.now()}-${java.util.UUID.randomUUID().toString.take(8)}"
    store.put(s"keys/$kid/private", Signer.privateKeyText(pair.getPrivate))
    store.put(s"keys/$kid/public", Signer.publicKeyText(pair.getPublic))
    kid
