package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * What the modules tool asks of ankka-vendor, through its plugin (vendor 004 `contracts/cli.md`): a
 * channel's version for `render`, a brand's non-secret values for `values push`, and the manifest
 * schema for `schema-sync`. Never a secret: those go straight to the brand's project.
 */
trait Vendor:
  /** The version a module's channel names (`ankka vendor modules get <module>`). */
  def channelVersion(module: String, channel: String): Either[String, String]
  /** `ankka vendor tenants values set <tenant> key=value…`. */
  def setValues(tenant: String, entries: Map[String, String]): Either[String, Unit]
  /** `ankka vendor modules schema`: the manifest's JSON schema. */
  def schema(): Either[String, String]
  def calls: Vector[String]

/**
 * The plugin run as a process: `ankka vendor …` by default (ankka 056 finds `ankka-vendor` on the
 * path and hands it the CLI's token), or the command `CASINO_VENDOR_COMMAND` names.
 */
final class PluginVendor(command: Vector[String]) extends Vendor:

  private val log    = mutable.ArrayBuffer.empty[String]
  private val mapper = ObjectMapper()
  def calls: Vector[String] = log.toVector

  private def run(args: String*): Either[String, String] =
    log += args.mkString(" ")
    try
      val pb = ProcessBuilder((command ++ args).asJava)
      pb.redirectError(ProcessBuilder.Redirect.PIPE)
      val p      = pb.start()
      val out    = String(p.getInputStream.readAllBytes())
      val err    = String(p.getErrorStream.readAllBytes())
      val code   = p.waitFor()
      if code == 0 then Right(out) else Left(s"${(command ++ args).mkString(" ")}: exit $code: ${err.trim}")
    catch case e: java.io.IOException => Left(s"${command.mkString(" ")} is not on the path: ${e.getMessage}")

  def channelVersion(module: String, channel: String): Either[String, String] =
    run("modules", "get", module, "--output", "json").flatMap { text =>
      PluginVendor.channel(mapper.readTree(text), channel).toRight(s"module '$module' has no channel '$channel' at the vendor")
    }

  def setValues(tenant: String, entries: Map[String, String]): Either[String, Unit] =
    run(Vector("tenants", "values", "set", tenant) ++ entries.toVector.sortBy(_._1).map((k, v) => s"$k=$v")*).map(_ => ())

  def schema(): Either[String, String] = run("modules", "schema")

object PluginVendor:
  def fromEnvironment(env: Map[String, String] = sys.env): PluginVendor =
    PluginVendor(env.get("CASINO_VENDOR_COMMAND").map(_.split(' ').toVector.filter(_.nonEmpty)).getOrElse(Vector("ankka", "vendor")))

  /** A channel's version in `modules get`'s JSON: `channels` as an object (`{"stable": "1.4.0"}`) or a list of `{name, version}`. */
  def channel(node: JsonNode, name: String): Option[String] =
    val channels = node.path("channels")
    if channels.isObject then Option(channels.get(name)).map(n => if n.isTextual then n.asText() else n.path("version").asText())
    else channels.elements().asScala.find(_.path("name").asText() == name).map(_.path("version").asText())
