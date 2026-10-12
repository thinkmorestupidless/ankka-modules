package io.eitheror.casino.gameprovider.config

import com.thinkmorestupidless.ankka.sdk.SecretStore
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.provider.st8.St8Signature

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.security.{PrivateKey, PublicKey}
import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap
import scala.util.Try

/** Fetches a provider's key document: `{keys:[{kid, publicKey}]}`, each key base64 DER (spec 020 FR-011). */
trait KeyDocuments:
  def fetch(url: String): Either[String, Vector[String]]

object KeyDocuments:
  /** Over HTTP/1.1, which ST8 and the simulator both serve. */
  def http(timeout: Duration = Duration.ofSeconds(5)): KeyDocuments = new KeyDocuments:
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build()
    def fetch(url: String): Either[String, Vector[String]] =
      Try(client.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofString()))
        .toEither.left.map(e => s"key document unreachable: ${e.getMessage}")
        .flatMap(r => if r.statusCode / 100 == 2 then Right(r.body) else Left(s"key document answered ${r.statusCode}"))
        .flatMap(body => Json.parse(body).toRight("key document is not JSON"))
        .map(doc => doc.objects("keys").flatMap(_.text("publicKey")))

/**
 * The brand's provider configurations, read from the service's secret store and cached for 30 seconds
 * (research R4): a change is picked up within that bound on every instance and at once on the one that
 * took the write, without a redeploy (FR-026), and no callback reads the store itself (038 writes a
 * read record on every `get`). A key document is refreshed on the same cadence, inside FR-026's 60 s.
 */
final class ProviderConfigurations(
    secrets: SecretStore,
    keyDocuments: KeyDocuments = KeyDocuments.http(),
    ttl: Duration = Duration.ofSeconds(30),
    now: () => Instant = () => Instant.now()
):
  private final case class Cached[A](at: Instant, value: A)

  private val configs = ConcurrentHashMap[String, Cached[Option[ProviderConfiguration]]]()
  private val keys    = ConcurrentHashMap[String, Cached[Vector[PublicKey]]]()
  @volatile private var index: Option[Cached[Vector[String]]] = None

  private def fresh[A](c: Cached[A] | Null): Option[A] =
    Option(c).filter(x => Duration.between(x.at, now()).compareTo(ttl) < 0).map(_.value)

  def get(provider: String): Option[ProviderConfiguration] =
    fresh(configs.get(provider)).getOrElse {
      val read = secrets.get(ProviderConfiguration.secretName(provider)).flatMap(ProviderConfiguration.decode)
      configs.put(provider, Cached(now(), read))
      read
    }

  /** The providers the brand holds a configuration for, in the order they were first written. */
  def providers: Vector[String] =
    index.flatMap(c => fresh(c)).getOrElse {
      val read = secrets.get(ProviderConfiguration.IndexName).flatMap(Json.parse).map(_.strings("providers")).getOrElse(Vector.empty)
      index = Some(Cached(now(), read))
      read
    }

  /** Writes a provider's configuration and forgets what was cached for it here. */
  def put(provider: String, configuration: ProviderConfiguration): Unit =
    secrets.put(ProviderConfiguration.secretName(provider), ProviderConfiguration.encode(configuration.copy(updatedAt = Some(now()))))
    index = None
    val listed = providers
    if !listed.contains(provider) then
      secrets.put(ProviderConfiguration.IndexName, Json.render(Json.obj("providers" -> (listed :+ provider))))
    index = None
    configs.remove(provider)
    keys.remove(provider): Unit

  /** Forgets everything cached, as a restart would. */
  def invalidate(): Unit =
    configs.clear(); keys.clear(); index = None

  /**
   * The keys a callback from `provider` verifies under: the literal key, or every key the document
   * lists (the current and the next during a rotation). None when unconfigured or unreachable, which
   * refuses every callback rather than admitting one.
   */
  def verifyingKeys(provider: String): Vector[PublicKey] =
    fresh(keys.get(provider)).getOrElse {
      val read = get(provider).toVector.flatMap { c =>
        val texts = c.providerPublicKey match
          case Some(k) => Vector(k)
          case None    => c.providerKeyDocumentUrl.toVector.flatMap(url => keyDocuments.fetch(url).getOrElse(Vector.empty))
        texts.flatMap(t => St8Signature.publicKey(t).toOption)
      }
      // An empty answer from an unreachable document is not cached, so the next callback tries again.
      if read.nonEmpty then keys.put(provider, Cached(now(), read))
      read
    }

  def operatorKey(provider: String): Option[PrivateKey] = get(provider).flatMap(c => St8Signature.privateKey(c.operatorPrivateKey).toOption)
