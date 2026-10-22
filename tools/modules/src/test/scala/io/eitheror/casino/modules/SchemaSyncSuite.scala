package io.eitheror.casino.modules

import java.nio.file.{Files, Path}
import java.time.LocalDate

/** `schema-sync` against the fake plugin, on a copy of the two files it writes (spec 021 T027). */
class SchemaSyncSuite extends munit.FunSuite:

  private def tree(): Path =
    val dir = Files.createTempDirectory("schema-sync")
    for f <- Vector(SchemaSync.Resource, SchemaSync.Readme) do
      Files.createDirectories(dir.resolve(f).getParent)
      Files.copy(Fixtures.root.resolve(f), dir.resolve(f))
    dir

  private def fake(schema: String): PluginVendor =
    val file = Files.createTempFile("vendor-schema", ".json")
    Files.writeString(file, schema)
    val log = Files.createTempFile("vendor", ".log")
    PluginVendor(Vector("env", s"ANKKA_VENDOR_FAKE_SCHEMA=$file", s"ANKKA_VENDOR_FAKE_LOG=$log", Fixtures.root.resolve("tests/fakes/ankka-vendor").toString))

  private val served = """{"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"https://vendor.example/manifest.v2.json","type":"object"}"""

  test("the plugin's schema replaces the vendored one, and the README line says where it came from") {
    val root = tree()
    val out = Output.Collected()
    assertEquals(SchemaSync.run(root, out, () => fake(served), LocalDate.parse("2026-10-22")), 0, out.lines.mkString)
    assert(Files.readString(root.resolve(SchemaSync.Resource)).contains("manifest.v2.json"))
    val readme = Files.readString(root.resolve(SchemaSync.Readme))
    assert(readme.contains("from `ankka vendor modules schema` on 2026-10-22 (`https://vendor.example/manifest.v2.json`), sha256"), readme.linesIterator.take(8).mkString("\n"))
    assert(!readme.contains("written by hand"), "the old line is replaced, not kept beside")
    assert(readme.contains("# "), "the rest of the README stays")
    assert(out.lines.head.startsWith("changed"))
    assert(SchemaSync.run(root, Output.Collected(), () => fake(served), LocalDate.parse("2026-10-22")) == 0)
  }

  test("an answer that is not a JSON schema is refused and nothing is written") {
    val root = tree()
    val before = Files.readString(root.resolve(SchemaSync.Resource))
    val out = Output.Collected()
    assertEquals(SchemaSync.run(root, out, () => fake("""{"type":"object"}""")), 1)
    assertEquals(Files.readString(root.resolve(SchemaSync.Resource)), before)
  }

  test("without the plugin it says so, skips, and changes nothing") {
    val root = tree()
    val before = Files.readString(root.resolve(SchemaSync.Resource))
    val out = Output.Collected()
    assertEquals(SchemaSync.run(root, out, () => PluginVendor(Vector("ankka-vendor-not-installed-anywhere"))), 0)
    assert(out.lines.head.startsWith("skipped"), out.lines.mkString)
    assertEquals(Files.readString(root.resolve(SchemaSync.Resource)), before)
  }
