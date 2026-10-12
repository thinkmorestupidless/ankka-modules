package io.eitheror.casino.simulator.core

import scala.concurrent.duration.*

/** Durations as scenario data writes them: `250ms`, `30s`, `10m`, `8h` (contracts/scenario.schema.json). */
object Durations:

  private val Pattern = """([0-9]+)(ms|s|m|h)""".r

  def parse(text: String): Either[String, FiniteDuration] = text match
    case Pattern(n, "ms") => Right(n.toLong.millis)
    case Pattern(n, "s")  => Right(n.toLong.seconds)
    case Pattern(n, "m")  => Right(n.toLong.minutes)
    case Pattern(n, "h")  => Right(n.toLong.hours)
    case other            => Left(s"'$other' is not a duration: a whole number and one of ms, s, m, h")

  def unsafe(text: String): FiniteDuration = parse(text).fold(e => throw IllegalArgumentException(e), identity)

  def show(d: FiniteDuration): String =
    if d.toMillis % 3600000 == 0 && d.toMillis > 0 then s"${d.toHours}h"
    else if d.toMillis % 60000 == 0 && d.toMillis > 0 then s"${d.toMinutes}m"
    else if d.toMillis % 1000 == 0 then s"${d.toSeconds}s"
    else s"${d.toMillis}ms"
