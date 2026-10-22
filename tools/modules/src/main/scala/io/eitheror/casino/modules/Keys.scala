package io.eitheror.casino.modules

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** One row of `contract/keys.md`: a configuration key a module manifest may name. */
final case class KeyRow(
    key: String,
    module: String,
    kind: String,
    secret: Boolean,
    /** For a secret key, the secrets file line it is filled from: `<slug>-st8/API_KEY`. */
    readFrom: Option[String],
    /** For a derived key, what `values push` writes it from; a values file may not write it. */
    derivedFrom: Option[String],
    env: Option[String]
):
  def derived: Boolean = derivedFrom.isDefined

  /** The secret and the entry a secret key is read from, for this brand. */
  def source(slug: String): Option[(String, String)] =
    readFrom.map(_.replace("<slug>", slug)).flatMap { s =>
      s.split('/') match
        case Array(secret, entry) => Some(secret -> entry)
        case _                    => None
    }

/**
 * `contract/keys.md` read as tables (spec 021 T003): the keys, and the services the manifests may
 * name before their builds exist. The file is the contract; this reads it, so the two cannot drift.
 */
final case class Keys(rows: Vector[KeyRow], notYetBuilt: Map[String, String]):
  def names: Set[String]                  = rows.map(_.key).toSet
  def named(key: String): Option[KeyRow]  = rows.find(_.key == key)
  def secret(key: String): Boolean        = named(key).exists(_.secret)
  def derived: Vector[KeyRow]             = rows.filter(_.derived)

object Keys:

  private def cells(line: String): Vector[String] =
    line.trim.stripPrefix("|").stripSuffix("|").split('|').map(_.trim.stripPrefix("`").stripSuffix("`")).toVector

  private def tableRows(lines: Vector[String], header: String): Vector[Vector[String]] =
    lines.map(_.trim).filter(l => l.startsWith("|") && !l.startsWith("|---")).map(cells).filterNot(_.headOption.contains(header))

  def parse(text: String): Keys =
    val lines = text.linesIterator.toVector
    val keys = tableRows(lines, "Key").collect {
      case Vector(key, module, kind, secret, readFrom, derivedFrom, env) if module.nonEmpty && secret.matches("yes|no") =>
        def opt(s: String) = Option(s).filter(_.nonEmpty)
        KeyRow(key, module, kind, secret == "yes", opt(readFrom), opt(derivedFrom), opt(env))
    }
    val built = tableRows(lines, "Service").collect { case Vector(service, module, _) => service -> module }.toMap
    Keys(keys, built)

  def load(root: Path): Keys = parse(Files.readString(root.resolve("contract/keys.md")))

  /** The services the tree has a build for: `services/<name>/build.sbt`. */
  def built(root: Path): Set[String] =
    val dir = root.resolve("services")
    if !Files.isDirectory(dir) then Set.empty
    else Files.list(dir).iterator().asScala.filter(d => Files.exists(d.resolve("build.sbt"))).map(_.getFileName.toString).toSet
