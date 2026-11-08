package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.{DataSubject, Personal}
import io.eitheror.casino.contract.personal.Personal as Marked

/**
 * A fact's personal field as this service keeps it (research R10): the contract's marker read with `reveal` and held
 * as ankka's `Personal` under `player/<id>`, so the store seals it and an erasure leaves it reading `Erased`.
 */
object Personals:
  def subject(playerId: String): DataSubject = DataSubject(s"player/$playerId")

  /** None for an absent or empty value. */
  def of(playerId: String, value: Marked[String]): Option[Personal[String]] =
    Option(value.reveal).filter(_.nonEmpty).map(Personal(subject(playerId), _))

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
