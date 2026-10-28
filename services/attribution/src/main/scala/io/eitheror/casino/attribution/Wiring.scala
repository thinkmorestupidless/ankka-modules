package io.eitheror.casino.attribution

import io.eitheror.casino.attribution.facts.Facts.ClosedDay
import io.eitheror.casino.attribution.money.DayRow
import io.eitheror.casino.attribution.networks.{DocumentFetch, NetworkState}

import java.util.concurrent.atomic.AtomicReference

/**
 * What a consumer is constructed without: the brand, and a reading of the closed days a player had
 * before a later binding. A consumer has no view client, and its companion constructs it, so
 * `AttributionService` sets these before registering (as `game-provider`'s `RefreshRuntime` is wired),
 * and the suites set fakes.
 */
object Wiring:
  val brand: AtomicReference[String] = AtomicReference(sys.env.getOrElse("CASINO_BRAND", ""))

  /** A player's closed days, from `OpenDays.closedFor`. */
  val closedDays: AtomicReference[String => Vector[ClosedDay]] = AtomicReference(_ => Vector.empty)

  /** The ids of a network's active webhook subscriptions, from `Subscriptions.activeFor`. */
  val activeSubscriptions: AtomicReference[String => Vector[String]] = AtomicReference(_ => Vector.empty)

  /** The service's settings, for the timed actions. */
  val settings: AtomicReference[Settings] = AtomicReference(Settings(brand.get()))

  /** The open tallies of days before a day, from `OpenDays.openBefore`. */
  val openBefore: AtomicReference[String => Vector[DayRow]] = AtomicReference(_ => Vector.empty)

  /** Every network, from `Networks`. */
  val networks: AtomicReference[() => Vector[NetworkState]] = AtomicReference(() => Vector.empty)

  /** How documents are fetched: over HTTP in a cluster, `FakeNetwork` in the suites. */
  val fetch: AtomicReference[DocumentFetch] = AtomicReference(DocumentFetch.http(java.time.Duration.ofSeconds(10)))

  /** The clock documents are judged fresh against: the system's in a cluster, the kit's movable one in the suites. */
  val clock: AtomicReference[java.time.Clock] = AtomicReference(java.time.Clock.systemUTC())
