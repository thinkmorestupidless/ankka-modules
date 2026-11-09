package io.eitheror.casino.audit.ingest

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.audit.ids.{Ids, RegistryKeyBytes}
import io.eitheror.casino.audit.keep.{Decision, Keep}
import io.eitheror.casino.audit.registry.{Bind, Identity, Pseudonym}
import io.eitheror.casino.audit.store.{Kept, Record}
import io.eitheror.casino.audit.stored.{Source, Stored}
import io.eitheror.casino.contract.facts.{AuditRecord, PlatformAudit, PlatformModules}
import io.eitheror.casino.contract.personal.PersonalFields

import java.time.{Duration, Instant}
import scala.util.Try

/** What one message became (contracts/ingest.md, "One message, in order"). */
enum Outcome:
  case Kept, Same, PassedOver
  case Held(why: String)

/** The components ingest calls, routed by the runtime in a cluster and to entity kits in the unit suites. */
trait IngestCalls:
  def issue(registryId: String): String
  def bind(pseudonym: String, bind: Bind): String
  def keep(stored: Stored): Kept
  def hold(id: String, hold: Hold): Unit
  def received(source: String, received: Received): Unit

object IngestCalls:
  def of(client: ComponentClient): IngestCalls = new IngestCalls:
    def issue(registryId: String): String = client.forKeyValueEntity(Pseudonym.entityId(registryId)).call(Pseudonym.issue).invoke().pseudonym
    def bind(pseudonym: String, b: Bind): String = client.forKeyValueEntity(Identity.entityId(pseudonym)).call(Identity.bind).invoke(b).outcome
    def keep(stored: Stored): Kept = client.forKeyValueEntity(Record.entityId(stored.key)).call(Record.keep).invoke(stored)
    def hold(id: String, h: Hold): Unit = client.forKeyValueEntity(EntityId(id)).call(Quarantined.hold).invoke(h): Unit
    def received(source: String, r: Received): Unit =
      client.forKeyValueEntity(SourceState.entityId(source)).call(SourceState.received).invoke(r): Unit

/** The keys and bound ingest is given, read once at start. */
final case class IngestKeys(registry: RegistryKeyBytes, quarantine: RegistryKeyBytes, skewBound: Duration)

/**
 * One message from a source, in contracts/ingest.md's order: decode, check, keep or pass over,
 * pseudonymise, store, count. Each step is idempotent, so a message redelivered after a step threw
 * changes nothing; a step that throws (a component unavailable) leaves the message to be redelivered.
 */
object Ingest:

  def record(source: Source, bytes: Array[Byte], now: Instant, calls: IngestCalls, keys: IngestKeys): Outcome =
    def hold(why: String): Outcome =
      calls.hold(Quarantined.id(source.name, bytes), Quarantined.hold(source.name, why, bytes, now, keys.quarantine))
      Outcome.Held(why)
    Try(PlatformAudit.serializer.fromBytes(bytes)).toOption.collect { case r: AuditRecord => r } match
      case None                                       => hold(Quarantined.Undecodable)
      case Some(r) if mismatched(source, r)           => hold(Quarantined.BrandMismatch)
      case Some(r) if carriesPersonalValue(r)         => hold(Quarantined.PersonalValue)
      case Some(r) =>
        val skewed = r.occurredAt.isAfter(now.plus(keys.skewBound))
        Keep.decide(r) match
          case Decision.PassOver =>
            calls.received(source.name, Received(now, r.occurredAt, kept = false, skewed))
            Outcome.PassedOver
          case Decision.Keep =>
            val pseudonyms = Stored.people(r).map { (kind, realId) =>
              val pseudonym = calls.issue(Ids.registryId(keys.registry, r.brand, kind, realId))
              (kind, realId) -> (pseudonym -> (calls.bind(pseudonym, Bind(r.brand, kind, realId)) == Identity.Conflict))
            }.toMap
            // A pseudonym bound to someone else will not bind on a redelivery either: the message is held
            // and reported, as a collision is, rather than stopping its source behind it.
            if pseudonyms.values.exists(_._2) then hold(Quarantined.PseudonymConflict)
            else
              val stored = Stored.from(r, source, (kind, id) => pseudonyms((kind, id))._1, keys.registry, now)
              store(source, stored, r.occurredAt, skewed, now, calls, () => hold(Quarantined.Collision))

  /** A brand's `platform.modules` change, as an installation record about the brand. */
  def module(brand: String, bytes: Array[Byte], now: Instant, calls: IngestCalls, keys: IngestKeys): Outcome =
    val source = Source.Modules(brand)
    def hold(why: String): Outcome =
      calls.hold(Quarantined.id(source.name, bytes), Quarantined.hold(source.name, why, bytes, now, keys.quarantine))
      Outcome.Held(why)
    Try(PlatformModules.serializer.fromBytes(bytes)).toOption match
      case None => hold(Quarantined.Undecodable)
      case Some(change) =>
        val stored = Stored.fromModule(change, brand, keys.registry, now)
        store(source, stored, change.at, change.at.isAfter(now.plus(keys.skewBound)), now, calls, () => hold(Quarantined.Collision))

  private def store(source: Source, s: Stored, occurredAt: Instant, skewed: Boolean, now: Instant, calls: IngestCalls,
      collision: () => Outcome): Outcome =
    val kept = calls.keep(s)
    kept.outcome match
      case Record.Collision => collision()
      case Record.Same      => Outcome.Same
      case _ =>
        calls.received(source.name, Received(now, occurredAt, kept = true, skewed))
        Outcome.Kept

  /** A brand's topic carries only the brand's records; payments' name any brand, or `payments`. */
  def mismatched(source: Source, r: AuditRecord): Boolean = source match
    case Source.Brand(slug) => r.brand != slug
    case _                  => false

  /** A change of a personal field that carries a value: never stored (FR-003). */
  def carriesPersonalValue(r: AuditRecord): Boolean =
    r.changes.exists(c => PersonalFields.names(c.field) && (c.before.isDefined || c.after.isDefined))
