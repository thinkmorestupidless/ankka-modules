package io.eitheror.casino.audit.registry

import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.Wiring
import io.eitheror.casino.audit.ids.RegistryKeyBytes
import io.eitheror.casino.audit.ingest.IngestKeys

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * A key of 32 random bytes in the service's secret store (data-model.md *Secrets*). [[Seed]] makes it
 * at first start and is its one writer: made on a consumer's read, two instances could make two, and
 * whatever was digested or sealed under the one written over would be lost. Read once and held: it
 * never changes.
 */
class StoredKey(val name: String):
  private val held = AtomicReference[Option[RegistryKeyBytes]](None)

  private def decode(text: String): RegistryKeyBytes = RegistryKeyBytes(Base64.getDecoder.decode(text))

  /** The key, or why it cannot be had yet: the store unreadable, or the seed not yet run. */
  def read(store: SecretStore): Either[String, RegistryKeyBytes] =
    held.get() match
      case Some(k) => Right(k)
      case None =>
        try
          store.get(name).map(decode) match
            case Some(k) => held.set(Some(k)); Right(k)
            case None    => Left(s"the $name is not made yet")
        catch case e: CommandError => Left(s"the secret store is unavailable: ${e.code}: ${e.getMessage}")

  /** The key, made when absent. The seed's alone. */
  def make(store: SecretStore): RegistryKeyBytes =
    store.get(name) match
      case Some(text) => decode(text)
      case None =>
        val bytes = new Array[Byte](32)
        SecureRandom().nextBytes(bytes)
        store.put(name, Base64.getEncoder.encodeToString(bytes))
        RegistryKeyBytes(bytes)

  /** The key, or a refusal that makes a consumer's message redelivered until the seed has run. */
  def required(store: SecretStore): RegistryKeyBytes =
    read(store).fold(problem => throw IllegalStateException(problem), identity)

  /** Forgets the held key: a suite's restart. */
  def forget(): Unit = held.set(None)

/** The key behind every digest and registry id (research R5). */
object RegistryKey extends StoredKey("registry-key")

/** The key the quarantine seals a message's bytes under (research, *Found during implementation*). */
object QuarantineKey extends StoredKey("quarantine-key")

/** The single timer `seed`, set at every start: the service's keys made when absent, and handed to ingest. */
final class Seed(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    Seed.run(context.secrets)
    effects.done()

object Seed extends TimedAction.Companion[Seed](ComponentId("seed")):
  val TimerName = "seed"

  /** Makes both keys when absent and sets them in `Wiring` for the consumers. Idempotent. */
  def run(store: SecretStore): Unit =
    RegistryKey.make(store): Unit
    QuarantineKey.make(store): Unit
    Wiring.keys.set(Some(IngestKeys(RegistryKey.required(store), QuarantineKey.required(store), Wiring.settings.get().skewBound)))

  def create(context: TimedActionContext) = new Seed(context)
  val seed = handler("seed")(_.run)
