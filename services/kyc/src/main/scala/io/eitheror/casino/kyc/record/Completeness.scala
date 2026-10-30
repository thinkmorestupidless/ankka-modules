package io.eitheror.casino.kyc.record

import io.eitheror.casino.contract.personal.Personal

import java.time.LocalDate
import scala.util.Try

/** The eight fields the profile needs for verification (FR-016), in the order and by the names the player is told. */
enum ProfileField(val wire: String):
  case FirstName    extends ProfileField("firstName")
  case LastName     extends ProfileField("lastName")
  case DateOfBirth  extends ProfileField("dateOfBirth")
  case Phone        extends ProfileField("phone")
  case AddressLine1 extends ProfileField("addressLine1")
  case City         extends ProfileField("city")
  case PostalCode   extends ProfileField("postalCode")
  case Country      extends ProfileField("country")

/** What the record keeps of a profile (R11): which fields are present, and the day the player turns eighteen. */
final case class Presence(present: Set[ProfileField], adultFrom: Option[Personal[LocalDate]])

/**
 * The profile's completeness without the profile (R11), ported from the original's
 * `missingProfileFields`: a field is missing when blank after trimming, and the date of birth also when
 * its first ten characters are not a `yyyy-MM-dd` date at least eighteen whole years before today. The
 * age is checked against today on every derivation, not when the fact arrived.
 */
object Completeness:

  /** The presence of each field from a profile fact's values; nothing of a value is kept but the adult date. */
  def facts(values: Map[ProfileField, Option[String]]): Presence =
    val present = values.collect { case (f, Some(v)) if v.trim.nonEmpty => f }.toSet
    Presence(present, values.get(ProfileField.DateOfBirth).flatten.flatMap(adultFrom).map(Personal(_)))

  /**
   * The day the player turns eighteen, as `java.time.Period.between` counts whole years: a 29 February
   * birthday turns eighteen on 1 March in a year that is not a leap year.
   */
  def adultFrom(dateOfBirth: String): Option[LocalDate] =
    val text = dateOfBirth.trim
    Option.when(text.length >= 10)(text.take(10)).flatMap(d => Try(LocalDate.parse(d)).toOption).map { born =>
      val eighteen = born.plusYears(18)
      if eighteen.getDayOfMonth < born.getDayOfMonth then eighteen.plusDays(1) else eighteen
    }

  /** The fields missing on `today`, in `ProfileField`'s order. */
  def missing(present: Set[ProfileField], adultFrom: Option[Personal[LocalDate]], today: LocalDate): Vector[ProfileField] =
    ProfileField.values.toVector.filter {
      case ProfileField.DateOfBirth => !present(ProfileField.DateOfBirth) || adultFrom.forall(a => today.isBefore(a.reveal))
      case f                        => !present(f)
    }

  def complete(present: Set[ProfileField], adultFrom: Option[Personal[LocalDate]], today: LocalDate): Boolean =
    missing(present, adultFrom, today).isEmpty
