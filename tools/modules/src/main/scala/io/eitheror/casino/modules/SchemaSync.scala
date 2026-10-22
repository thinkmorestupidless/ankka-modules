package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.ObjectMapper

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.LocalDate
import scala.util.Try

/**
 * `schema-sync` (contracts/modules-tool.md): the vendor's manifest schema from `ankka vendor modules
 * schema` into the vendored resource, and the README's line saying where it came from. Without the
 * plugin it says so and changes nothing. A schema that is not JSON, or has no `$schema`, is refused.
 */
object SchemaSync:

  val Resource = "tools/modules/src/main/resources/ankka-vendor-manifest.schema.json"
  val Readme   = "tools/modules/README.md"
  private val Line = "> Vendor manifest schema: "

  def run(root: Path, out: Output, vendor: () => Vendor, today: LocalDate = LocalDate.now()): Int =
    vendor().schema() match
      case Left(e) if e.contains("is not on the path") =>
        out.line(s"skipped: the ankka-vendor plugin is not on the path ($e); the vendored schema is unchanged"); 0
      case Left(e) => out.line(s"failed $e"); 1
      case Right(text) =>
        Try(ObjectMapper().readTree(text)).toOption.filter(n => n.isObject && n.has("$schema")) match
          case None => out.line("refused: what ankka vendor modules schema answered is not a JSON schema"); 1
          case Some(node) =>
            val pretty  = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n"
            val target  = root.resolve(Resource)
            val current = Try(Files.readString(target)).toOption
            val digest  = MessageDigest.getInstance("SHA-256").digest(pretty.getBytes(StandardCharsets.UTF_8)).take(6).map("%02x".format(_)).mkString
            val id      = Option(node.get("$id")).map(_.asText()).filter(_.nonEmpty)
            val line    = s"${Line}`src/main/resources/ankka-vendor-manifest.schema.json`, from `ankka vendor modules schema` on $today" +
              s"${id.fold("")(i => s" (`$i`)")}, sha256 $digest. `ankka casino schema-sync` refreshes it and rewrites this line."
            Files.writeString(target, pretty)
            rewriteReadme(root.resolve(Readme), line)
            out.line(if current.contains(pretty) then s"unchanged $Resource (sha256 $digest)" else s"changed $Resource (sha256 $digest)")
            0

  /** The quoted paragraph starting `> Vendor manifest schema:` becomes `line`; the rest of the README stays. */
  private def rewriteReadme(path: Path, line: String): Unit =
    val lines = Files.readAllLines(path).toArray(Array.empty[String]).toVector
    val start = lines.indexWhere(_.startsWith(Line))
    val updated =
      if start < 0 then lines :+ "" :+ line
      else
        val end = lines.indexWhere(l => !l.startsWith(">"), start) match
          case -1 => lines.size
          case i  => i
        lines.take(start) ++ Vector(line) ++ lines.drop(end)
    Files.writeString(path, updated.mkString("\n") + "\n")
