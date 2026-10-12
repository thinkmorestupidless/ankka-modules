package io.eitheror.casino.gameprovider

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * FR-029's two metrics. ankka 0.11.0 exports only its runtime's own metrics and offers a service no
 * meter (research R16, verified at task time), so the service keeps its counter itself and renders both
 * in Prometheus' text format on an operator route; the gauge is read from the unacknowledged view when
 * rendered, so it survives a restart. The counter restarts at zero with the process, as a Prometheus
 * counter may.
 */
object Metrics:
  val BetsTotal       = "eitheror_casino_bets_total"
  val Unacknowledged  = "eitheror_casino_unacknowledged_callbacks"

  private val bets = ConcurrentHashMap[String, LongAdder]()

  /** One bet placed, free rounds included (the original's `incrementBetsPlaced`). */
  def betPlaced(brand: String): Unit = bets.computeIfAbsent(brand, _ => LongAdder()).increment()

  def betsPlaced(brand: String): Long = Option(bets.get(brand)).map(_.sum).getOrElse(0L)

  def render(brand: String, unacknowledged: Long): String =
    s"""# HELP $BetsTotal Bets placed with the wallet for the provider's debits, free rounds included.
       |# TYPE $BetsTotal counter
       |$BetsTotal{brand_id="$brand"} ${betsPlaced(brand)}
       |# HELP $Unacknowledged Provider callbacks the wallet did not acknowledge, still open.
       |# TYPE $Unacknowledged gauge
       |$Unacknowledged{brand_id="$brand"} $unacknowledged
       |""".stripMargin
