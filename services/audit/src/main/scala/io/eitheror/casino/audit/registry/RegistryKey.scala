package io.eitheror.casino.audit.registry

import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.ids.RegistryKeyBytes

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * The registry key (data-model.md *Secrets*): 32 random bytes in the service's secret store under
 * `registry-key`, behind every digest and registry id (research R5). [[Seed]] makes it at first start
 * and is its one writer: made on a consumer's read, two instances could make two, and the digests under
 * the one written over would join nothing. Read once and held: it never changes.
 */
object RegistryKey:
  val Name = "registry-key"

  private val held = AtomicReference[Option[RegistryKeyBytes]](None)

  private def decode(text: String): RegistryKeyBytes = RegistryKeyBytes(Base64.getDecoder.decode(text))

  /** The key, or why it cannot be had yet: the store unreadable, or the seed not yet run. */
  def read(store: SecretStore): Either[String, RegistryKeyBytes] =
    held.get() match
      case Some(k) => Right(k)
      case None =>
        try
          store.get(Name).map(decode) match
            case Some(k) => held.set(Some(k)); Right(k)
            case None    => Left("the registry key is not made yet")
        catch case e: CommandError => Left(s"the secret store is unavailable: ${e.code}: ${e.getMessage}")

  /** The key, made when absent. The seed's alone. */
  def make(store: SecretStore): RegistryKeyBytes =
    store.get(Name) match
      case Some(text) => decode(text)
      case None =>
        val bytes = new Array[Byte](32)
        SecureRandom().nextBytes(bytes)
        store.put(Name, Base64.getEncoder.encodeToString(bytes))
        RegistryKeyBytes(bytes)

  /** The key, or a refusal that makes a consumer's message redelivered until the seed has run. */
  def required(store: SecretStore): RegistryKeyBytes =
    read(store).fold(problem => throw IllegalStateException(problem), identity)

  /** Forgets the held key: a suite's restart. */
  def forget(): Unit = held.set(None)

/** The single timer `seed`, set at every start: the registry key made when absent. */
final class Seed(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    RegistryKey.make(context.secrets): Unit
    effects.done()

object Seed extends TimedAction.Companion[Seed](ComponentId("seed")):
  val TimerName = "seed"

  def create(context: TimedActionContext) = new Seed(context)
  val seed = handler("seed")(_.run)
