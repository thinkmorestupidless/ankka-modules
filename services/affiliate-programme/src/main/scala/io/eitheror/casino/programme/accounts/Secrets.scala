package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.SecretStore
import io.eitheror.casino.programme.sealing.Sealing

import java.time.{Duration, Instant}
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** The store could not be read: never the same as a secret that is absent. */
final case class StoreUnavailable(reason: String)

/**
 * The secrets this service keeps in its own store (data-model.md *Secrets*), as `merchant`'s: each
 * affiliate's data key at `affiliate/<id>/data-key`, the email digest's key at `email-index-key`, a
 * brand's secrets at `brand/<slug>/<name>`. Keys are base64. A `Secrets` is made per request or step, and
 * its cache (60 seconds at most) lasts as long: a name read twice within it is asked once, and a write or a
 * destroy here clears it. No cache outlives its caller, so no node reads a key another has destroyed. A key once destroyed is gone: what it sealed reads as erased
 * (FR-037).
 */
final class Secrets(store: SecretStore, ttl: Duration = Secrets.Ttl, now: () => Instant = () => Instant.now()):
  private final case class Cached(value: Option[String], at: Instant)
  private val cache = ConcurrentHashMap[String, Cached]()

  def get(name: String): Either[StoreUnavailable, Option[String]] =
    Option(cache.get(name)).filter(c => Duration.between(c.at, now()).compareTo(ttl) < 0) match
      case Some(c) => Right(c.value)
      case None =>
        try
          val v = store.get(name)
          cache.put(name, Cached(v, now()))
          Right(v)
        catch case e: CommandError => Left(StoreUnavailable(s"${e.code}: ${e.getMessage}"))

  private def put(name: String, value: String): Either[StoreUnavailable, Unit] =
    try
      store.put(name, value)
      cache.put(name, Cached(Some(value), now())): Unit
      Right(())
    catch case e: CommandError => Left(StoreUnavailable(s"${e.code}: ${e.getMessage}"))

  private def key(name: String): Either[StoreUnavailable, Option[Array[Byte]]] = get(name).map(_.map(Base64.getDecoder.decode))

  /** A key under `name`, made the first time it is asked for. */
  private def keyMadeOnce(name: String): Either[StoreUnavailable, Array[Byte]] =
    key(name).flatMap {
      case Some(k) => Right(k)
      case None =>
        val k = Sealing.newKey()
        put(name, Base64.getEncoder.encodeToString(k)).map(_ => k)
    }

  /** The affiliate's data key, made once, when the account is (one writer: the account's id is new). */
  def dataKey(affiliate: String): Either[StoreUnavailable, Array[Byte]] = keyMadeOnce(Secrets.dataKey(affiliate))

  /** The affiliate's data key if it still exists; `None` after an erasure. */
  def existingDataKey(affiliate: String): Either[StoreUnavailable, Option[Array[Byte]]] = key(Secrets.dataKey(affiliate))

  /** Destroys the affiliate's data key (the erasure's `destroy` step); destroying one already gone is nothing. */
  def destroy(affiliate: String): Either[StoreUnavailable, Unit] =
    val name = Secrets.dataKey(affiliate)
    try
      store.delete(name)
      cache.remove(name): Unit
      Right(())
    catch case e: CommandError => Left(StoreUnavailable(s"${e.code}: ${e.getMessage}"))

  /**
   * The key of the email digest (research R9), unavailable until `Seed` has made it: made on a request's
   * read, two requests could make two, and the digests under the one written over would name no one.
   */
  def emailIndexKey: Either[StoreUnavailable, Array[Byte]] =
    key(Secrets.EmailIndexKey).flatMap(_.toRight(StoreUnavailable("the email index key is not made yet")))

  /** `Seed`'s, its one writer: the key made the first time. */
  def makeEmailIndexKey(): Either[StoreUnavailable, Array[Byte]] = keyMadeOnce(Secrets.EmailIndexKey)

  /** A brand's secret: `webhook-secret`, `customerio-key`. */
  def brandSecret(slug: String, name: String): Either[StoreUnavailable, Option[String]] = get(Secrets.brand(slug, name))

  def putBrandSecret(slug: String, name: String, value: String): Either[StoreUnavailable, Unit] = put(Secrets.brand(slug, name), value)

  def invalidate(): Unit = cache.clear()

object Secrets:
  val Ttl: Duration = Duration.ofSeconds(60)

  def dataKey(affiliate: String): String     = s"affiliate/$affiliate/data-key"
  val EmailIndexKey                          = "email-index-key"
  def brand(slug: String, name: String): String = s"brand/$slug/$name"
  def signingKey(kid: String): String        = s"signing-key/$kid"
  val WebhookSecret                          = "webhook-secret"
  val CustomerIoKey                          = "customerio-key"

  /** A store whose calls fail, for the store-unreadable cases. */
  def failing(reason: String): SecretStore = new SecretStore:
    def put(name: String, value: String): Unit = throw CommandError(reason, ErrorCode.Unavailable)
    def get(name: String): Option[String]      = throw CommandError(reason, ErrorCode.Unavailable)
    def delete(name: String): Unit             = throw CommandError(reason, ErrorCode.Unavailable)
