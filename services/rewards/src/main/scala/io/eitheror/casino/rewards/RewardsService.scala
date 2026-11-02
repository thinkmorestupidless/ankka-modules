package io.eitheror.casino.rewards

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime}

/** Every component of the service (plan.md's structure), registered by the service and by its test kit alike. */
object RewardsService:
  val descriptors: Seq[ComponentDescriptor] = Seq(
    steps.WalletStep.descriptor,
    steps.FreeRounds.descriptor,
    reward.Reward.descriptor,
    reward.RewardClocks.descriptor,
    reward.ClockTimer.descriptor,
    reads.RewardsView.descriptor,
    facts.RewardsPublisher.descriptor,
    facts.CodesPublisher.descriptor,
    facts.AuditPublisher.descriptor,
    offers.OfferBook.descriptor,
    facts.OfferAuditPublisher.descriptor,
    player.PlayerRewards.descriptor,
    consumers.MoneyConsumer.descriptor,
    consumers.IndexConsumer.descriptor,
    consumers.PlayersConsumer.descriptor,
    consumers.RoundsConsumer.descriptor,
    codes.CodeDocument.descriptor,
    codes.CodesTimer.descriptor
  )

  /** One endpoint per prefix (contracts/routes.md), none sharing one; each user story adds its own. */
  def endpoints(authenticate: () => Acl): Seq[EndpointClients => HttpEndpoint] = Seq(
    clients => api.RewardsEndpoint(api.Ctx(authenticate, clients)),
    clients => api.OffersEndpoint(api.Ctx(authenticate, clients)),
    clients => api.RewardAdminEndpoint(api.Ctx(authenticate, clients))
  )

  /**
   * Once the service has started: the timers reachable from the components, the wallet steps wired to `Reward`, and the
   * programme's code document refreshed by `codes-<slug>` when there is one to read.
   */
  def started(timers: TimerRuntime): Unit =
    RewardsRuntime.timers = () => timers.timerScheduler
    steps.Wiring.install()
    codes.CodesRefresh.schedule(timers.timerScheduler)

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  RewardsRuntime.wire(settings)
  val timers = TimerRuntime()
  // A local run with no broker reads and publishes in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      org.slf4j.LoggerFactory.getLogger("rewards")
        .warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are read and published in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val service = Ankka.service
    .registerAll(RewardsService.descriptors)
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(RewardsService.endpoints(() => Oidc.authenticate())*))
    .start(name = "rewards")
  RewardsService.started(timers)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
