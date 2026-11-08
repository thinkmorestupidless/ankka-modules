package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.{DataSubject, KeyAnswer, Keyring, Personal}
import io.eitheror.casino.contract.personal.Personal as Marked

import scala.util.Try

/**
 * A fact's personal field as this service keeps it (research R10): the contract's marker read with `reveal` and held
 * as ankka's `Personal` under `player/<id>`, so the store seals it and an erasure leaves it reading `Erased`.
 */
object Personals:
  def subject(playerId: String): DataSubject = DataSubject(s"player/$playerId")

  /**
   * None for an absent or empty value; `Erased` for a player already erased. A fact read again after an erasure (a
   * rebuild, research R8) still carries the value, and a write of it is refused (`Conflict`, "nothing was written"),
   * which would stop the topic's intake for good; kept as erased, it folds as the original fact did, without it. The
   * keyring is asked without making a key, and a keyring that cannot answer leaves the decision to the write.
   */
  def of(playerId: String, value: Marked[String]): Option[Personal[String]] =
    Option(value.reveal).filter(_.nonEmpty).map { v =>
      val s = subject(playerId)
      if erased(s) then Personal.Erased(s) else Personal(s, v)
    }

  private def erased(s: DataSubject): Boolean =
    Try(Keyring.current).flatMap(k => Try(k.key(k.project, s, create = false))).toOption.contains(KeyAnswer.Destroyed)

  def of(playerId: String, value: Option[Marked[String]]): Option[Personal[String]] = value.flatMap(of(playerId, _))

  /** What a report shows of a personal field (FR-025): its value, `[erased]` once its subject is erased, empty when absent. */
  def shown(field: Option[Personal[String]]): String = field match
    case Some(Personal.Present(_, v)) if v != null => v
    case Some(Personal.Erased(_))                  => Erased
    case _                                         => ""

  /** How an erased value reads in a report and an export (research R10). */
  val Erased = "[erased]"

  /** A personal field's value: none when absent, empty or erased. */
  def reveal(field: Option[Personal[String]]): Option[String] =
    field.collect { case Personal.Present(_, v) if v != null && v.nonEmpty => v }
