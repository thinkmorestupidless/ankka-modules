package io.eitheror.casino.simulator.core

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * One scenario of the library, keyed by its name: the document as written, validated before it is
 * stored (FR-019). A tester adds or replaces one without a release (SC-005); a shipped one is seeded
 * at start only when its version is higher than the stored, so an edit survives a restart.
 */
final case class StoredScenario(name: String, version: Int, json: String, shipped: Boolean)

final class ScenarioEntity(context: KeyValueEntityContext) extends KeyValueEntity[Option[StoredScenario]]:

  def emptyState: Option[StoredScenario] = None

  /** An operator's put: validated by the caller (the schema needs the classpath, an entity does not). */
  def put(scenario: StoredScenario): Effect[Done] =
    if context.entityId == "default" && !scenario.shipped then
      effects.error("the default scenario reproduces the stand-in and is not replaced", ErrorCode.Conflict)
    else effects.updateState(Some(scenario)).thenReply(_ => Done)

  /** A shipped file's seed: written only when newer than what is stored. */
  def seed(scenario: StoredScenario): Effect[Boolean] =
    currentState match
      case Some(stored) if stored.version >= scenario.version => effects.reply(false)
      case _                                                    => effects.updateState(Some(scenario)).thenReply(_ => true)

  def get: ReadOnlyEffect[StoredScenario] =
    currentState.fold(effects.error[StoredScenario](s"scenario '${context.entityId}' not found", ErrorCode.NotFound))(effects.reply(_))

object ScenarioEntity
    extends KeyValueEntity.Companion[ScenarioEntity, Option[StoredScenario]](
      componentId = ComponentId("scenario"),
      stateSerializer = Codecs.serializer[Option[StoredScenario]]("scenario")
    ):

  given Serializer[StoredScenario] = Codecs.serializer[StoredScenario]("stored-scenario")

  def create(context: KeyValueEntityContext) = new ScenarioEntity(context)

  val put  = command("put")(_.put)
  val seed = command("seed")(_.seed)
  val get  = query("get")(_.get)

/** A brand's default scenario (FR-016), set by an operator; `default` until then. */
final class BrandDefaults extends KeyValueEntity[String]:
  def emptyState: String = "default"
  def set(name: String): Effect[Done] = effects.updateState(name).thenReply(_ => Done)
  def get: ReadOnlyEffect[String]     = effects.reply(currentState)

object BrandDefaults
    extends KeyValueEntity.Companion[BrandDefaults, String](
      componentId = ComponentId("brand-defaults"),
      stateSerializer = Codecs.serializer[String]("brand-default")
    ):
  def create(context: KeyValueEntityContext) = new BrandDefaults
  val set = command("set")(_.set)
  val get = query("get")(_.get)

/** The load profile a brand last started, so a second is refused while the first runs (FR-025). */
final class ActiveProfile extends KeyValueEntity[String]:
  def emptyState: String = ""
  def set(id: String): Effect[Done] = effects.updateState(id).thenReply(_ => Done)
  def get: ReadOnlyEffect[String]   = effects.reply(currentState)

object ActiveProfile
    extends KeyValueEntity.Companion[ActiveProfile, String](
      componentId = ComponentId("active-profile"),
      stateSerializer = Codecs.serializer[String]("active-profile")
    ):
  def create(context: KeyValueEntityContext) = new ActiveProfile
  val set = command("set")(_.set)
  val get = query("get")(_.get)

/** What the simulator says about itself (FR-022): runs swept and when. Keys and brands are read live. */
final case class SimulatorStatus(sweptRuns: Long, lastSweepAt: Option[Long])

final class SimulatorStatusEntity extends KeyValueEntity[SimulatorStatus]:
  def emptyState: SimulatorStatus = SimulatorStatus(0L, None)
  def swept(sweep: SimulatorStatusEntity.Swept): Effect[SimulatorStatus] =
    effects.updateState(SimulatorStatus(currentState.sweptRuns + sweep.runs, Some(sweep.at))).thenReply(identity)
  def get: ReadOnlyEffect[SimulatorStatus] = effects.reply(currentState)

object SimulatorStatusEntity
    extends KeyValueEntity.Companion[SimulatorStatusEntity, SimulatorStatus](
      componentId = ComponentId("simulator-status"),
      stateSerializer = Codecs.serializer[SimulatorStatus]("simulator-status")
    ):
  final case class Swept(runs: Long, at: Long)
  given Serializer[Swept]           = Codecs.serializer[Swept]("simulator-swept")
  given Serializer[SimulatorStatus] = Codecs.serializer[SimulatorStatus]("simulator-status")
  def create(context: KeyValueEntityContext) = new SimulatorStatusEntity
  val swept = command("swept")(_.swept)
  val get   = query("get")(_.get)
  val Id    = "status"
