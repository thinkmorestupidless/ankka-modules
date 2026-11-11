package io.eitheror.casino.support.config

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.support.{Stored, SupportCodecs}
import io.eitheror.casino.support.domain.ConfigRules

import java.time.Instant

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

  /** A `put` of a whole body (the kit's, and a migration's). */
  def put(components: ComponentClient, slug: String, change: ConfigChange): Put =
    put[Nothing](components, slug, change.by, change.at)(_ => Right(change.body)).merge

  /** How many times a put is read again when other puts moved first. */
  val Attempts = 3

  /**
   * A `put` (contracts/routes.md): `next` makes the body from the current one, so a put read again after another
   * moved first changes only what it sent, over the other's (and is checked again). Nothing changed only replies;
   * otherwise the next version's body is written to `ConfigVersion`, then the configuration moves to it. A slot that
   * holds another put's body (one moving now, or one that failed before it moved) is moved to as that put made it,
   * with its author, and this put is read again over it, so an abandoned put never blocks the next.
   */
  def put[R](components: ComponentClient, slug: String, by: String, at: Instant)(
      next: ConfigBody => Either[R, ConfigBody]): Either[R, Put] =
    def moveTo(v: Int, from: ConfigBody, body: ConfigBody, author: String, when: Instant): Moved =
      val (fields, values) = ConfigRules.changes(from, body)
      components.forKeyValueEntity(id(slug)).call(move).invoke(Move(v, body, ConfigNote(v, author, fields, values, when)))
    def attempt(n: Int): Either[R, Put] =
      val now = components.forKeyValueEntity(id(slug)).call(get).invoke()
      next(now.current).flatMap { body =>
        val fields = ConfigRules.changes(now.current, body)._1
        if fields.isEmpty then Right(Put(now.version, Vector.empty))
        else
          val v     = now.version + 1
          val ours  = ConfigChange(body, by, at)
          val kept  = components.forKeyValueEntity(ConfigVersion.id(slug, v)).call(ConfigVersion.write).invoke(ours)
          val moved =
            if kept == ours then moveTo(v, now.current, body, by, at)
            else
              moveTo(v, now.current, kept.body, kept.by, kept.at): Unit
              Moved(now, stale = true)
          if !moved.stale then Right(Put(v, fields))
          else if n >= Attempts then throw IllegalStateException("the configuration was changed by other puts; try again")
          else attempt(n + 1)
      }
    attempt(1)

/** One version's body (key value, id = `<slug>/<version>`): written once, before the configuration moves to it. */
final class ConfigVersion(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[VersionState]:
  def emptyState: VersionState = VersionState()

  /** The change kept: the first written, never changed. */
  def write(c: ConfigChange): Effect[ConfigChange] = currentState.body match
    case Some(kept) => effects.reply(ConfigChange(kept, currentState.by.getOrElse(""), currentState.at.getOrElse(Instant.EPOCH)))
    case None       => effects.updateState(VersionState(Some(c.body), Some(c.by), Some(c.at))).thenReply(_ => c)

  def get: ReadOnlyEffect[VersionState] = effects.reply(currentState)

object ConfigVersion
    extends KeyValueEntity.Companion[ConfigVersion, VersionState](
      componentId = ComponentId("support-config-version"),
      stateSerializer = Stored.configVersion
    ):
  given Serializer[ConfigBody]   = SupportCodecs.serializer[ConfigBody]("config-body")
  given Serializer[VersionState] = SupportCodecs.serializer[VersionState]("config-version-reply")
  given Serializer[ConfigChange] = SupportCodecs.serializer[ConfigChange]("config-version-write")

  def id(slug: String, version: Int): EntityId = EntityId(s"$slug/$version")

  def create(context: KeyValueEntityContext) = new ConfigVersion(context)

  val write = command("write")(_.write)
  val get   = query("get")(_.get)

  /** The body a conversation on `version` reads; version 0 is the defaults, which are never written. */
  def body(components: ComponentClient, slug: String, version: Int): ConfigBody =
    if version == 0 then ConfigBody()
    else components.forKeyValueEntity(id(slug, version)).call(get).invoke().body.getOrElse(ConfigBody())
