package io.eitheror.casino.modules

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * What an installation is configured to do: `installation/overlays/<name>/settings.env`, the same
 * file kustomize renders into the `casino-installation` ConfigMap (installation/README.md).
 */
final case class Installation(name: String, settings: Map[String, String], source: Path):
  def get(key: String): Option[String] = settings.get(key).map(_.trim).filter(_.nonEmpty)
  def flag(key: String): Option[Boolean] = get(key).map(_.equalsIgnoreCase("true"))
  def int(key: String): Option[Int] = get(key).flatMap(_.toIntOption)

  /** The longest retention the installation allows: `forever` or `<n>d`. */
  def longestRetentionDays: Option[Option[Int]] =
    get("TOPIC_LONGEST_RETENTION").map(v => if v == "forever" then None else v.stripSuffix("d").toIntOption)

object Installation:
  def load(root: Path, name: String): Either[String, Installation] =
    fromFile(name, root.resolve("installation").resolve("overlays").resolve(name).resolve("settings.env"))

  def fromFile(name: String, file: Path): Either[String, Installation] =
    if !Files.exists(file) then Left(s"no installation '$name': $file does not exist")
    else
      val settings = Files.readAllLines(file).asScala.toVector
        .map(_.trim)
        .filterNot(l => l.isEmpty || l.startsWith("#"))
        .flatMap { line =>
          line.split("=", 2) match
            case Array(k, v) => Some(k.trim -> v.trim)
            case _           => None
        }
        .toMap
      Right(Installation(name, settings, file))
