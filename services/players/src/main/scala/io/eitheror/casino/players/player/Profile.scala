package io.eitheror.casino.players.player

import io.eitheror.casino.contract.personal.Personal

import java.time.{LocalDate, Period}
import scala.util.Try

/**
 * A player's profile (data-model.md; FR-023, FR-024): every field identifies a person, so every value is
 * `Personal`, kept under the original's camelCase wire name. A field with no value is absent.
 */
final case class Profile(fields: Map[String, Personal[String]] = Map.empty):
  def get(field: String): Option[String] = fields.get(field).map(_.reveal).filter(_.nonEmpty)
  def has(field: String): Boolean        = get(field).nonEmpty

  /** Every field emptied: what `Erased` leaves (data-model.md's invariant). */
  def erased: Profile = Profile()

object Profile:

  /** Every field, in the original's names. */
  val all: Vector[String] = Vector(
    "displayName", "firstName", "lastName", "dateOfBirth", "phone", "addressLine1", "city", "stateProv",
    "postalCode", "country", "language", "mobile", "province", "bankAccount", "institutionCode", "transitNumber"
  )

  /** What a player may edit on themselves (the original's `EditableProfileFields`). */
  val editable: Set[String] = Set(
    "firstName", "lastName", "phone", "addressLine1", "city", "stateProv", "postalCode", "dateOfBirth", "country",
    "mobile", "province", "bankAccount", "institutionCode", "transitNumber"
  )

  /** Set once, then kept against a player's edit (the original's `LockOnceProfileFields`). */
  val lockOnce: Set[String] = Set("dateOfBirth", "addressLine1", "city", "stateProv", "postalCode", "country")

  /** May be emptied again by an empty or null value (the original's `ClearableProfileFields`). */
  val clearable: Set[String] = Set("mobile", "province", "bankAccount", "institutionCode", "transitNumber")

  /** The fields KYC reads for completeness (the original's `ProfileCompleteness.RequiredFields`). */
  val required: Vector[String] = Vector("firstName", "lastName", "dateOfBirth", "phone", "addressLine1", "city", "postalCode", "country")

  /** The lock-once fields that hold a value in `p`. */
  def lockedIn(p: Profile): Set[String] = lockOnce.filter(p.has)

  /** What an edit made of the profile: the new profile, the fields it changed, and the fields now locked. */
  final case class Edited(profile: Profile, changed: Set[String], locked: Set[String])

  /**
   * A player's edit applied (FR-024): a field not editable is ignored; a locked field is dropped, not
   * refused; a value is trimmed and set when it differs; an empty or null value (`None`) empties a
   * clearable field and leaves any other unchanged. Nothing is refused here: the route answers
   * `no_fields_provided` when nothing changed and nothing was asked.
   */
  def apply(current: Profile, edit: Map[String, Option[String]], locked: Set[String]): Edited =
    val next = edit.foldLeft(current.fields) { case (acc, (field, value)) =>
      val text = value.map(_.trim).getOrElse("")
      if !editable(field) || locked(field) then acc
      else if text.nonEmpty then acc.updated(field, Personal(text))
      else if clearable(field) then acc - field
      else acc
    }
    val profile = Profile(next)
    val changed = all.filter(f => current.get(f) != profile.get(f)).toSet
    Edited(profile, changed, locked ++ lockedIn(profile))

  /** A date as the routes take one, `yyyy-MM-dd`. */
  def date(text: String): Option[LocalDate] = Try(LocalDate.parse(text.trim)).toOption

  /** Whole years from `dob` to `today`. */
  def age(dob: LocalDate, today: LocalDate): Int = Period.between(dob, today).getYears

  /** Younger than eighteen on `today`: eighteen years old today is not (the original's 18+ gate). */
  def underage(dob: LocalDate, today: LocalDate): Boolean = dob.isAfter(today.minusYears(18))
