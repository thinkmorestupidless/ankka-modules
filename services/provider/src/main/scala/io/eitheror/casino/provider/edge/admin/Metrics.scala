package io.eitheror.casino.provider.edge.admin

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters.*

/**
 * FR-025's three metrics. ankka 0.13 exports only its runtime's own metrics and gives a service no
 * meter (`operate/telemetry.md`), so the provider keeps its counters itself, as `game-provider` does,
 * and renders them in Prometheus' text format on `GET /v1/provider/metrics`. The unanswered gauge is
 * read from the run view when rendered, so it survives a restart; the counters restart at zero with
 * the process, as a Prometheus counter may.
 */
object Metrics:
  val CallbacksSent       = "eitheror_games_callbacks_sent_total"
  val CallbacksUnanswered = "eitheror_games_callbacks_unanswered"
  val Launches            = "eitheror_games_launches_total"

  private val sent     = ConcurrentHashMap[(String, String), LongAdder]()
  private val launched = ConcurrentHashMap[(String, String), LongAdder]()

  /** A callback sent to a brand for the first time; a retry of it is not another callback. */
  def callbackSent(brand: String, kind: String): Unit = sent.computeIfAbsent((brand, kind), _ => LongAdder()).increment()

  def launch(brand: String, game: String): Unit = launched.computeIfAbsent((brand, game), _ => LongAdder()).increment()

  def sentCount(brand: String, kind: String): Long = Option(sent.get((brand, kind))).map(_.sum).getOrElse(0L)
  def launchCount(brand: String, game: String): Long = Option(launched.get((brand, game))).map(_.sum).getOrElse(0L)

  private def label(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

  def render(unanswered: Map[String, Long]): String =
    val sentLines = sent.asScala.toVector.sortBy(_._1).map { case ((b, k), n) => s"""$CallbacksSent{brand="${label(b)}",kind="${label(k)}"} ${n.sum}""" }
    val gauge     = unanswered.toVector.sorted.map((b, n) => s"""$CallbacksUnanswered{brand="${label(b)}"} $n""")
    val launches  = launched.asScala.toVector.sortBy(_._1).map { case ((b, g), n) => s"""$Launches{brand="${label(b)}",game="${label(g)}"} ${n.sum}""" }
    (Vector(
      s"# HELP $CallbacksSent Provider calls sent to a brand, per brand and kind; a retry is not counted again.",
      s"# TYPE $CallbacksSent counter") ++ sentLines ++ Vector(
      s"# HELP $CallbacksUnanswered Provider calls a brand has not acknowledged, waiting on a retry, exhausted or abandoned.",
      s"# TYPE $CallbacksUnanswered gauge") ++ gauge ++ Vector(
      s"# HELP $Launches Launches the house provider admitted, per brand and game.",
      s"# TYPE $Launches counter") ++ launches).mkString("", "\n", "\n")
