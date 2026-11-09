package io.eitheror.casino.support.config

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.support.{Stored, SupportCodecs}
import io.eitheror.casino.support.domain.ConfigRules

/**
 * A brand's agent configuration (key value, id = slug; data-model.md): the current version, its body, and the last
 * [[AgentConfigState.Kept]] change notes for the audit, as engagement's `Configuration`. It moves only to the next
 * version, after that version's body is in `ConfigVersion`, so a conversation always finds the version it names.
 */
final class AgentConfiguration(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[AgentConfigState]:
  def emptyState: AgentConfigState = AgentConfigState()

  def move(m: Move): Effect[Moved] =
    if m.version != currentState.version + 1 then effects.reply(Moved(currentState, stale = true))
    else
      val recent = (currentState.recentChanges :+ m.note).takeRight(AgentConfigState.Kept)
      effects.updateState(AgentConfigState(m.version, m.body, recent)).thenReply(s => Moved(s))

  def get: ReadOnlyEffect[AgentConfigState] = effects.reply(currentState)

object AgentConfiguration
    extends KeyValueEntity.Companion[AgentConfiguration, AgentConfigState](
      componentId = ComponentId("support-configuration"),
      stateSerializer = Stored.configuration
    ):
  given Serializer[AgentConfigState] = SupportCodecs.serializer[AgentConfigState]("configuration-reply")
  given Serializer[Move]             = SupportCodecs.serializer[Move]("configuration-move")
  given Serializer[Moved]            = SupportCodecs.serializer[Moved]("configuration-moved")

  def id(slug: String): EntityId = EntityId(slug)

  def create(context: KeyValueEntityContext) = new AgentConfiguration(context)

  val move = command("move")(_.move)
  val get  = query("get")(_.get)

  /**
   * A `put` (contracts/routes.md): nothing changed only replies; otherwise the next version's body is written to
   * `ConfigVersion`, then the configuration moves to it. Another put that moved first is read again, once.
   */
  def put(components: ComponentClient, slug: String, change: ConfigChange): Put =
    def attempt(retried: Boolean): Put =
      val now = components.forKeyValueEntity(id(slug)).call(get).invoke()
      val (fields, values) = ConfigRules.changes(now.current, change.body)
      if fields.isEmpty then Put(now.version, Vector.empty)
      else
        val v = now.version + 1
        val written = components.forKeyValueEntity(ConfigVersion.id(slug, v)).call(ConfigVersion.write).invoke(change.body)
        val moved =
          if written != change.body then Moved(now, stale = true)
          else components.forKeyValueEntity(id(slug)).call(move).invoke(Move(v, change.body, ConfigNote(v, change.by, fields, values, change.at)))
        if !moved.stale then Put(v, fields)
        else if retried then throw IllegalStateException("the configuration was changed by another put; try again")
        else attempt(retried = true)
    attempt(retried = false)

/** One version's body (key value, id = `<slug>/<version>`): written once, before the configuration moves to it. */
final class ConfigVersion(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[VersionState]:
  def emptyState: VersionState = VersionState()

  /** The body kept: the first written, never changed. */
  def write(body: ConfigBody): Effect[ConfigBody] = currentState.body match
    case Some(kept) => effects.reply(kept)
    case None       => effects.updateState(VersionState(Some(body))).thenReply(_ => body)

  def get: ReadOnlyEffect[VersionState] = effects.reply(currentState)

object ConfigVersion
    extends KeyValueEntity.Companion[ConfigVersion, VersionState](
      componentId = ComponentId("support-config-version"),
      stateSerializer = Stored.configVersion
    ):
  given Serializer[ConfigBody]   = SupportCodecs.serializer[ConfigBody]("config-body")
  given Serializer[VersionState] = SupportCodecs.serializer[VersionState]("config-version-reply")

  def id(slug: String, version: Int): EntityId = EntityId(s"$slug/$version")

  def create(context: KeyValueEntityContext) = new ConfigVersion(context)

  val write = command("write")(_.write)
  val get   = query("get")(_.get)

  /** The body a conversation on `version` reads; version 0 is the defaults, which are never written. */
  def body(components: ComponentClient, slug: String, version: Int): ConfigBody =
    if version == 0 then ConfigBody()
    else components.forKeyValueEntity(id(slug, version)).call(get).invoke().body.getOrElse(ConfigBody())
