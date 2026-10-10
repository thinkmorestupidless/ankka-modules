package io.eitheror.casino.tools.tlfixtures

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper, SerializationFeature}
import com.fasterxml.jackson.databind.node.JsonNodeFactory

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Converts the original's wallet handler fixtures into one JSON case file per handler, once
 * (spec 002 research R13). Run from the repository root:
 * {{{
 * sbt "tlFixtures/run ../eitheror-platform/starter-packs/wallet/handlers services/wallet/src/test/resources/fixtures"
 * }}}
 */
object Main:

  /** Handlers whose routes spec 002 drops (contracts/surface.md): their fixtures are not converted. */
  val Skipped: Set[String] = Set(
    "approve-withdrawal-request",
    "mark-withdrawal-awaiting-payout",
    "check-open-round",
    "read-contribution-rate",
    "create-payout-destination",
    "set-default-payout-destination",
    "delete-payout-destination"
  )

  private val TestLine = "(?m)^test \"".r

  final case class Converted(handler: String, cases: Int)

  def convert(handlersDir: Path, outDir: Path): Vector[Converted] =
    val mapper  = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    val factory = JsonNodeFactory.instance
    Files.createDirectories(outDir)
    val files = Files.list(handlersDir).iterator.asScala
      .filter(_.getFileName.toString.endsWith(".test.tl"))
      .toVector.sortBy(_.getFileName.toString)
    if files.isEmpty then throw IllegalArgumentException(s"no .test.tl files in $handlersDir")
    files.flatMap { file =>
      val handler = file.getFileName.toString.stripSuffix(".test.tl")
      Option.unless(Skipped(handler)) {
        val src      = Files.readString(file, UTF_8)
        val cases    = Parser.parse(file.getFileName.toString, src)
        val declared = TestLine.findAllMatchIn(src).size
        if cases.size != declared then
          throw IllegalStateException(s"$handler: ${cases.size} cases parsed but $declared tests declared; refusing to write a partial file")
        val root = factory.objectNode()
        root.put("handler", handler)
        root.put("source", s"eitheror-platform/starter-packs/wallet/handlers/${file.getFileName}")
        val arr = factory.arrayNode()
        cases.foreach(c => arr.add(c.json))
        root.set[JsonNode]("cases", arr)
        Files.writeString(outDir.resolve(s"$handler.json"), mapper.writeValueAsString(root) + "\n", UTF_8)
        Converted(handler, cases.size)
      }
    }

  def main(args: Array[String]): Unit =
    args match
      case Array(in, out) =>
        val converted = convert(Path.of(in), Path.of(out))
        converted.foreach(c => println(f"${c.handler}%-32s ${c.cases}%4d"))
        println(f"${"total"}%-32s ${converted.map(_.cases).sum}%4d")
        println(s"skipped: ${Skipped.toVector.sorted.mkString(", ")}")
      case _ =>
        System.err.println("usage: tlFixtures/run <handlers-dir> <out-dir>")
        sys.exit(2)
