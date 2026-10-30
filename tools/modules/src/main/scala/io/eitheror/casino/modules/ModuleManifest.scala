package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.{JsonSchemaFactory, SpecVersion}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * A module manifest in ankka-vendor's format (vendor 004 `contracts/manifest.md`, spec 021 R1):
 * `modules/<module>/module.json`. Read only after the vendored schema accepts it, so an unknown
 * field is refused there, naming it; then this repository's contract rules
 * (`contracts/module-manifests.md`) are applied, each refusal naming the entry's path.
 */
final case class ModuleManifest(
    name: String,
    kind: String,
    description: Option[String],
    dependencies: Vector[ModuleManifest.Dependency],
    services: Vector[ModuleManifest.Service],
    topics: Vector[ModuleManifest.TopicEntry],
    grantsMade: Vector[ModuleManifest.MadeGrant],
    grantsReceived: Vector[ModuleManifest.ReceivedGrant],
    configuration: Vector[ModuleManifest.ConfigurationKey],
    requires: Vector[ModuleManifest.Requirement],
    retention: String,
    ending: Option[ModuleManifest.Ending],
    /** The document as read, for the release rendering, which rewrites images and inlines schemas. */
    json: ObjectNode
):
  def serviceNames: Vector[String] = services.map(_.name)
  def secretKeys: Set[String]      = configuration.filter(_.secret).map(_.key).toSet

