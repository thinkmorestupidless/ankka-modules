package io.eitheror.casino.simulator.core

import java.util.SplittableRandom
import scala.concurrent.duration.*

/**
 * Every outcome a run needs at random, from the run's seed (FR-004). A draw is a function of the
 * seed and the step it is for, never of how many draws came before, so a step that runs twice (a
 * workflow step is at-least-once) draws the same value, and two machines agree (SC-003).
 *
 * Draws are made by a command handler or a workflow step and written into the event; an entity's
 * `applyEvent` never draws (research R6).
 */
final case class Seeded(seed: Long):

  private def at(step: Int, salt: Int): SplittableRandom =
    SplittableRandom(seed ^ (step.toLong * 0x9E3779B97F4A7C15L) ^ (salt.toLong << 32))

  /** A decimal amount between `min` and `max` inclusive, two places, as the wire writes it. */
  def amount(step: Int, min: String, max: String): String =
    val lo = BigDecimal(min).setScale(2).bigDecimal.unscaledValue.longValue
    val hi = BigDecimal(max).setScale(2).bigDecimal.unscaledValue.longValue
    require(hi >= lo, s"amount range $min..$max is empty")
    val cents = if hi == lo then lo else lo + at(step, 1).nextLong(hi - lo + 1)
    BigDecimal(cents, 2).bigDecimal.toPlainString

  /** A duration between `min` and `max` inclusive, to the millisecond. */
  def duration(step: Int, min: FiniteDuration, max: FiniteDuration): FiniteDuration =
    val lo = min.toMillis
    val hi = max.toMillis
    require(hi >= lo, s"duration range $min..$max is empty")
    (if hi == lo then lo else lo + at(step, 2).nextLong(hi - lo + 1)).millis

  /** One of `n` choices. */
  def pick(step: Int, n: Int): Int =
    require(n > 0, "nothing to pick from")
    at(step, 3).nextInt(n)

  /** True with probability `percent` in 100. */
  def chance(step: Int, percent: Int): Boolean = at(step, 4).nextInt(100) < percent

  /** An id that is the same for the same seed and step: a UUID v4 in lower case, as the stub mints. */
  def id(step: Int, salt: Int = 0): String =
    val r = at(step, 5 + salt)
    val msb = (r.nextLong() & 0xffffffffffff0fffL) | 0x0000000000004000L
    val lsb = (r.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L
    java.util.UUID(msb, lsb).toString
