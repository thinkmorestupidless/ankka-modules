package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.{JsonSchemaFactory, SpecVersion}

import scala.jdk.CollectionConverters.*

/** The vendored schema accepts the vendor's own example (ankka-vendor 004 contracts/manifest.md) and refuses an unknown field. */
class VendorSchemaSuite extends munit.FunSuite:

  private val schema =
    val in = getClass.getResourceAsStream("/ankka-vendor-manifest.schema.json")
    try JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in) finally in.close()

  private def example = ObjectMapper().readTree(getClass.getResourceAsStream("/manifests/vendor-example.json"))

  test("the vendor's example manifest validates") {
    assertEquals(schema.validate(example).asScala.toVector.map(_.getMessage), Vector.empty)
  }

  test("an unknown field is refused, naming it (the manifest's versioning rule)") {
    val node = example.asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
    node.put("schema", "2")
    val messages = schema.validate(node).asScala.toVector.map(_.getMessage)
    assert(messages.exists(_.contains("schema")), messages.mkString("; "))
  }
