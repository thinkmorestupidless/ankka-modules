package io.eitheror.casino.publicapi.contract

import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions

import scala.jdk.CollectionConverters.*

/**
 * T088 (research R2's stand-in for the original's `clientGeneration.test.ts`): the served document parses as OpenAPI
 * 3.1 with every `$ref` resolved, so a client can be generated from it.
 */
class ContractValiditySuite extends munit.FunSuite:
  private val contract = Contract.load("https://api.goldvibe.example/v1").fold(e => fail(e), identity)

  test("the served document is OpenAPI 3.1 with no message and no unresolved reference") {
    val options = ParseOptions(); options.setResolve(true); options.setResolveFully(true)
    val result  = OpenAPIParser().readContents(contract.json, null, options)
    assertEquals(Option(result.getMessages).map(_.asScala.toVector).getOrElse(Vector.empty), Vector.empty)
    val api = result.getOpenAPI
    assert(api != null)
    assertEquals(api.getOpenapi, "3.1.0")
    assertEquals(api.getPaths.size, 22)
    assert(!contract.json.contains("\"$ref\":\"#/components/schemas/Missing"), "")
    api.getPaths.asScala.foreach((p, item) => assert(item.getGet != null && item.getGet.getOperationId != null, p))
  }

  test("a document with a dangling reference is reported") {
    val broken = contract.json.replace("#/components/schemas/Player\"", "#/components/schemas/Nobody\"")
    val result = OpenAPIParser().readContents(broken, null, { val o = ParseOptions(); o.setResolve(true); o })
    assert(Option(result.getMessages).exists(_.asScala.exists(_.contains("Nobody"))), String.valueOf(result.getMessages))
  }
