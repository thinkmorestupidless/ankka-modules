package io.eitheror.casino.reporting.api

import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.reporting.Settings

import java.time.{Instant, LocalDate}

/**
 * What a report's rows are read through, by a route and by a document job alike (research R9): the views, the
 * components, the settings and the clock. A route's come from its request's clients; a job's from `ReportingRuntime`,
 * since a workflow step has no view client.
 */
final class Reads(val views: ViewClient, val components: ComponentClient, val settings: Settings, val now: () => Instant):
  def generation: Int = settings.generation

  /** The first instant of a reporting day in the brand's zone. */
  def startOf(day: LocalDate): Instant = day.atStartOfDay(settings.reportingZone).toInstant
