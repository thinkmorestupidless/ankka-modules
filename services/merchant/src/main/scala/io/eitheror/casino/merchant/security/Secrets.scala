package io.eitheror.casino.merchant.security

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.SecretStore
import io.eitheror.casino.merchant.Json

import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** The store could not be read: never the same as a secret that is absent (FR-018, `CashierCaptureAuthSpec`). */
final case class StoreUnavailable(reason: String)

/**
 * The secrets this service keeps, by name, in its own secret store (research R9): a credential's
 * secrets as one JSON map at `credential/<id>`, a terminal's secret properties at `terminal/<id>`,
 * the realm's admin client at `keycloak/operators-admin`, the invitation provider's key at
 * `engagement/transactional`. Reads go through a 30-second cache, as spec 003's provider
 * configurations do, so the money path does not read the database on every capture; a write here
 * invalidates the cache at once. A store that cannot be read is `StoreUnavailable`, never "none".
 */
final class Secrets(store: SecretStore, ttl: Duration = Secrets.Ttl, now: () => Instant = () => Instant.now()):
  private final case class Cached(value: Option[String], at: Instant)
  private val cache = ConcurrentHashMap[String, Cached]()

  def get(name: String): Either[StoreUnavailable, Option[String]] =
    Secrets.outage.fold(lookup(name))(reason => Left(StoreUnavailable(reason)))

  private def lookup(name: String): Either[StoreUnavailable, Option[String]] =
    Option(cache.get(name)).filter(c => Duration.between(c.at, now()).compareTo(ttl) < 0) match
      case Some(c) => Right(c.value)
      case None =>
        try
          val v = store.get(name)
          cache.put(name, Cached(v, now()))
          Right(v)
        catch case e: CommandError => Left(StoreUnavailable(s"${e.code}: ${e.getMessage}"))

  /** A JSON map kept under `name`; absent is empty. */
  def map(name: String): Either[StoreUnavailable, Map[String, String]] =
    get(name).map(_.flatMap(Json.parseObject).map { o =>
      o.properties.asScala.toVector.collect { case e if e.getValue.isValueNode => e.getKey -> e.getValue.asText }.toMap
    }.getOrElse(Map.empty))

  def putMap(name: String, values: Map[String, String]): Unit =
    if values.isEmpty then delete(name)
    else
      store.put(name, Json.render(Json.objOf(values)))
      cache.remove(name): Unit

  def delete(name: String): Unit =
    store.delete(name)
    cache.remove(name): Unit

  def invalidate(): Unit = cache.clear()

  def credential(id: String): Either[StoreUnavailable, Map[String, String]] = map(Secrets.credential(id))
  def terminal(id: String): Either[StoreUnavailable, Map[String, String]]   = map(Secrets.terminal(id))

object Secrets:
  val Ttl: Duration = Duration.ofSeconds(30)

  /** Set by a suite to make every read fail as an unreachable database would (the store-unreadable scenario). */
  @volatile var outage: Option[String] = None

  def credential(id: String): String = s"credential/$id"
  def terminal(id: String): String   = s"terminal/$id"
  val KeycloakAdmin                  = "keycloak/operators-admin"
  val Engagement                     = "engagement/transactional"

  /** The secret the capture's signature is made with, among a credential's secrets. */
  val WebhookSecret = "webhookSecret"

  /** A store whose reads fail, for a test of the money path's answer when the store is down. */
  def failing(reason: String): SecretStore = new SecretStore:
    def put(name: String, value: String): Unit = throw CommandError(reason, ErrorCode.Unavailable)
    def get(name: String): Option[String]      = throw CommandError(reason, ErrorCode.Unavailable)
    def delete(name: String): Unit             = throw CommandError(reason, ErrorCode.Unavailable)
