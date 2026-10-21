package io.eitheror.casino.merchant

import java.util.concurrent.atomic.AtomicLong

/**
 * The gauge `eitheror_payments_uncredited_captures` (FR-023): captured payments not credited past the
 * installation's bound, as `/status` last counted them. ankka 0.11.0 offers a service no meter (spec
 * 003 research R16), so the value is kept here and rendered as Prometheus text on the operators' route.
 */
object Metrics:
  private val uncreditedCaptures = AtomicLong(0)

  def uncredited(n: Long): Unit = uncreditedCaptures.set(n)
  def uncreditedNow: Long       = uncreditedCaptures.get

  def prometheus: String =
    s"""# HELP eitheror_payments_uncredited_captures Captured payments not credited past the installation's bound.
       |# TYPE eitheror_payments_uncredited_captures gauge
       |eitheror_payments_uncredited_captures ${uncreditedCaptures.get}
       |""".stripMargin
