package io.eitheror.casino.provider.st8

import com.thinkmorestupidless.ankka.sdk.SecretStore
import io.eitheror.casino.provider.core.KeyRing

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

  /** The key signing stopped with at the last switch, still listed until it is withdrawn. */
  def previousKeyId: Option[String] = store.get("keys/previous")

  /**
   * The switch (spec 026 FR-020): every later call is signed with the next key. The old key stays
   * in the document, as `previous`, until it is withdrawn, so a call it signed that is still on its
   * way verifies.
   */
  def promote(): String =
    nextKeyId match
      case None => currentKeyId
      case Some(next) =>
        val old = currentKeyId
        store.put("keys/previous", old)
        store.put("keys/current", next)
        store.delete("keys/next")
        next

  /**
   * The withdrawal: the key signing stopped with leaves the document. Its pair stays under
   * `keys/withdrawn` so the rotated-key scenario can sign with a key the document no longer lists.
   */
  def withdrawOld(): Unit =
    previousKeyId.foreach { old =>
      store.put("keys/withdrawn", old)
      store.delete("keys/previous")
    }

  /**
   * The house provider's withdrawal (spec 026 FR-020; review): as `withdrawOld`, and the withdrawn
   * key's private half is deleted, so nothing can sign with it again. Its id and public half stay,
   * for the key state and for a call it signed that is still being verified. The simulator keeps
   * `withdrawOld`, whose rotated-key scenario signs with the withdrawn key on purpose.
   */
  def withdrawForGood(): Unit =
    previousKeyId.foreach { old =>
      withdrawOld()
      store.delete(s"keys/$old/private")
    }

  /** Whether a key's private half is still held. */
  def holdsPrivate(kid: String): Boolean = store.get(s"keys/$kid/private").isDefined

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

  /**
   * The key document (contracts/st8-wire.md): the current key and, during a rotation, the next;
   * and `keys`, every key a call may be signed with now (the current, the next, and the previous
   * until it is withdrawn), as `{kid, publicKey}`, the shape spec 003's brand reads.
   */
  def document(): GoJson.Value =
    val current = currentKeyId
    def entry(kid: String) = store.get(s"keys/$kid/public").map(spki => GoJson.obj("kid" -> GoJson.str(kid), "spki" -> GoJson.str(spki)))
    val listed = (Vector(current) ++ nextKeyId ++ previousKeyId).distinct.flatMap { kid =>
      store.get(s"keys/$kid/public").map(spki => GoJson.obj("kid" -> GoJson.str(kid), "publicKey" -> GoJson.str(spki)))
    }
    val fields = Vector(
      Some("current" -> entry(current).get),
      nextKeyId.flatMap(entry).map("next" -> _),
      Some("keys" -> GoJson.Value.Arr(listed)),
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
