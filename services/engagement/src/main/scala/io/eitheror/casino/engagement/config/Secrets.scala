package io.eitheror.casino.engagement.config

import com.thinkmorestupidless.ankka.sdk.SecretStore

import java.time.{Duration, Instant}
import scala.collection.concurrent.TrieMap

/** The brand's three Customer.io keys (R12). */
final case class CrmKeys(track: Option[String], app: Option[String], signing: Option[String])

/**
 * The brand's Customer.io keys in the secret store, under `crm/<slug>/track-key`, `app-key` and `signing-key`
 * (data-model.md `Configuration`). Each read is held `ttl` per instance, so a send's steps do not read the store
 * on every attempt, and a key rotated reaches the next attempt within it.
 */
final class Secrets(ttl: Duration, now: () => Instant):
  private val held = TrieMap.empty[String, (Instant, Option[String])]

  def keys(store: SecretStore, slug: String): CrmKeys =
    CrmKeys(read(store, Secrets.track(slug)), read(store, Secrets.app(slug)), read(store, Secrets.signing(slug)))

  /** Writes `value` (or removes the secret, for none) and forgets what was held of it. */
  def write(store: SecretStore, name: String, value: Option[String]): Unit =
    value.filter(_.nonEmpty).fold(store.delete(name))(store.put(name, _))
    held.remove(name): Unit

  def forget(): Unit = held.clear()

  private def read(store: SecretStore, name: String): Option[String] =
    val at = now()
    held.get(name) match
      case Some((when, v)) if when.plus(ttl).isAfter(at) => v
      case _ =>
        val v = store.get(name)
        held.update(name, (at, v))
        v

object Secrets:
  def track(slug: String): String   = s"crm/$slug/track-key"
  def app(slug: String): String     = s"crm/$slug/app-key"
  def signing(slug: String): String = s"crm/$slug/signing-key"
