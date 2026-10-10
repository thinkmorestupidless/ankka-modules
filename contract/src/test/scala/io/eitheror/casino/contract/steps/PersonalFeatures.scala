package io.eitheror.casino.contract.steps

import com.thinkmorestupidless.ankka.testkit.GherkinSuite
import io.eitheror.casino.contract.facts.{FieldType, Json, PlayerProfileUpdated, PlayerSessionStarted, FamilyDef, Topics}
import io.eitheror.casino.contract.personal.{PersonalFields, PlayerSubject}

/**
 * features/brands/personal-data.feature: the marking is the contract's and is bound here. Erasure
 * itself is ankka 042, specified and not built; its scenarios run in ErasureSuite once it ships.
 */
class PersonalFeatures extends GherkinSuite("features/brands/personal-data.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "an erased player's personal fields read as erased and every amount, id and time reads as before" -> "ErasureSuite, once ankka 042 erases",
    "the shared payments holds a player's id and the fields a payment provider requires, the shared audit the id alone, and the programme no id" -> "the merchant's (spec 004) and the shared audit's (spec 018) suites; the programme and audit halves are PersonalSuite's",
    "an erasure requested under a retention hold waits for the hold to lapse and is then applied unasked" -> "ErasureSuite, once ankka 042 holds and applies requests"
  )

  private def marked(t: FieldType[?]): Boolean = t.schema match
    case Json.Obj(fs) => fs.contains("x-personal" -> Json.Bool(true))
    case _            => false

  private var families = Vector.empty[FamilyDef[?]]
  private var kept     = Vector.empty[(String, FieldType[?])]

  Given("a registered player") { () =>
    families = Topics.all.flatMap(_.families)
  }

  When("the brand keeps their name, email address, telephone number, postal address, date of birth, identity documents or network address") { () =>
    kept = families.flatMap(_.fields).filter((name, _) => PersonalFields.names.contains(name))
    assert(kept.map(_._1).toSet.intersect(Set("given_name", "family_name", "telephone", "date_of_birth", "network_address")).size == 5,
      s"the contract's facts carry these fields: ${kept.map(_._1).distinct}")
  }

  Then("it keeps each as personal data under the player") { () =>
    kept.foreach((name, t) => assert(marked(t), s"$name is not personal"))
  }

  Given("a fact about a player") { () =>
    families = Vector(FamilyDef.of[PlayerSessionStarted]("player_session_started"), FamilyDef.of[PlayerProfileUpdated]("player_profile_updated"))
  }

  When("it carries a personal field") { () =>
    kept = families.flatMap(_.fields).filter((_, t) => marked(t))
    assert(kept.nonEmpty)
  }

  Then("the field is personal data under the player in the fact too") { () =>
    assert(families.forall(_.fields.exists(_._1 == "player_id")), "a fact with a personal field names the player it is held under")
    assertEquals(PlayerSubject("p1").id, "player/p1")
    kept.foreach((name, t) => assert(marked(t), name))
  }
