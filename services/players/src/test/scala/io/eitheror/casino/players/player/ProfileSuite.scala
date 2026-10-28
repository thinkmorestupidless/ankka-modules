package io.eitheror.casino.players.player

import io.eitheror.casino.contract.personal.Personal

import java.time.LocalDate

/** FR-024: the lock-once and clearable fields, what an edit changed, and the 18+ gate. */
class ProfileSuite extends munit.FunSuite:

  private def profile(fields: (String, String)*) = Profile(fields.map((k, v) => k -> Personal(v)).toMap)

  private def edit(p: Profile, fields: (String, Option[String])*) = Profile(p, fields.toMap, Profile.lockedIn(p))

  test("each lock-once field locks on its first value and ignores the next edit") {
    Profile.lockOnce.foreach { field =>
      val first = edit(Profile(), field -> Some("one"))
      assertEquals(first.profile.get(field), Some("one"), field)
      assertEquals(first.changed, Set(field))
      assert(first.locked(field))
      val second = Profile(first.profile, Map(field -> Some("two")), first.locked)
      assertEquals(second.profile.get(field), Some("one"), field)
      assertEquals(second.changed, Set.empty[String])
    }
  }

  test("each clearable field clears on an empty or null value") {
    Profile.clearable.foreach { field =>
      val held = profile(field -> "value")
      assertEquals(edit(held, field -> Some("")).profile.get(field), None, field)
      assertEquals(edit(held, field -> None).profile.get(field), None, field)
      assertEquals(edit(held, field -> None).changed, Set(field))
    }
  }

  test("a field neither clearable nor locked keeps its value on an empty or null value") {
    val held = profile("firstName" -> "Ada", "phone" -> "+44 20 7946 0000")
    val after = edit(held, "firstName" -> Some(""), "phone" -> None)
    assertEquals(after.profile, held)
    assertEquals(after.changed, Set.empty[String])
  }

  test("changed names exactly what changed: a resave of the same value and a field not editable are not changes") {
    val held  = profile("firstName" -> "Ada", "lastName" -> "Lovelace", "displayName" -> "ada")
    val after = edit(held, "firstName" -> Some(" Ada "), "lastName" -> Some("Byron"), "displayName" -> Some("other"), "nonsense" -> Some("x"))
    assertEquals(after.changed, Set("lastName"))
    assertEquals(after.profile.get("displayName"), Some("ada"))
    assert(!after.profile.fields.contains("nonsense"))
  }

  test("17 years and 364 days is underage, 18 years is not") {
    val today = LocalDate.of(2026, 10, 25)
    assert(Profile.underage(LocalDate.of(2008, 10, 26), today))
    assert(!Profile.underage(LocalDate.of(2008, 10, 25), today))
    assertEquals(Profile.age(LocalDate.of(2008, 10, 25), today), 18)
    assertEquals(Profile.age(LocalDate.of(2008, 10, 26), today), 17)
  }

  test("a date is read as yyyy-MM-dd only") {
    assertEquals(Profile.date("1990-12-10"), Some(LocalDate.of(1990, 12, 10)))
    assertEquals(Profile.date("10/12/1990"), None)
    assertEquals(Profile.date("1990-02-30"), None)
  }

  test("an erased profile holds nothing") {
    assertEquals(profile("firstName" -> "Ada").erased.fields, Map.empty[String, Personal[String]])
  }
