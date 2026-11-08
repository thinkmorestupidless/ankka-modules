package io.eitheror.casino.programme

import io.eitheror.casino.programme.accounts.KeycloakAdmin
import io.eitheror.casino.programme.ledger.UnsettledRow

import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/**
 * What the consumers and timed actions are constructed without, as `attribution`'s `Wiring`: their
 * companions construct them, so `ProgrammeService` sets these before registering and the suites set fakes.
 */
object Wiring:
  val settings: AtomicReference[Settings] = AtomicReference(Settings())

  /** The clock facts are received by: the system's in a cluster, the kit's movable one in the suites. */
  val clock: AtomicReference[Clock] = AtomicReference(Clock.systemUTC())

  /** The waits between an email's attempts: EmailRules.Delays in a cluster; seconds in the suites, whose pauses are real time. */
  val emailDelays: AtomicReference[Vector[scala.concurrent.duration.FiniteDuration]] =
    AtomicReference(io.eitheror.casino.programme.comms.EmailRules.Delays)

  /** The realm `Provisioning` gives affiliates their sign-in and roles in. */
  val realm: AtomicReference[KeycloakAdmin] = AtomicReference(KeycloakAdmin.unconfigured)

  /** A brand's clicks carrying a click id (brand, click id): the `Clicks` view's, set once the service has started. */
  val clicksWith: AtomicReference[(String, String) => Vector[io.eitheror.casino.programme.players.ClickState]] =
    AtomicReference((_, _) => throw IllegalStateException("the views are not wired: ProgrammeService.wireViews"))

  /** The accounts a close owes (brand, period, after): the `Unsettled` view's, set once the service has started. */
  val owed: AtomicReference[(String, String, String) => Vector[UnsettledRow]] =
    AtomicReference((_, _, _) => throw IllegalStateException("the views are not wired: ProgrammeService.wireViews"))