object ModuleManifest:

  final case class Dependency(module: String, range: String)

  /** One `env[]` entry: a key the agent fills, a literal that is no secret, or a `secretKeyRef` (refused). */
  final case class EnvEntry(name: String, key: Option[String], value: Option[String], secretKeyRef: Boolean)

  final case class Service(name: String, descriptor: ObjectNode, readyWithin: Option[String]):
    def image: String         = descriptor.path("image").asText()
    def expose: Boolean       = descriptor.path("expose").asBoolean(false)
    def objectStorage: Boolean = descriptor.path("provisionObjectStorage").asBoolean(false)
    def exposeObjectStorage: Boolean = descriptor.path("exposeObjectStorage").asBoolean(false)
    def env: Vector[EnvEntry] =
      descriptor.path("env").elements().asScala.toVector.map { e =>
        EnvEntry(
          e.path("name").asText(),
          Option(e.get("key")).map(_.asText()),
          Option(e.get("value")).map(_.asText()),
          e.has("secretKeyRef")
        )
      }

  final case class TopicEntry(name: String, partitions: Int, retention: Option[String])

  enum Principal:
    case Literal(principal: String)
    case OfModule(module: String, service: String)
    case Shared(module: String)

  enum Target:
    case Route(service: String, method: String, path: String)
    case Topic(topic: String, right: String)

  final case class MadeGrant(principal: Principal, target: Target)
  final case class ReceivedGrant(fromModule: String, fromService: String, service: String, target: Target)

  final case class ConfigurationKey(key: String, kind: String, secret: Boolean, value: Option[JsonNode])

  /** One requirement of the installation: its condition, and the facts as `GET /installation` names them (`backups.target` …). */
  final case class Requirement(when: Option[(String, String)], facts: Vector[(String, JsonNode)])

  final case class Ending(mode: String, service: String, path: String)

  /** One reason a manifest is refused: where in the document, and why, in the contract's words. */
  final case class Refusal(path: String, message: String):
    override def toString: String = if path.isEmpty then message else s"$path: $message"

  // ── reading ────────────────────────────────────────────────────────────────

  private val mapper = ObjectMapper()

  private lazy val schema =
    val in = getClass.getResourceAsStream("/ankka-vendor-manifest.schema.json")
    try JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in)
    finally in.close()

  def load(path: Path): Either[Vector[Refusal], ModuleManifest] =
    Try(Files.readString(path)).toEither.left
      .map(e => Vector(Refusal("", s"$path cannot be read: ${e.getMessage}")))
      .flatMap(parse)

  /** JSON, then the vendor's schema, then the shape read into values. Nothing is called. */
  def parse(text: String): Either[Vector[Refusal], ModuleManifest] =
    Try(mapper.readTree(text)).toEither.left
      .map(e => Vector(Refusal("", s"the manifest is not JSON: ${e.getMessage}")))
      .flatMap {
        case node: ObjectNode =>
          val messages = schema.validate(node).asScala.toVector
          if messages.nonEmpty then
            Left(messages.map { m =>
              val at    = m.getInstanceLocation.toString.stripPrefix("$").stripPrefix(".")
              val value = Option(m.getInstanceNode).filter(_.isValueNode).map(v => s" ('${v.asText()}')").getOrElse("")
              Refusal(at, s"${m.getMessage}$value")
            }.distinct.sortBy(_.toString))
          else Right(from(node))
        case _ => Left(Vector(Refusal("", "the manifest is not a JSON object")))
      }

  private def text(n: JsonNode, f: String): String           = n.path(f).asText()
  private def optional(n: JsonNode, f: String): Option[String] = Option(n.get(f)).filterNot(_.isNull).map(_.asText())
  private def array(n: JsonNode, f: String): Vector[JsonNode]  = Option(n.get(f)).map(_.elements().asScala.toVector).getOrElse(Vector.empty)

  private def target(n: JsonNode): Target = text(n, "kind") match
    case "route" => Target.Route(text(n, "service"), text(n, "method"), text(n, "path"))
    case _       => Target.Topic(text(n, "topic"), text(n, "right"))

  private def principal(n: JsonNode): Principal =
    if n.isTextual then Principal.Literal(n.asText())
    else if n.has("shared") then Principal.Shared(text(n, "shared"))
    else Principal.OfModule(text(n, "module"), text(n, "service"))

  /** `{"backups": {"target": …}}` → `backups.target`, the vendor's fact names. */
  private def facts(n: JsonNode, prefix: String = ""): Vector[(String, JsonNode)] =
    n.properties().asScala.toVector.flatMap { e =>
      val name = if prefix.isEmpty then e.getKey else s"$prefix.${e.getKey}"
      if e.getValue.isObject then facts(e.getValue, name) else Vector(name -> e.getValue)
    }

  private def from(node: ObjectNode): ModuleManifest =
    val grants = node.path("grants")
    ModuleManifest(
      name = text(node, "name"),
      kind = text(node, "kind"),
      description = optional(node, "description"),
      dependencies = array(node, "dependencies").map(d => Dependency(text(d, "module"), text(d, "range"))),
      services = array(node, "services").map(s =>
        Service(text(s, "name"), s.get("descriptor").asInstanceOf[ObjectNode], optional(s, "readyWithin"))),
      topics = array(node, "topics").map(t => TopicEntry(text(t, "name"), t.path("partitions").asInt(), optional(t, "retention"))),
      grantsMade = array(grants, "made").map(g => MadeGrant(principal(g.get("principal")), target(g.get("target")))),
      grantsReceived = array(grants, "received").map(g =>
        ReceivedGrant(text(g.get("from"), "module"), text(g.get("from"), "service"), text(g.get("principal"), "service"), target(g.get("target")))),
      configuration = array(node, "configuration").map(c =>
        ConfigurationKey(text(c, "key"), text(c, "type"), c.path("secret").asBoolean(false), Option(c.get("value")))),
      requires = array(node, "requires").map(r =>
        Requirement(Option(r.get("when")).map(w => text(w, "key") -> text(w, "equals")), facts(r.path("installation")))),
      retention = text(node, "retention"),
      ending = Option(node.get("ending")).map(e => Ending(text(e, "mode"), text(e.get("check"), "service"), text(e.get("check"), "path"))),
      json = node
    )

  // ── the contract's rules on top of the vendor's schema ─────────────────────

  /**
   * What a manifest is checked against: the services, topics, routes and configuration keys this
   * repository's contract defines, and which modules are shared (run in a project of their own).
   * The tree's view (services with a build, `contract/keys.md`, `contract/access/routes.md`) is read once `keys.md` exists (spec 021 T003); the suites pass their own.
   */
  final case class ContractView(
      services: Set[String],
      topics: Set[String],
      routes: Set[(String, String, String)],
      keys: Set[String],
      productModules: Set[String],
      sharedModules: Set[String]
  )

  object ContractView:
    /** The modules data-model.md names, by kind; a manifest's dependency is checked against these. */
    val ProductModules: Set[String] = Set("casino", "engagement", "reporting", "support", "partner", "predictions")
    val SharedModules: Set[String]  = Set("pool")

    /**
     * The tree's contract: the brand services that have a build under `services/` or are listed as not
     * yet built in `contract/keys.md` (spec 021 T011), the contract's topics, the routes of
     * `contract/access/routes.md` and the keys of `contract/keys.md`.
     */
    def fromTree(root: Path): ContractView =
      val keys   = Keys.load(root)
      val shared = io.eitheror.casino.contract.Layout.SharedProjects.values.flatten.toSet
      ContractView(
        services = (Keys.built(root) -- shared) ++ keys.notYetBuilt.keySet,
        topics = io.eitheror.casino.contract.facts.Topics.all.map(_.name).toSet,
        routes = contractRoutes(root),
        keys = keys.names,
        productModules = ProductModules,
        sharedModules = SharedModules
      )

    /** The rows of `contract/access/routes.md`: service, method, path. */
    def contractRoutes(root: Path): Set[(String, String, String)] =
      Files.readAllLines(root.resolve("contract/access/routes.md")).asScala.toVector
        .map(_.trim)
        .filter(l => l.startsWith("|") && !l.startsWith("|---") && !l.startsWith("| Service"))
        .map(_.stripPrefix("|").stripSuffix("|").split('|').map(_.trim).toVector)
        .collect { case Vector(service, method, path, _*) => (service, method, path) }
        .toSet

  /** Every manifest under `modules/`, in the order Layout reads them: `core` first. */
  def treeManifests(root: Path): Vector[Path] =
    val dir = root.resolve("modules")
    if !Files.isDirectory(dir) then Vector.empty
    else
      val all = Files.list(dir).iterator().asScala.toVector.map(_.resolve("module.json")).filter(Files.exists(_))
        .sortBy(_.getParent.getFileName.toString)
      all.filter(_.getParent.getFileName.toString == "core") ++ all.filterNot(_.getParent.getFileName.toString == "core")

  /** Every manifest of the tree, each validated, then the rules across them (one service, one module). */
  def validateTree(root: Path, view: ContractView): (Vector[ModuleManifest], Vector[(Path, Vector[Refusal])]) =
    val results  = treeManifests(root).map(p => p -> validate(p, view))
    val valid    = results.collect { case (_, Right(m)) => m }
    val refused  = results.collect { case (p, Left(rs)) => p -> rs }
    val across   = this.across(valid)
    (valid, if across.isEmpty then refused else refused :+ (root.resolve("modules") -> across))

  /** The one product module allowed to depend on another (FR-002): `partner` on `casino`. */
  val ProductOnProduct: Set[(String, String)] = Set("partner" -> "casino")

  /** The vendor's secret patterns (vendor 004 `contracts/manifest.md`, "looks like a secret"). */
  private val SecretLiteral = "sk_live_|sk_test_|whsec_|-----BEGIN|AKIA[0-9A-Z]{16}".r
  private val LongBase64    = "[A-Za-z0-9+/=_-]{32,}".r
  private val SecretField   = "(?i).*(key|secret|token|password).*".r

  def looksLikeSecret(field: String, value: String): Boolean =
    SecretLiteral.findFirstIn(value).isDefined || (SecretField.matches(field) && LongBase64.findFirstIn(value).isDefined)

  /** Every string in the document with its path and the name it is filed under (an env entry's `name`). */
  private def literals(node: JsonNode, path: String, field: String): Vector[(String, String, String)] =
    if node.isTextual then Vector((path, field, node.asText()))
    else if node.isArray then
      node.elements().asScala.toVector.zipWithIndex.flatMap((n, i) => literals(n, s"$path[$i]", field))
    else if node.isObject then
      val filedAs = Option(node.get("name")).filter(_ => node.has("value")).map(_.asText())
      node.properties().asScala.toVector.flatMap { e =>
        val p = if path.isEmpty then e.getKey else s"$path.${e.getKey}"
        literals(e.getValue, p, filedAs.filter(_ => e.getKey == "value").getOrElse(e.getKey))
      }
    else Vector.empty

  def rules(m: ModuleManifest, view: ContractView): Vector[Refusal] =
    val kind = Vector(
      Option.when(m.name == "core" && m.kind != "core")(Refusal("kind", "module 'core' is of kind core")),
      Option.when(m.name != "core" && m.kind == "core")(Refusal("kind", s"only module 'core' is of kind core, not '${m.name}'")),
      Option.when(m.kind != "core" && !m.dependencies.exists(_.module == "core"))(Refusal("dependencies", s"a ${m.kind} module depends on 'core'"))
    ).flatten
    val dependencies = m.dependencies.zipWithIndex.collect {
      case (d, i) if m.kind == "product" && view.productModules.contains(d.module) && !ProductOnProduct.contains(m.name -> d.module) =>
        Refusal(s"dependencies[$i].module", s"a product module depends on product module '${d.module}'")
      case (d, i) if m.kind == "shared" && d.module != "core" =>
        Refusal(s"dependencies[$i].module", s"a shared module depends on the core only, not '${d.module}'")
    }
    val services = m.services.zipWithIndex.flatMap { (s, i) =>
      Option.when(!view.services.contains(s.name))(Refusal(s"services[$i].name", s"service '${s.name}' is not in the contract")).toVector ++
        Option.when(s.image.endsWith(":latest"))(Refusal(s"services[$i].descriptor.image", s"an image pins a version; 'latest' does not")).toVector ++
        s.env.zipWithIndex.flatMap { (e, j) =>
          val at = s"services[$i].descriptor.env[$j]"
          Option.when(e.secretKeyRef)(Refusal(at, s"a value where a key is expected: '${e.name}' names a secretKeyRef; the agent writes those")).toVector ++
            e.key.filterNot(view.keys.contains).map(k => Refusal(s"$at.key", s"configuration key '$k' is not in the contract")).toVector ++
            e.key.filter(view.keys.contains).filterNot(k => m.configuration.exists(_.key == k))
              .map(k => Refusal(s"$at.key", s"configuration key '$k' is not one this module names")).toVector
        }
    }
    val topics = m.topics.zipWithIndex.collect {
      case (t, i) if !view.topics.contains(t.name) => Refusal(s"topics[$i].name", s"topic '${t.name}' is not in the contract")
    }
    def targetRefusal(at: String, t: Target): Option[Refusal] = t match
      case Target.Route(s, method, p) if !view.routes.contains((s, method, p)) =>
        Some(Refusal(at, s"grant target 'route $method $s$p' is not in the contract"))
      case Target.Topic(topic, _) if !view.topics.contains(topic) =>
        Some(Refusal(at, s"grant target 'topic $topic' is not in the contract"))
      case _ => None
    val made = m.grantsMade.zipWithIndex.flatMap { (g, i) =>
      targetRefusal(s"grants.made[$i].target", g.target).toVector ++ (g.principal match
        case Principal.OfModule(module, service) if !view.sharedModules.contains(module) =>
          Vector(Refusal(s"grants.made[$i].principal", s"a grant within the brand project is not a grant: '$module/$service' runs in the same project (FR-007)"))
        case _ => Vector.empty)
    }
    val received = m.grantsReceived.zipWithIndex.flatMap((g, i) => targetRefusal(s"grants.received[$i].target", g.target).toVector)
    val configuration = m.configuration.zipWithIndex.flatMap { (c, i) =>
      Option.when(c.value.isDefined)(Refusal(s"configuration[$i]", s"a value where a key is expected: '${c.key}'")).toVector ++
        Option.when(!view.keys.contains(c.key))(Refusal(s"configuration[$i].key", s"configuration key '${c.key}' is not in the contract")).toVector
    }
    val requirements = m.requires.zipWithIndex.flatMap { (r, i) =>
      r.when.collect { case (k, _) if !m.configuration.exists(_.key == k) =>
        Refusal(s"requires[$i].when.key", s"configuration key '$k' is not one this module names")
      }.toVector
    }
    val secrets = literals(m.json, "", "").collect {
      case (path, field, value) if looksLikeSecret(field, value) => Refusal(path, s"'$value' looks like a secret")
    }
    kind ++ dependencies ++ services ++ topics ++ made ++ received ++ configuration ++ requirements ++ secrets

  /** Rules across the manifests of one repository: one service, one module. */
  def across(manifests: Vector[ModuleManifest]): Vector[Refusal] =
    manifests.flatMap(m => m.serviceNames.map(_ -> m.name)).groupBy(_._1).toVector.sortBy(_._1).collect {
      case (service, owners) if owners.size > 1 =>
        Refusal("services", s"service '$service' is in modules ${owners.map(_._2).mkString(" and ")}")
    }

  /** Schema, then rules; a manifest at `modules/<name>/module.json` must be named `<name>`. */
  def validate(path: Path, view: ContractView): Either[Vector[Refusal], ModuleManifest] =
    load(path).flatMap { m =>
      val directory = Option(path.getParent).map(_.getFileName.toString)
      val named = directory.filter(d => path.getFileName.toString == "module.json" && d != m.name)
        .map(d => Refusal("name", s"module '${m.name}' is in directory '$d'")).toVector
      val problems = named ++ rules(m, view)
      if problems.isEmpty then Right(m) else Left(problems)
    }
