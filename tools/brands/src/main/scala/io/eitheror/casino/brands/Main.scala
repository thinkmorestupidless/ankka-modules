package io.eitheror.casino.brands

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * `brands validate|apply|status|recordings` (tools/brands/README.md). Exit 0 when done or valid,
 * 1 when refused or failed, 2 when the command itself is wrong.
 */
object Main:

  final case class Args(command: Vector[String], flags: Map[String, String], switches: Set[String])

  def parse(argv: Seq[String]): Args =
    val flags    = Map.newBuilder[String, String]
    val switches = Set.newBuilder[String]
    val command  = Vector.newBuilder[String]
    val Valued   = Set("--installation", "--organization", "--secrets")
    var i = 0
    while i < argv.length do
      argv(i) match
        case f if Valued.contains(f) && i + 1 < argv.length => flags += f -> argv(i + 1); i += 2
        case s if s.startsWith("--")                       => switches += s; i += 1
        case word                                          => command += word; i += 1
    Args(command.result(), flags.result(), switches.result())

  def main(argv: Array[String]): Unit =
    val out = Output.console
    sys.exit(run(parse(argv.toSeq), root(), out, () => HttpControlPlane.fromEnvironment()))

  def root(): Path = sys.env.get("CASINO_ROOT").orElse(sys.props.get("casino.root")).map(Paths.get(_)).getOrElse(Paths.get("").toAbsolutePath)

  /** Everything but the process: the tests call this with an in-memory control plane. */
  def run(args: Args, root: Path, out: Output, controlPlane: () => ControlPlane, realms: Option[RealmAdmin] = None): Int =
    args.command match
      case Vector("validate", path)   => validate(args, root, root.resolve(path), out)
      case Vector("apply", path)      => apply(args, root, root.resolve(path), out, controlPlane, realms)
      case Vector("status", which)    => status(args, root, which, out, controlPlane, realms)
      case Vector("recordings", "check") => Recordings.check(root, out)
      case Vector("specs", "established") => Established.check(root, out)
      case _ =>
        out.err(
          """usage: brands validate <manifest> [--installation <name>] [--real-money]
            |       brands apply <manifest> --installation <name> [--organization <id>] [--secrets <file>]
            |       brands status <slug or project> [--installation <name>]
            |       brands recordings check
            |       brands specs established""".stripMargin)
        2

  /** Validation: schema, rules, and readiness for real money, with no call (FR-010, FR-040). */
  def checked(args: Args, root: Path, path: Path, out: Output): Either[Int, (Manifest, Option[Installation])] =
    Manifest.load(path) match
      case Left(problems) =>
        problems.foreach(p => out.line(s"refused $p"))
        out.line("calls: 0")
        Left(1)
      case Right(manifest) =>
        val installation = args.flags.get("--installation").map(Installation.load(root, _))
        installation match
          case Some(Left(problem)) => out.err(problem); Left(2)
          case _ =>
            val inst = installation.flatMap(_.toOption)
            val realMoney = manifest.realMoney || args.switches.contains("--real-money")
            val refusals =
              if !realMoney then Vector.empty
              else inst match
                case None => Vector(Problem("real-money", "a brand taking real money needs --installation to check it against"))
                case Some(i) => Readiness.refusals(Readiness.conditions(i))
            if refusals.nonEmpty then
              refusals.foreach(p => out.line(s"refused $p"))
              out.line("calls: 0")
              Left(1)
            else Right(manifest -> inst)

  private def validate(args: Args, root: Path, path: Path, out: Output): Int =
    checked(args, root, path, out) match
      case Left(code) => code
      case Right((manifest, inst)) =>
        out.line(s"valid ${kind(manifest)} ${manifest.projectId}: ${manifest.services.size} services, ${manifest.topics.size} topics")
        inst.foreach(i => Readiness.conditions(i).foreach(c => out.line(s"  ${c.line}")))
        out.line("calls: 0")
        0

  private def kind(m: Manifest) = m match
    case _: BrandManifest  => "brand"
    case _: SharedManifest => "shared project"

  /** The brands the installation's manifests name, for a shared project's per-brand grants. */
  def brandSlugs(root: Path): Vector[String] =
    val dir = root.resolve("brands")
    if !Files.isDirectory(dir) then Vector.empty
    else
      Files.list(dir).iterator().asScala.toVector
        .filter(d => Files.exists(d.resolve("manifest.json")))
        .flatMap(d => Manifest.load(d.resolve("manifest.json")).toOption.collect { case b: BrandManifest => b.slug })
        .sorted

  private def apply(args: Args, root: Path, path: Path, out: Output, controlPlane: () => ControlPlane, realms: Option[RealmAdmin]): Int =
    if !args.flags.contains("--installation") then
      out.err("apply needs --installation <name>: the settings the brand is checked and rendered against")
      return 2
    checked(args, root, path, out) match
      case Left(code) => code
      case Right((manifest, inst)) =>
        val cp = controlPlane()
        val plan = Render.plan(manifest, inst, brandSlugs(root))
        val secrets = args.flags.get("--secrets").map(f => SecretsFile.load(root.resolve(f))).getOrElse(Right(Map.empty))
        secrets match
          case Left(problem) => out.err(problem); 2
          case Right(values) =>
            val admin = realms.orElse(KeycloakAdmin.fromEnvironment(manifest.issuerBase).toOption)
            val realm = manifest match
              case brand: BrandManifest =>
                admin.map(a => a -> (() => Realm.render(Realm.template(root), brand.slug, frontend(brand, inst))))
              case _ => None
            val organization = args.flags.get("--organization").orElse(sys.env.get("ANKKA_ORGANIZATION")).getOrElse("eitheror")
            val result = Apply.run(plan, cp, Apply.Options(organization, values, realm))
            result.changes.foreach(c => out.line(s"changed $c"))
            result.notes.foreach(n => out.line(s"note $n"))
            out.line(s"changes: ${result.changes.size}")
            result.failure match
              case Some(f) => out.line(s"failed $f"); 1
              case None    => 0

  def frontend(brand: BrandManifest, inst: Option[Installation]): String =
    brand.frontendBaseUrl
      .orElse(inst.flatMap(_.get("PLAYER_SITE_BASE")).map(b => s"https://${brand.slug}.$b"))
      .getOrElse(s"https://${brand.slug}.invalid")

  private def status(args: Args, root: Path, which: String, out: Output, controlPlane: () => ControlPlane, realms: Option[RealmAdmin]): Int =
    val candidates = Vector(root.resolve("brands").resolve(which).resolve("manifest.json"), root.resolve("shared").resolve(which).resolve("manifest.json"))
    candidates.find(Files.exists(_)) match
      case None => out.err(s"no manifest for '$which' under brands/ or shared/"); 2
      case Some(path) =>
        Manifest.load(path) match
          case Left(problems) => problems.foreach(p => out.line(s"refused $p")); 1
          case Right(manifest) =>
            val inst = args.flags.get("--installation").flatMap(Installation.load(root, _).toOption)
            val plan = Render.plan(manifest, inst, brandSlugs(root))
            Status.lines(plan, controlPlane(), inst.map(Readiness.conditions), realms).foreach(out.line)
            0

