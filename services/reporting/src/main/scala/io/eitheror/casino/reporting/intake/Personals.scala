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

  /** A personal field's value: none when absent, empty or erased. */
  def reveal(field: Option[Personal[String]]): Option[String] =
    field.collect { case Personal.Present(_, v) if v != null && v.nonEmpty => v }
