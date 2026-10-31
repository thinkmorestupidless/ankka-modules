package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.ObjectMapper

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * The release (spec 021 FR-004, SC-004, contracts/release.md): every manifest rendered at the tag's
 * version with its topics' contracts inlined and validated again, then the publish loop of
 * release.yml against the fake vendor plugin: one publish per module, core first, no channel moved.
 */
class ReleaseRenderSuite extends munit.FunSuite:

  private val mapper = ObjectMapper()

  private def rendered(version: String): (Path, Vector[Path]) =
    val out = Files.createTempDirectory("release")
    val files = ReleaseRender.all(Fixtures.root, version, out).fold(rs => fail(rs.mkString("\n")), identity)
    out -> files

  test("a tag is a release version; anything else is refused") {
    assertEquals(ReleaseRender.version("v1.4.0"), Right("1.4.0"))
    assertEquals(ReleaseRender.version("v1.4.0-rc.1"), Right("1.4.0-rc.1"))
    assert(ReleaseRender.version("main").isLeft)
  }

  test("every manifest is rendered with every image at the version, and nothing else of the descriptor changes") {
    val (_, files) = rendered("9.9.9")
    assertEquals(files.map(_.getFileName.toString).toSet, Set("core.json", "casino.json", "engagement.json", "reporting.json", "support.json"))
    files.foreach { f =>
      val doc  = mapper.readTree(f.toFile)
      val tree = mapper.readTree(Fixtures.root.resolve(s"modules/${doc.path("name").asText()}/module.json").toFile)
      doc.path("services").elements().asScala.zip(tree.path("services").elements().asScala).foreach { (r, t) =>
        assertEquals(r.path("descriptor").path("image").asText(), t.path("descriptor").path("image").asText().replace(":0.0.0", ":9.9.9"))
        assertEquals(r.path("descriptor").path("env"), t.path("descriptor").path("env"))
      }
    }
  }

  test("every topic carries its contract, the schema contract/schemas holds, and each rendered manifest validates") {
    val (_, files) = rendered("9.9.9")
    val view = ModuleManifest.ContractView.fromTree(Fixtures.root)
    files.foreach { f =>
      val m = ModuleManifest.load(f).fold(rs => fail(s"$f: ${rs.mkString("; ")}"), identity)
      assertEquals(ModuleManifest.rules(m, view), Vector.empty, f.toString)
      m.json.path("topics").elements().asScala.foreach { t =>
        val name = t.path("name").asText()
        assertEquals(t.path("contract").path("name").asText(), s"$name.v1")
        assertEquals(t.path("contract").path("schema"), mapper.readTree(Fixtures.root.resolve(s"contract/schemas/$name.v1.json").toFile), name)
      }
    }
    val core = mapper.readTree(files.find(_.getFileName.toString == "core.json").get.toFile)
    assertEquals(core.path("topics").elements().asScala.map(_.path("name").asText()).toVector.last, "platform.modules")
  }

  private def publish(dir: Path, version: String, env: Map[String, String] = Map.empty): (Int, String, Vector[String]) =
    val log = dir.resolve("vendor.log")
    val pb = ProcessBuilder("bash", Fixtures.root.resolve("scripts/release-publish.sh").toString, dir.toString, version)
    pb.redirectErrorStream(true)
    pb.environment().put("ANKKA_VENDOR_FAKE_LOG", log.toString)
    pb.environment().put("PATH", s"${Fixtures.root.resolve("tests/fakes")}:${sys.env("PATH")}")
    env.foreach((k, v) => pb.environment().put(k, v))
    val p = pb.start()
    val output = String(p.getInputStream.readAllBytes())
    val code = p.waitFor()
    (code, output, if Files.exists(log) then Files.readAllLines(log).asScala.toVector else Vector.empty)

  test("the dry run publishes every module once at the version, core first, and moves no channel (SC-004)") {
    val (dir, files) = rendered("9.9.9")
    val (code, output, calls) = publish(dir, "v9.9.9")
    assertEquals(code, 0, output)
    assertEquals(calls.size, files.size, calls.mkString("\n"))
    assert(calls.forall(c => c.startsWith("modules publish ") && c.endsWith(" --version 9.9.9")), calls.mkString("\n"))
    assert(calls.head.contains("/core.json"), "core is published first: a module's dependency must be published")
    assertEquals(calls.map(_.split(' ')(2)).toSet, files.map(_.toString).toSet)
    assert(!calls.exists(_.startsWith("channels")), "a release moves no channel")
    assertEquals(output.linesIterator.count(_.startsWith("published ")), files.size, output)
  }

  test("the release job refuses before any publish while a manifest names a service with no build, naming it") {
    val (dir, _) = rendered("9.9.9")
    val (code, output, calls) = publish(dir, "9.9.9", Map("CASINO_RELEASE_REQUIRE_BUILDS" -> "1"))
    assertEquals(code, 1)
    assert(output.contains("refused: service 'rewards' of module casino has no build"), output)
    assertEquals(calls, Vector.empty)
  }

  test("the fake refuses a channel move, so the dry run would see one") {
    val dir = Files.createTempDirectory("fake")
    val pb = ProcessBuilder(Fixtures.root.resolve("tests/fakes/ankka-vendor").toString, "channels", "set", "core", "stable", "9.9.9")
    pb.environment().put("ANKKA_VENDOR_FAKE_LOG", dir.resolve("log").toString)
    pb.redirectErrorStream(true)
    val p = pb.start(); p.getInputStream.readAllBytes()
    assertEquals(p.waitFor(), 1)
    assertEquals(Files.readAllLines(dir.resolve("log")).asScala.toVector, Vector("channels set core stable 9.9.9"))
  }
