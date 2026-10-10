package io.eitheror.casino.contract.personal

import io.eitheror.casino.contract.facts.{FamilyDef, Json, Topics}

class PersonalSuite extends munit.FunSuite:

  private def personal(field: (String, ?)): Boolean = field match
    case (_, t: io.eitheror.casino.contract.facts.FieldType[?]) =>
      t.schema match
        case Json.Obj(fs) => fs.contains("x-personal" -> Json.Bool(true))
        case _            => false

  private val everyField: Vector[(String, String, (String, io.eitheror.casino.contract.facts.FieldType[?]))] =
    for
      topic  <- Topics.all
      family <- topic.families
      field  <- family.fields
    yield (topic.name, family.name, field)

  test("every fact field FR-023 names is marked personal: none crosses a topic in the clear (SC-002)") {
    val clear = everyField.collect { case (t, f, field @ (name, _)) if PersonalFields.names.contains(name) && !personal(field) => s"$t $f $name" }
    assertEquals(clear, Vector.empty)
  }

  test("nothing FR-024 says is not personal is marked personal") {
    val marked = everyField.collect { case (t, f, field @ (name, _)) if PersonalFields.notPersonal.contains(name) && personal(field) => s"$t $f $name" }
    assertEquals(marked, Vector.empty)
  }

  test("amounts are never personal, so ledgers survive an erasure (FR-024)") {
    val amounts = everyField.collect { case (t, f, field @ (name, _)) if (name.endsWith("_cents") || name.contains("balance") || name == "amount") && personal(field) => s"$t $f $name" }
    assertEquals(amounts, Vector.empty)
  }

  test("a personal field is held under the player's subject, player/<id>") {
    assertEquals(PlayerSubject("8c1f").id, "player/8c1f")
  }

  test("the programme's facts carry no player id; the shared audit's records no personal value (FR-018)") {
    assert(!Topics.named("affiliates.attribution").get.families.exists(_.fields.exists(_._1 == "player_id")))
    assert(!Topics.named("platform.audit").get.families.exists(_.fields.exists(personal)))
  }
