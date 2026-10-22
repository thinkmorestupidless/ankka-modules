package io.eitheror.casino.modules

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
    val Valued   = Set("--installation", "--organization", "--secrets", "--out", "--modules", "--version", "--channel", "--format")
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
  def run(args: Args, root: Path, out: Output, controlPlane: () => ControlPlane, realms: Option[RealmAdmin] = None,
      vendor: () => Vendor = () => PluginVendor.fromEnvironment(), env: Map[String, String] = sys.env): Int =
    args.command match
      case Vector("render", slug)     => render(args, root, slug, out, vendor)
      case Vector("values", "push", slug) => BrandCommands.push(args, root, slug, out, controlPlane, vendor, env)
      case Vector("secrets", slug)    => BrandCommands.secrets(args, root, slug, out, controlPlane, env)
      case Vector("hostnames", slug)  => BrandCommands.hostnames(args, root, slug, out, controlPlane, env)
      case Vector("realm", slug)      => BrandCommands.realm(args, root, slug, out, realms)
      case Vector("validate") if args.switches.contains("--all") => validateAll(root, out)
      case Vector("validate", path) if path.endsWith("module.json") => validateModule(root, root.resolve(path), out)
      case Vector("validate", path) if path.endsWith("values.json") => validateValues(args, root, root.resolve(path), out)
      case Vector("validate", path)   => validate(args, root, root.resolve(path), out)
      case Vector("apply", path)      => apply(args, root, root.resolve(path), out, controlPlane, realms)
      case Vector("status", which)    => status(args, root, which, out, controlPlane, realms)
      case Vector("release", "render", tag) => releaseRender(args, root, tag, out)
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

  /** One module manifest: the vendor's schema, then the contract's rules (spec 021 FR-002). No call. */
  private def validateModule(root: Path, path: Path, out: Output): Int =
    ModuleManifest.validate(path, ModuleManifest.ContractView.fromTree(root)) match
      case Left(refusals) =>
        refusals.foreach(r => out.line(s"refused ${root.relativize(path)}: $r")); out.line("calls: 0"); 1
      case Right(m) =>
        out.line(s"valid module ${m.name} (${m.kind}): ${m.services.size} services, ${m.topics.size} topics, ${m.configuration.size} keys")
        out.line("calls: 0"); 0

  def valuesView(root: Path): Values.View = Values.View(Keys.load(root), io.eitheror.casino.contract.Layout.Services.toSet)

  /** One brand's values (spec 021 FR-003); with `--installation` and `--real-money`, the core's requirements too. */
  private def validateValues(args: Args, root: Path, path: Path, out: Output): Int =
    Values.load(path, valuesView(root)) match
      case Left(refusals) =>
        refusals.foreach(r => out.line(s"refused ${root.relativize(path)}: $r")); out.line("calls: 0"); 1
      case Right(v) =>
        BrandCommands.loadInstallation(args, root, out) match
          case Left(code) => code
          case Right(inst) =>
            // The core's requirements, from the overlay's settings: validate makes no call (contracts/modules-tool.md).
            val refusals = BrandCommands.realMoney(v, v.slug, inst, inst.map(Readiness.fromOverlay), force = args.switches.contains("--real-money"))
            if refusals.nonEmpty then
              refusals.foreach(r => out.line(s"refused ${root.relativize(path)}: $r")); out.line("calls: 0"); 1
            else
              out.line(s"valid values ${v.slug}: ${v.values.size} values, ${v.secrets.size} secrets")
              inst.foreach(i => Readiness.conditions(i).foreach(c => out.line(s"  ${c.line}")))
              out.line("calls: 0"); 0

  // ── the brand's values and the modules (spec 021) ──────────────────────────

  /** The tree's manifests, every one valid, or the refusals printed. */
  def manifests(root: Path, out: Output): Either[Int, Vector[ModuleManifest]] =
    val (valid, refused) = ModuleManifest.validateTree(root, ModuleManifest.ContractView.fromTree(root))
    if refused.isEmpty then Right(valid)
    else
      refused.foreach((p, rs) => rs.foreach(r => out.line(s"refused ${root.relativize(p)}: $r")))
      Left(1)

  /** `brands/<slug>/values.json`, valid, or the refusals printed. */
  def values(root: Path, slug: String, out: Output): Either[Int, Values] =
    val path = root.resolve(s"brands/$slug/values.json")
    if !Files.exists(path) then { out.err(s"no values for '$slug': $path does not exist"); Left(2) }
    else
      Values.load(path, valuesView(root)).left.map { rs =>
        rs.foreach(r => out.line(s"refused brands/$slug/values.json: $r")); 1
      }

  def list(args: Args, flag: String): Vector[String] =
    args.flags.get(flag).toVector.flatMap(_.split(',').map(_.trim).filter(_.nonEmpty))

  /** `--version core=1.2.0,casino=1.1.0`, or every named module's version on `--channel` at the vendor. */
  def versions(args: Args, modules: Vector[String], vendor: () => Vendor): Either[String, Map[String, String]] =
    (args.flags.get("--version"), args.flags.get("--channel")) match
      case (Some(_), Some(_)) => Left("--version and --channel are one or the other")
      case (Some(_), None) =>
        val pairs = list(args, "--version").map(_.split('=') match
          case Array(m, v) => Right(m -> v.stripPrefix("v"))
          case other       => Left(s"--version: '${other.mkString("=")}' is not <module>=<version>"))
        pairs.collectFirst { case Left(e) => e }.toLeft(pairs.collect { case Right(p) => p }.toMap)
      case (None, Some(channel)) =>
        val v = vendor()
        val found = modules.map(m => v.channelVersion(m, channel).map(m -> _))
        found.collectFirst { case Left(e) => e }.toLeft(found.collect { case Right(p) => p }.toMap)
      case (None, None) => Right(Map.empty)

  /** `render <slug>`: what the agent would apply, for a person to read before anything runs (FR-005). No call but the vendor's for a channel. */
  private def render(args: Args, root: Path, slug: String, out: Output, vendor: () => Vendor): Int =
    val installation = args.flags.get("--installation").map(Installation.load(root, _))
    installation match
      case Some(Left(problem)) => out.err(problem); 2
      case _ =>
        val result = for
          ms   <- manifests(root, out)
          v    <- values(root, slug, out)
          keys  = Keys.load(root)
          order <- BrandRender.ordered(list(args, "--modules"), ms).left.map { e => out.err(e); 2 }
          vs   <- versions(args, order.map(_.name), vendor).left.map { e => out.err(e); 2 }
          plan <- BrandRender.plan(v, keys, ms, list(args, "--modules"), vs, installation.flatMap(_.toOption),
                    args.flags.get("--organization")).left.map { e => out.err(e); 2 }
        yield plan
        result match
          case Left(code) => code
          case Right(plan) =>
            if args.flags.get("--format").contains("json") then out.line(PlanJson.render(plan))
            else PlanText.lines(plan).foreach(out.line)
            0

  /** `release render <version> --out <dir>`: what release.yml publishes (spec 021 T013). */
  private def releaseRender(args: Args, root: Path, tag: String, out: Output): Int =
    (ReleaseRender.version(tag), args.flags.get("--out")) match
      case (Left(problem), _) => out.err(problem); 2
      case (_, None)          => out.err("release render needs --out <dir>"); 2
      case (Right(version), Some(dir)) =>
        ReleaseRender.all(root, version, root.resolve(dir)) match
          case Left(refusals) =>
            refusals.foreach((file, r) => out.line(s"refused $file: $r")); 1
          case Right(files) =>
            files.foreach(f => out.line(s"rendered ${f.getFileName.toString.stripSuffix(".json")} $version ${root.relativize(f)}")); 0

  /** Every manifest under modules/ and every values file under brands/: the CI's check. */
  private def validateAll(root: Path, out: Output): Int =
    val (valid, refused) = ModuleManifest.validateTree(root, ModuleManifest.ContractView.fromTree(root))
    valid.foreach(m => out.line(s"valid module ${m.name} (${m.kind}): ${m.services.size} services, ${m.topics.size} topics"))
    refused.foreach((p, rs) => rs.foreach(r => out.line(s"refused ${root.relativize(p)}: $r")))
    val view = valuesView(root)
    val brands = brandSlugs(root).map(s => s -> Values.load(root.resolve(s"brands/$s/values.json"), view))
    brands.foreach {
      case (_, Right(v)) => out.line(s"valid values ${v.slug}")
      case (s, Left(rs)) => rs.foreach(r => out.line(s"refused brands/$s/values.json: $r"))
    }
    out.line("calls: 0")
    if refused.isEmpty && brands.forall(_._2.isRight) then 0 else 1

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
              else if manifest.projectId == "tools" then
                // spec 020: the simulator stands in for a game provider; it never runs where money is real.
                Vector(Problem("real-money", "project 'tools' holds the game provider simulator, which never runs on an installation that takes real money"))
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
        // Each `setting:` the manifest's services read, and what this installation gives it; an unset one is
        // left out of the service's environment, so the service refuses that provider by name.
        Render.settingUses(manifest, inst).foreach { u =>
          val state = u.value match
            case Some(_)             => "set"
            case None if inst.isEmpty => "unresolved: no --installation"
            case None                => s"unset in ${inst.get.name}: ${u.variable} left out"
          out.line(s"  setting ${u.setting} -> ${u.service}.${u.variable}: $state")
        }
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
        .filter(d => Files.exists(d.resolve("values.json")))
        .map(_.getFileName.toString)
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
            val operators = () => Option.when(manifest.isInstanceOf[BrandManifest])(Realm.operators(root))
            val result = Apply.run(plan, cp, Apply.Options(organization, values, realm, operators))
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
    val candidates =
      if which.endsWith(".json") then Vector(root.resolve(which))
      else Vector(root.resolve("shared").resolve(which).resolve("manifest.json"))
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
