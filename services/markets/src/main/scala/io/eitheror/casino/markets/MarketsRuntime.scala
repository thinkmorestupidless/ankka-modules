package io.eitheror.casino.markets

import com.thinkmorestupidless.ankka.sdk.ConsumerContext
import io.eitheror.casino.markets.drive.ExchangeCalls

import java.time.{Duration, Instant}

/**
 * What the components and endpoints are constructed without (ankka builds them): the settings and the
 * clock, set once at start by `MarketsService.wire`, and by the test kit with a clock it moves.
 */
object MarketsRuntime:
  @volatile private var current: Option[Settings] = None
  @volatile var now: () => Instant                = () => Instant.now()

  /** How the driver reaches `exchange`: its service client, as the brand's other services call theirs. */
  @volatile var exchange: ConsumerContext => ExchangeCalls = ctx => ExchangeCalls(ctx.services, settings.brand)

  def settings: Settings = current.getOrElse(throw IllegalStateException("markets is not wired: MarketsService.wire first"))

  def wire(s: Settings, clock: () => Instant = () => Instant.now()): Unit =
    current = Some(s)
    now = clock

  /** A resolution's or a void's review window: spec 002 FR-007's default (research, found in implementation). */
  val ReviewWindow: Duration = Duration.ofHours(24)
