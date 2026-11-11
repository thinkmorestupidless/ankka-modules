package io.eitheror.casino.support

import com.thinkmorestupidless.ankka.agent.{AnthropicProvider, Json as AgentJson, ModelProvider, TestModelProvider}
import com.thinkmorestupidless.ankka.agent.judgment.JudgmentProvider
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.clients.Neighbours

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*

/**
 * What the agent and the endpoints share that is the service's: the settings, the clock, the model for a name, the
 * judge, and the neighbours over a service client. A suite wires its own: a scripted model for every name, a judge
 * it scripts, and a clock it moves.
 */
object SupportRuntime:
  @volatile var settings: Settings                 = Settings()
  @volatile var now: () => Instant                 = () => Instant.now()
  @volatile var judge: Option[JudgmentProvider]    = None
  @volatile var neighbours: ServiceClients => Neighbours = services => Neighbours.http(services, settings)

  /** The timer scheduler, set once the service's timer runtime is built (the forget timer, FR-027). */
  @volatile var timers: () => com.thinkmorestupidless.ankka.sdk.TimerScheduler =
    () => throw IllegalStateException("the timers are not wired: the service sets SupportRuntime.timers at start")

  /** The provider that answers a turn on `model` (FR-008): one per name, built from the settings' source. */
  @volatile var model: String => ModelProvider = models(Settings().model)

  def wire(s: Settings, clock: () => Instant = () => Instant.now()): Unit =
    settings = s
    now = clock
    model = models(s.model)
    neighbours = services => Neighbours.http(services, settings)

  /** The summariser compaction asks (R2): the source's provider, on the configuration's default model. */
  def summariser: ModelProvider = model(config.ConfigBody().model)

  private def models(source: ModelSource): String => ModelProvider = source match
    case ModelSource.Anthropic(key) =>
      val built = TrieMap.empty[String, ModelProvider]
      name => built.getOrElseUpdate(name, AnthropicProvider.withApiKey(key.value, name))
    case ModelSource.Scripted(file) =>
      lazy val scripted = Scripted.load(file)
      _ => scripted

/**
 * `AGENT_MODEL_PROVIDER=scripted:<file>` (research R14): a local run with no key. The file is a JSON array of
 * replies, answered in order, each `{"text": "…"}` or `{"tool": "<name>", "arguments": {…}}`.
 */
object Scripted:
  def load(file: String): ModelProvider =
    val provider = TestModelProvider(name = "scripted")
    if file.nonEmpty then
      val text = Files.readString(Path.of(file))
      Json.parse(text).filter(_.isArray).getOrElse(throw IllegalArgumentException(s"$file: not a JSON array of replies"))
        .elements.asScala.zipWithIndex.foreach { (reply, i) =>
          reply.text("text") match
            case Some(t) => provider.expectText(t): Unit
            case None =>
              val tool = reply.text("tool").getOrElse(throw IllegalArgumentException(s"$file: reply $i has no text or tool"))
              val args = Option(reply.get("arguments")).map(Json.render).getOrElse("{}")
              provider.expectToolCall(tool, AgentJson.parse(args).fold(e => throw IllegalArgumentException(s"$file: $e"), identity),
                callId = s"scripted-$i"): Unit
        }
    provider
