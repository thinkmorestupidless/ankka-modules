package io.eitheror.casino.reporting.port

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import io.eitheror.casino.contract.port.Recording

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The reporting recordings of tests/port/reporting, replayed for their shape (contracts/recording.md): the original's
 * figures are its twenty-player sample's, so a step is held to its status, the media type of its answer, its field
 * names and the JSON type of each value (a string by its kind: a date, a time, money, an id), the refusal texts, and
 * for the dispute report its headers, its HTML's structure and its CSV's header rows and column counts. Every
 * difference is a [[Parity.Divergence]] naming where it is decided, or a failure.
 */
object Parity:
  val root: Path      = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize
  val directory: Path = root.resolve("tests/port/reporting")

  /** What the driver records and no parity test replays: the seed's arrangement and the suite's own result. */
  val NotReplayed: Set[String] = Set("reporting-seed", "suite-result")

  /** Every recording a parity test replays, by name (its file without `.json`). */
  def names: Vector[String] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.map(_.getFileName.toString).filter(_.endsWith(".json"))
      .map(_.stripSuffix(".json")).filterNot(NotReplayed).toVector.sorted

  /** A recording and the response headers its steps kept, which `Recording` does not read. */
  final case class Recorded(name: String, recording: Recording, headers: Vector[Map[String, String]])

  private val mapper = ObjectMapper()

  def load(name: String): Recorded =
    val path      = directory.resolve(s"$name.json")
    val recording = Recording.load(path).fold(e => throw AssertionError(e), identity)
    val raw       = mapper.readTree(Files.readString(path))
    val headers = raw.path("steps").elements().asScala.toVector.map { s =>
      val h = s.path("response").path("headers")
      if h.isObject then h.properties.asScala.map(e => e.getKey -> e.getValue.asText).toMap else Map.empty[String, String]
    }
    Recorded(name, recording, headers)

  // ── differences and divergences ─────────────────────────────────────────────────────────────────

  /** `at` is `steps[<i>].<what>`: `status`, `headers.<name>`, `body…` (JSON), `html[<token>]`, `csv…` or `values…`. */
  final case class Difference(recording: String, at: String, expected: String, observed: String):
    def message: String = s"$recording $at: expected $expected, observed $observed"

  /**
   * Where the service answers differently from the original on purpose: the recordings it holds in, the paths it covers
   * (an index written `[]`, a path covering what is under it), the steps (every step when empty), why, and where that is
   * decided. `pending` marks one no decision covers yet, which the person is asked about.
   */
  final case class Divergence(recordings: Set[String], fields: Seq[String], why: String, decided: String, steps: Set[Int] = Set.empty,
      pending: Boolean = false):
    def covers(d: Difference): Boolean =
      recordings(d.recording) && {
        val (step, field) = split(d.at)
        (steps.isEmpty || steps(step)) && fields.exists(f => field == f || field.startsWith(f + ".") || field.startsWith(f + "["))
      }
    def label: String = if pending then s"pending: $why" else s"diverges: $why"

  private val StepAt = """^steps\[(\d+)\]\.(.*)$""".r
  private val Index  = """\[\d+\]""".r

  private def split(at: String): (Int, String) = at match
    case StepAt(i, rest) => (i.toInt, Index.replaceAllIn(rest, "[]"))
    case other           => (-1, other)

  def divergence(d: Difference): Option[Divergence] = Divergences.all.find(_.covers(d))

  def diverging(name: String): Vector[Divergence] = Divergences.all.filter(_.recordings(name))

  // ── shapes ──────────────────────────────────────────────────────────────────────────────────────

  private val Uuid      = """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""".r
  private val Date      = """\d{4}-\d{2}-\d{2}""".r
  private val Time      = """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})""".r
  private val MoneyText = """-?\d+\.\d{2}""".r
  private val Hour      = """\d{2}:00""".r
  private val Week      = """\d{4}-W\d{2}""".r

  /** A string's kind: what a value of the original and one of the service must share, whatever their figures. */
  def kind(s: String): String =
    if s.isEmpty then "empty"
    else if Uuid.matches(s) then "id"
    else if Date.matches(s) then "date"
    else if Time.matches(s) then "time"
    else if MoneyText.matches(s) then "money"
    else if Hour.matches(s) then "hour"
    else if Week.matches(s) then "week"
    else "text"

  def typeOf(n: JsonNode): String =
    if n == null || n.isMissingNode then "absent"
    else if n.isNull then "null"
    else if n.isTextual then s"string:${kind(n.asText)}"
    else if n.isNumber then "number"
    else if n.isBoolean then "boolean"
    else if n.isArray then "array"
    else "object"

  /**
   * The shape of `observed` held to `expected`'s: the same fields (one only the service answers named as such), each of
   * the same type; an array empty in one and not the other; each element of an observed array matching some element of
   * the recorded one, else its differences from the closest.
   */
  def shape(recording: String, at: String, expected: JsonNode, observed: JsonNode): Vector[Difference] =
    def diff(e: String, o: String) = Vector(Difference(recording, at, e, o))
    (expected, observed) match
      case (e: ObjectNode, o: ObjectNode) =>
        val names = (e.fieldNames.asScala ++ o.fieldNames.asScala).toVector.distinct
        names.flatMap { n =>
          (Option(e.get(n)), Option(o.get(n))) match
            case (Some(x), Some(y)) => shape(recording, s"$at.$n", x, y)
            case (Some(x), None)    => Vector(Difference(recording, s"$at.$n", typeOf(x), "absent"))
            case (None, Some(y))    => Vector(Difference(recording, s"$at.$n", "absent", s"${typeOf(y)} (added)"))
            case (None, None)       => Vector.empty
        }
      case (e: ArrayNode, o: ArrayNode) =>
        if e.isEmpty && o.isEmpty then Vector.empty
        else if e.isEmpty then diff("[] (empty)", s"${o.size} element(s)")
        else if o.isEmpty then diff(s"${e.size} element(s)", "[] (empty)")
        else
          val recorded = e.elements.asScala.toVector
          o.elements.asScala.toVector.zipWithIndex.flatMap { (y, i) =>
            val tries = recorded.iterator.map(x => shape(recording, s"$at[$i]", x, y))
            var best: Vector[Difference] = null
            while tries.hasNext && (best == null || best.nonEmpty) do
              val t = tries.next()
              if best == null || t.size < best.size then best = t
            best
          }.distinctBy(d => (Index.replaceAllIn(d.at, "[]"), d.expected, d.observed))
      case _ =>
        val (te, to) = (typeOf(expected), typeOf(observed))
        if te == to then Vector.empty else diff(te, to)

  // ── the dispute report's files ──────────────────────────────────────────────────────────────────

  private val Tag   = """<(/?[a-zA-Z0-9]+)([^>]*)>""".r
  private val Attr  = """([a-zA-Z-]+)="([^"]*)"""".r
  private val Th    = """<th>([^<]*)</th>""".r
  private val Style = """(?s)<style>(.*?)</style>""".r

  /**
   * The HTML's structure, not its times or figures: each element in order with its attributes, each table's column
   * names, and the style sheet, line by line.
   */
  def htmlStructure(html: String): Vector[String] =
    val style = Style.findFirstMatchIn(html).toVector.flatMap(_.group(1).linesIterator.map(_.trim).filter(_.nonEmpty).map(l => s"style: $l"))
    val heads = Th.findAllMatchIn(html).map(m => s"th: ${m.group(1)}")
    val tags = Tag.findAllMatchIn(html).map { m =>
      val attrs = Attr.findAllMatchIn(m.group(2)).map(a => s"${a.group(1)}=${a.group(2)}").toVector.sorted
      (m.group(1).toLowerCase +: attrs).mkString(" ")
    }
    style ++ tags.toVector ++ heads.toVector

  def htmlDifferences(recording: String, at: String, expected: String, observed: String): Vector[Difference] =
    val (e, o) = (htmlStructure(expected), htmlStructure(observed))
    if e == o then Vector.empty
    else
      val i = e.zip(o).indexWhere(_ != _) match
        case -1 => e.size min o.size
        case n  => n
      Vector(Difference(recording, s"$at[$i]", e.slice(i, i + 3).mkString(" | ").ifEmpty("the end"),
        o.slice(i, i + 3).mkString(" | ").ifEmpty("the end")))

  extension (s: String) private def ifEmpty(t: String): String = if s.isEmpty then t else s

  /** A CSV's sections, split at blank lines, each its header row and every row's column count. */
  def csvSections(csv: String): Vector[(String, Vector[Int])] =
    val lines = csv.split("\r?\n", -1).toVector
    val blocks = lines.foldLeft(Vector(Vector.empty[String]))((acc, l) =>
      if l.trim.isEmpty then acc :+ Vector.empty else acc.init :+ (acc.last :+ l)).filter(_.nonEmpty)
    blocks.map(b => b.head -> b.tail.map(columns))

  /** The columns of one row: a cell quoted when it holds a comma, a quote or a line break. */
  def columns(row: String): Int =
    var (n, quoted, i) = (1, false, 0)
    while i < row.length do
      row(i) match
        case '"'          => quoted = !quoted
        case ',' if !quoted => n += 1
        case _            => ()
      i += 1
    n

  def csvDifferences(recording: String, at: String, expected: String, observed: String): Vector[Difference] =
    val (e, o) = (csvSections(expected), csvSections(observed))
    val count =
      if e.size != o.size then Vector(Difference(recording, s"$at.sections", e.size.toString, o.size.toString)) else Vector.empty
    count ++ e.zip(o).zipWithIndex.flatMap { case (((eh, _), (oh, rows)), i) =>
      val header = if eh != oh then Vector(Difference(recording, s"$at.sections[$i].header", eh, oh)) else Vector.empty
      val width  = columns(oh)
      header ++ rows.zipWithIndex.collect { case (c, r) if c != width =>
        Difference(recording, s"$at.sections[$i].rows[$r].columns", width.toString, c.toString)
      }
    }

  /** A `Content-Disposition` with its reference read as a placeholder. */
  def disposition(value: String): String = Uuid.replaceAllIn(value, "<reference>")

  def parse(text: String): Option[JsonNode] = Try(mapper.readTree(text)).toOption.filter(n => n != null && !n.isMissingNode)
