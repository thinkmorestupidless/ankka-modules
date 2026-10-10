package io.eitheror.casino.contract.facts

import java.nio.file.{Files, Paths}

/** The committed schemas are what the families generate, byte for byte (`just schemas` to refresh). */
class SchemaDriftSuite extends munit.FunSuite:

  private val dir = Paths.get(sys.props.getOrElse("casino.root", ".")).resolve("contract/schemas")

  for topic <- Topics.all do
    test(s"${topic.contractName}.json is what the families generate") {
      val file = dir.resolve(s"${topic.contractName}.json")
      assert(Files.exists(file), s"$file is missing: run `just schemas`")
      assertEquals(Files.readString(file), topic.schema.render() + "\n", s"$file differs from the families: run `just schemas` and commit")
    }

  test("every schema fits ankka's limit of 65536 bytes") {
    Topics.all.foreach(t => assert((t.schema.render() + "\n").getBytes.length <= 65536, t.name))
  }

  test("every schema fingerprints as ankka does, so a topic's contract can be declared") {
    Topics.all.foreach { t =>
      val result = com.thinkmorestupidless.ankka.core.Contract.fromSchema(t.contractName, Files.readAllBytes(dir.resolve(s"${t.contractName}.json")))
      assert(result.isRight, s"${t.name}: $result")
    }
  }