/** Where the tool writes; the tests collect it. */
trait Output:
  def line(text: String): Unit
  def err(text: String): Unit

object Output:
  val console: Output = new Output:
    def line(text: String) = println(text)
    def err(text: String)  = System.err.println(text)

  final class Collected extends Output:
    val lines  = scala.collection.mutable.ArrayBuffer.empty[String]
    val errors = scala.collection.mutable.ArrayBuffer.empty[String]
    def line(text: String) = lines += text
    def err(text: String)  = errors += text

/** `--secrets <file>`: `<secret>/<KEY>=<value>` lines, kept outside the repository. */
object SecretsFile:
  def load(path: Path): Either[String, Map[String, Map[String, String]]] =
    if !Files.exists(path) then Left(s"no secrets file $path")
    else
      val entries = Files.readAllLines(path).asScala.toVector.map(_.trim).filterNot(l => l.isEmpty || l.startsWith("#"))
      val parsed = entries.map { line =>
        line.split("=", 2) match
          case Array(k, v) if k.contains('/') =>
            val Array(secret, key) = k.split("/", 2): @unchecked
            Right((secret, key, v))
          case _ => Left(s"secrets file: '${line.takeWhile(_ != '=')}' is not <secret>/<KEY>=<value>")
      }
      parsed.collectFirst { case Left(e) => e } match
        case Some(e) => Left(e)
        case None =>
          Right(parsed.collect { case Right(t) => t }.groupBy(_._1).view.mapValues(_.map(t => t._2 -> t._3).toMap).toMap)
