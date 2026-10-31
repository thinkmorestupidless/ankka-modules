package io.eitheror.casino.exchange

/** A gate an order may run beside the account's status, which it always runs (spec 023 FR-002). */
enum Gate(val word: String):
  case PlayGate extends Gate("play-gate")
  case MayBet   extends Gate("may-bet")

/**
 * What `exchange` is told at start (spec 023 R7): the brand, and which of the play gate and
 * `may-bet` (which applies the player's limits) an order runs, both when the values name none.
 */
final case class Settings(brand: String, gates: Set[Gate] = Gate.values.toSet)

object Settings:
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val gates = env.get("PREDICTIONS_GATES").map(_.trim).fold(Right(Gate.values.toSet)) { v =>
      val words = v.split(',').toVector.map(_.trim).filter(_.nonEmpty)
      words.find(w => !Gate.values.exists(_.word == w)) match
        case Some(w) => Left(s"PREDICTIONS_GATES: '$w' is not a gate; the gates are ${Gate.values.map(_.word).mkString(", ")}")
        case None    => Right(Gate.values.filter(g => words.contains(g.word)).toSet)
    }
    for
      brand <- env.get("CASINO_BRAND").map(_.trim).filter(_.nonEmpty).toRight("CASINO_BRAND: not set; a brand service is deployed by the modules tool")
      gs    <- gates
    yield Settings(brand, gs)
