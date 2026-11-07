package io.eitheror.casino.engagement.config

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.engagement.EngagementCodecs

/** A `put`'s answer: the configuration now, and the fields it changed. */
final case class Put(config: CrmConfig, changed: Vector[String])

/** The brand's Customer.io configuration (key value, id = slug; FR-004). Its secrets are not here. */
final class Configuration(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[CrmConfig]:
  def emptyState: CrmConfig = CrmConfig()

  def put(change: ConfigChange): Effect[Put] =
    val changed = currentState.changes(change.config) ++ change.keys
    if changed.isEmpty then effects.reply(Put(currentState, changed))
    else
      val at   = io.eitheror.casino.engagement.EngagementRuntime.now()
      val note = ConfigNote(currentState.lastChange.fold(1)(_.version + 1), change.by, changed, at)
      effects.updateState(change.config.copy(updatedAt = Some(at), lastChange = Some(note))).thenReply(c => Put(c, changed))

  def get: ReadOnlyEffect[CrmConfig] = effects.reply(currentState)

object Configuration
    extends KeyValueEntity.Companion[Configuration, CrmConfig](
      componentId = ComponentId("crm-configuration"),
      stateSerializer = EngagementCodecs.serializer[CrmConfig]("crm-config")
    ):
  given JsonValueCodec[CrmConfig] = EngagementCodecs.stored[CrmConfig]
  given Serializer[CrmConfig]     = EngagementCodecs.serializer[CrmConfig]("crm-config-reply")
  given Serializer[Put]           = EngagementCodecs.serializer[Put]("crm-config-put")
  given Serializer[ConfigChange]  = EngagementCodecs.serializer[ConfigChange]("crm-config-change")

  def create(context: KeyValueEntityContext) = new Configuration(context)

  val put = command("put")(_.put)
  val get = query("get")(_.get)
