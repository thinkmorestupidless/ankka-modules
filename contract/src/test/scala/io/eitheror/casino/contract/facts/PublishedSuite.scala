package io.eitheror.casino.contract.facts

import com.fasterxml.jackson.databind.ObjectMapper
import io.eitheror.casino.contract.access.OperatorPermissions

/** Spec 030 research R5, R6: the files the backoffice reads are the contract's, written by WriteSchemas. */
class PublishedSuite extends munit.FunSuite:

  private val json = ObjectMapper()

  test("operator-permissions names every permission and gives the role matrix as its defaults") {
    val tree = json.readTree(Published.operatorPermissions)
    assertEquals(tree.path("version").asInt, 1)
    assertEquals(tree.path("permissions").size, OperatorPermissions.All.size)
    assertEquals(tree.path("defaults").size, OperatorPermissions.DefaultRoles.size)
    assertEquals(tree.path("defaults").path("brand_admin").size, OperatorPermissions.All.size)
  }

  test("currencies give each code its minor-unit count as Amounts reads it: JPY 0, EUR 2, KWD 3") {
    val cs = json.readTree(Published.currencies).path("currencies")
    assertEquals(cs.path("JPY").asInt, 0)
    assertEquals(cs.path("EUR").asInt, 2)
    assertEquals(cs.path("KWD").asInt, 3)
    assertEquals(cs.size, Published.CurrencyCodes.size)
  }

  test("every code of CurrencyCodes is one Currency.from takes, so none is written with a guessed exponent") {
    val refused = Published.CurrencyCodes.filter(c => io.eitheror.casino.contract.money.Currency.from(c).isLeft)
    assertEquals(refused, Vector.empty[String])
  }

  test("the committed files are what the contract writes") {
    val root = java.nio.file.Paths.get(sys.props.getOrElse("casino.root", "."), "contract", "schemas")
    for (name, text) <- Published.files do
      val file = root.resolve(name)
      assert(java.nio.file.Files.exists(file), s"$file is not there: the contract writes it (just schemas), or casino.root is wrong")
      assertEquals(java.nio.file.Files.readString(file), text, name)
  }
