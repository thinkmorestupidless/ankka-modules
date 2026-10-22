package io.eitheror.casino.modules

import io.eitheror.casino.contract.port.Recording

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * `recordings check` (spec 001 SC-006): every recording under `tests/port/` is well formed, was run
 * twice on fresh ids, and names the requirement it establishes or is listed as unclaimed; and every
 * recording a spec names exists.
 */
object Recordings:

  def all(root: Path): Vector[Path] =
    val dir = root.resolve("tests/port")
    if !Files.isDirectory(dir) then Vector.empty
    else
      Files.walk(dir).iterator().asScala.toVector
        .filter(p => p.toString.endsWith(".json") && p.getFileName.toString != "suite-result.json")
        .sorted

  /** Recordings no requirement claims yet: `tests/port/unclaimed.txt`, one path per line. */
  def unclaimed(root: Path): Set[String] =
    val file = root.resolve("tests/port/unclaimed.txt")
    if !Files.exists(file) then Set.empty
    else Files.readAllLines(file).asScala.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toSet

  private val Named = """tests/port/[A-Za-z0-9_./-]+\.json""".r

  /** Recordings the specs name. */
  def named(root: Path): Vector[(Path, String)] =
    val specs = root.resolve("specs")
    if !Files.isDirectory(specs) then Vector.empty
    else
      Files.walk(specs).iterator().asScala.toVector.filter(_.getFileName.toString == "spec.md").flatMap { spec =>
        Named.findAllIn(Files.readString(spec)).toVector.distinct.map(spec -> _)
      }

  def problems(root: Path): Vector[String] =
    val skip = unclaimed(root)
    val recordings = all(root).map(p => p -> Recording.load(p))
    recordings.flatMap {
      case (_, Left(e)) => Vector(e)
      case (p, Right(r)) =>
        val rel = root.relativize(p).toString
        Vector(
          Option.when(r.runs < 2)(s"$rel: run ${r.runs} time(s); a scenario is run twice on fresh ids so `vary` is known"),
          Option.when(r.requirement.isEmpty && !skip.contains(rel))(s"$rel: names no requirement and is not listed in tests/port/unclaimed.txt"),
          Option.when(r.steps.isEmpty)(s"$rel: has no steps")
        ).flatten
    } ++ named(root).collect {
      case (spec, path) if !Files.exists(root.resolve(path)) => s"${root.relativize(spec)} names $path, which does not exist"
    }

  def check(root: Path, out: Output): Int =
    val found = problems(root)
    found.foreach(p => out.line(s"refused $p"))
    out.line(s"recordings: ${all(root).size}, problems: ${found.size}")
    if found.isEmpty then 0 else 1
