package io.eitheror.casino.modules

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * `specs established` (spec 001 FR-031, SC-006): every requirement names what established it — a
 * recording under tests/port/, the original's source, or a decision (this spec's clarifications,
 * another spec, the plan, an ankka spec, the eitheror brain). A requirement with none of those is
 * listed. The reading is by words, not structure, so it errs towards accepting.
 */
object Established:

  private val Requirement = """(?ms)^- \*\*(FR-\d+[a-z]?)\*\*:(.*?)(?=^- \*\*|^\*\*|^#|\z)""".r

  private val Names = """(?i)tests/port/|\brecord(ing|ed)\b|\boriginal\b|\bdecision\b|\bspec \d{3}\b|\(\d{3}\b|\bplan\b|clarif|ankka \d{3}|\bbrain\b|research r\d+|\bestablished\b""".r

  def unnamed(spec: String): Vector[String] =
    Requirement.findAllMatchIn(spec).collect { case m if Names.findFirstIn(m.group(2)).isEmpty => m.group(1) }.toVector

  def problems(root: Path): Vector[String] =
    val specs = root.resolve("specs")
    if !Files.isDirectory(specs) then Vector.empty
    else
      Files.list(specs).iterator().asScala.toVector.sorted.map(_.resolve("spec.md")).filter(Files.exists(_)).flatMap { spec =>
        unnamed(Files.readString(spec)).map(fr => s"${root.relativize(spec)} $fr names nothing that established it")
      }

  def check(root: Path, out: Output): Int =
    val found = problems(root)
    found.foreach(p => out.line(s"unestablished $p"))
    out.line(s"requirements naming nothing that established them: ${found.size}")
    if found.isEmpty then 0 else 1
