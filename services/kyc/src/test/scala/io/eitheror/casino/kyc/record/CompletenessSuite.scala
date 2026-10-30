package io.eitheror.casino.kyc.record

import io.eitheror.casino.kyc.record.ProfileField.*

import java.time.LocalDate

/**
 * The profile rows of the original's `rollup_test.go`, named after their Go tests, and R11's own: the
 * completeness is checked against today, so a player who turns eighteen completes with no new fact.
 */
class CompletenessSuite extends munit.FunSuite:
  private val today = LocalDate.parse("2026-10-05")

  private val complete: Map[ProfileField, String] = Map(
    FirstName    -> "Alice",
    LastName     -> "Smith",
    DateOfBirth  -> "1990-01-15",
    Phone        -> "+1-555-000-0001",
    AddressLine1 -> "1 Main Street",
    City         -> "Montreal",
    PostalCode   -> "H1A 1A1",
    Country      -> "CA"
  )

  private def missing(values: Map[ProfileField, String], on: LocalDate = today): Vector[ProfileField] =
    val facts = Completeness.facts(values.map((f, v) => f -> Option(v)))
    Completeness.missing(facts.present, facts.adultFrom, on)

  test("IsProfileCompleteAllFieldsPresent"):
    assertEquals(missing(complete), Vector.empty)

  test("IsProfileCompleteBlankOrEmptyField"):
    for v <- Vector("", "   ") do assertEquals(missing(complete.updated(City, v)), Vector(City), s"'$v'")

  test("MissingProfileFieldsAllInOrder"):
    assertEquals(missing(Map.empty), Vector(FirstName, LastName, DateOfBirth, Phone, AddressLine1, City, PostalCode, Country))
    assertEquals(
      ProfileField.values.toVector.map(_.wire),
      Vector("firstName", "lastName", "dateOfBirth", "phone", "addressLine1", "city", "postalCode", "country")
    )

  // The original's snake_case keys are its twin's; the players fact names each field once. What stays is
  // that a field is read once, trimmed, and the date of birth from its first ten characters.
  test("ProfileFieldsFallBackToSnakeCase"):
    assertEquals(missing(complete.updated(FirstName, "  Alice ").updated(DateOfBirth, "1990-01-15T00:00:00Z")), Vector.empty)

  test("DateOfBirthValidation"):
    val rows = Vector(
      "1990-01-15"           -> true,
      "1990-01-15T00:00:00Z" -> true,
      "2008-10-05"           -> true,
      "2008-10-06"           -> false,
      "not-a-date"           -> false,
      "1990-13-01"           -> false,
      "1990"                 -> false
    )
    for (dob, valid) <- rows do assertEquals(missing(complete.updated(DateOfBirth, dob)).isEmpty, valid, dob)

  test("DateOfBirthLeapDay"):
    val leap = complete.updated(DateOfBirth, "2008-02-29")
    assertEquals(missing(leap, LocalDate.parse("2026-02-28")), Vector(DateOfBirth))
    assertEquals(missing(leap, LocalDate.parse("2026-03-01")), Vector.empty)

  test("a player who turns eighteen completes with no new fact"):
    val facts = Completeness.facts(complete.updated(DateOfBirth, "2008-10-06").map((f, v) => f -> Option(v)))
    assertEquals(Completeness.missing(facts.present, facts.adultFrom, today), Vector(DateOfBirth))
    assertEquals(Completeness.missing(facts.present, facts.adultFrom, today.plusDays(1)), Vector.empty)

  test("an absent value is missing; the date of birth's value is kept only as the day the player turns eighteen"):
    val facts = Completeness.facts(Map(Phone -> None, DateOfBirth -> Some("1990-01-15")))
    assertEquals(facts.present, Set(DateOfBirth))
    assertEquals(facts.adultFrom.map(_.toString), Some("2008-01-15"))
