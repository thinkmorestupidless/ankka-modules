package io.eitheror.casino.support

import com.thinkmorestupidless.ankka.agent.{AgentRuntime, CompactionSettings, ModelProvider, ModelSummariser}
import com.thinkmorestupidless.ankka.agent.judgment.{JevProvider, JudgmentProvider}
import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime}

/** Every component of the service (plan.md's structure), registered by the service and by its test kit alike. */
object SupportService:
  /**
   * The agent runtime (R2, R8): compaction on the settings' two numbers, summarised by `summariser`, and the judge.
   * Each turn names its own model (FR-008); the default is the summariser's, which no turn uses.
   */
  def agents(s: Settings, summariser: ModelProvider, judge: Option[JudgmentProvider]): AgentRuntime =
    val base = AgentRuntime
      .withDefaultModel(summariser)
      .withCompaction(CompactionSettings(s.compactionBytes, s.compactionRecent), Some(ModelSummariser(summariser)))
    judge.fold(base)(base.withJudgments(_))

  def descriptors(agents: AgentRuntime): Seq[ComponentDescriptor] = Seq(
    conversation.Conversation.descriptor,
    config.AgentConfiguration.descriptor,
    config.ConfigVersion.descriptor,
    agent.Motoko.descriptor
  ) ++ agents.descriptors

  /** One endpoint per prefix (contracts/routes.md), none sharing one. */
  def endpoints(authenticate: () => Acl): Seq[EndpointClients => HttpEndpoint] = Seq(
    clients => api.ChatEndpoint(api.Ctx(authenticate, clients)),
    clients => api.ServiceEndpoint(api.Ctx(authenticate, clients))
  )

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  SupportRuntime.wire(settings)
  SupportRuntime.judge = settings.judge.map(_ => JevProvider.fromEnv())
  val log    = org.slf4j.LoggerFactory.getLogger("support-agent")
  val agents = SupportService.agents(settings, SupportRuntime.summariser, SupportRuntime.judge)
  // A local run with no broker publishes in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      log.warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are published in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val builder = Ankka.service
    .registerAll(SupportService.descriptors(agents))
    .withExtension(broker)
    // Approval time limits are timers (R7), and the forget timer (FR-027) is one.
    .withExtension(TimerRuntime())
    .withExtension(agents)
    .withExtension(HttpServer.of(SupportService.endpoints(() => Oidc.authenticate())*))
  // The installation names its keyring for a service with personal data; a local run keeps its keys in its own
  // database (feature 042).
  val keyed =
    if sys.env.get("ANKKA_KEYRING_ADDRESS").exists(_.trim.nonEmpty) then builder
    else
      log.warn("ANKKA_KEYRING_ADDRESS is not set: sessions are sealed under keys in this service's database (a local run)")
      builder.withLocalKeyring()
  val service = keyed.start(name = "support-agent")
  sys.addShutdownHook(service.terminate(): Unit)
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
