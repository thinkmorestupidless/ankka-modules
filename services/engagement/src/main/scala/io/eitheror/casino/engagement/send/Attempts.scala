package io.eitheror.casino.engagement.send

import java.time.Duration
import scala.util.Random

/**
 * Research R5's attempts: four in all, the waits between them `base` doubling (500 ms, 1 s, 2 s) with ±25 %
 * jitter; after the fourth failure the send is parked for an operator.
 */
object Attempts:
  /** The wait after the `failed`th failure (1-based), jittered by `jitter` in [-1, 1]. */
  def delay(base: Duration, failed: Int, jitter: Double): Duration =
    val nominal = base.toMillis * (1L << math.max(0, math.min(failed - 1, 20)))
    Duration.ofMillis(math.max(1L, math.round(nominal * (1.0 + 0.25 * math.max(-1.0, math.min(1.0, jitter))))))

  def delay(base: Duration, failed: Int): Duration = delay(base, failed, Random.between(-1.0, 1.0))

  /** Whether a call that has failed `failed` times is parked rather than tried again. */
  def parked(failed: Int, attempts: Int): Boolean = failed >= attempts
