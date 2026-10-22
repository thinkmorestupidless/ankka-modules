package io.eitheror.casino.contract

/**
 * The shape every brand and shared project is built into (spec 001 FR-001 to FR-003, FR-011, FR-016,
 * as spec 021 amends them): a brand's services, topics and grants are its modules', read from the
 * manifests; the shared projects are spec 001's. Services read it for names they share.
 */
object Layout:

  /**
   * One module as the contract reads it from `modules/<module>/module.json` (spec 021 T009): its
   * services in order, its topics and the grants it makes. The manifests are packaged with the
   * contract artifact, so every service reads the same list the modules tool validates.
   */
  final case class Module(name: String, kind: String, services: Vector[String], topics: Vector[String], grantsMade: Vector[Grant])

  /** The modules of the tree, `core` first. */
  lazy val Modules: Vector[Module] = ModuleResources.read()

  /** The services of a brand project's modules (spec 021 FR-001), each in one module only. */
  lazy val Services: Vector[String] = Modules.flatMap(_.services)

  /** The module a brand service belongs to. */
  def moduleOf(service: String): Option[String] = Modules.find(_.services.contains(service)).map(_.name)

  /** Brand services that get an object-storage bucket (research R12). */
  val ObjectStorageServices: Set[String] = Set("kyc", "reporting")

  /**
   * The shared projects and their services (FR-003). `tools` holds the game provider simulator
   * (spec 020), which reaches a brand only through its public provider routes (constitution III).
   */
  val SharedProjects: Map[String, Vector[String]] = Map(
    "payments"  -> Vector("merchant", "psp-gateway", "ingest"),
    "programme" -> Vector("affiliate-programme"),
    "reasoner"  -> Vector("audit"),
    "tools"     -> Vector("game-provider-simulator")
  )

  /**
   * Services whose every route is of class `service`: never exposed (contracts/routes.md). Empty since
   * spec 004 (research R1, 2026-10-12): `merchant` serves the payments backoffice and the invitation
   * page from a browser, so it is exposed and its service-class routes refuse a token by their ACL.
   */
  val ServiceOnly: Set[String] = Set.empty

  /** Ids no brand may take: the shared projects and ankka's own reservations (FR-001, research R2). */
  val ReservedSlugs: Set[String] = SharedProjects.keySet ++ Set("platform", "local", "machine")

  /**
   * A slug is at most 42 characters: `<service>-<slug>` is one DNS label of at most 63, and the
   * longest brand service, `responsible-gambling`, takes 21 with its hyphen (research R2).
   */
  lazy val MaxSlugLength: Int = 63 - Services.map(_.length + 1).max

  private val SlugPattern = "[a-z][a-z0-9-]*".r

  def slugProblems(slug: String): Vector[String] =
    Vector(
      Option.when(!SlugPattern.matches(slug) || slug.endsWith("-"))(
        s"slug '$slug': lowercase letters, digits and '-', starting with a letter, not ending with '-'"
      ),
      Option.when(slug.length > MaxSlugLength)(
        s"slug '$slug' is ${slug.length} characters; a brand's slug is at most $MaxSlugLength"
      ),
      Option.when(ReservedSlugs.contains(slug))(s"slug '$slug' is reserved")
    ).flatten

  /** The topics a brand project's modules declare (spec 001 FR-011, spec 021 FR-001), in module order. */
  lazy val BrandTopics: Vector[String] = Modules.flatMap(_.topics)

  /** Topics of the shared projects that are part of the contract (FR-011). */
  val SharedTopics: Map[String, Vector[String]] = Map(
    // platform.audit: merchant's audit records (spec 004 FR-034, research R13); how reasoner/audit reads them is spec 018's.
    "payments"  -> Vector("payments.merchants", "platform.audit"),
    "programme" -> Vector.empty,
    "reasoner"  -> Vector.empty,
    "tools"     -> Vector.empty
  )

  /** Retention a topic is declared with (FR-015): facts are kept; two topics are ephemeral. */
  enum Retention:
    case Fact
    case Days(days: Int)

  val EphemeralTopics: Map[String, Retention] =
    Map("casino.sessions" -> Retention.Days(90), "casino.push" -> Retention.Days(1))

  def retentionOf(topic: String): Retention = EphemeralTopics.getOrElse(topic, Retention.Fact)

  /** What a grant opens (ankka 040's three kinds). */
  enum GrantTarget:
    case Route(service: String, method: String, path: String)
    case Topic(name: String, right: String)
    case Erasure

  final case class Grant(principal: String, target: GrantTarget)

  /** The grants a brand's modules make, and no others (spec 021 FR-006; was spec 001 FR-016's fixed list). */
  lazy val BrandGrantsMade: Vector[Grant] = Modules.flatMap(_.grantsMade)

  /**
   * The grants a brand holds from `payments`, the principal naming the brand's own service: the
   * payout initiate, its status read and the withdrawal terminal listing (spec 005 research R12).
   * Not read from a module manifest: the vendor's `received[].from` names a module and `payments` is
   * a shared project, so `shared/payments/manifest.json` makes these per brand (spec 021 E2) until
   * `payments` is a vendor shared module.
   */
  def brandGrantsReceived(slug: String): Vector[Grant] = Vector(
    Grant(s"service:$slug/payouts", GrantTarget.Route("psp-gateway", "POST", "/v1/payments/withdrawals")),
    Grant(s"service:$slug/payouts", GrantTarget.Route("psp-gateway", "GET", "/v1/payments/withdrawals/{requestId}")),
    Grant(s"service:$slug/payouts", GrantTarget.Route("psp-gateway", "GET", "/v1/payments/withdrawal-terminals")),
    Grant(s"service:$slug/players", GrantTarget.Erasure)
  )

  /**
   * The shared services a brand's `service` routes admit by name. Before ankka 040 there is no
   * `Callers.granted`, so the brand's own grants are this list, and contract.access admits exactly
   * these callers on exactly the routes granted to them.
   */
  def grantedCaller(principal: String): Option[(String, String)] =
    principal.stripPrefix("service:").split('/') match
      case Array(project, service) if principal.startsWith("service:") => Some(project -> service)
      case _                                                           => None

/** Reads the manifests the contract artifact carries (`modules/index`, `modules/<m>/module.json`). */
private object ModuleResources:
  import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
  import scala.jdk.CollectionConverters.*

  private def resource(path: String): String =
    val in = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw IllegalStateException(s"$path is not in the contract artifact: the build packages modules/ as resources"))
    try String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally in.close()

  private def array(n: JsonNode, f: String): Vector[JsonNode] =
    Option(n.get(f)).map(_.elements().asScala.toVector).getOrElse(Vector.empty)

  private def grant(g: JsonNode): Option[Layout.Grant] =
    val t = g.path("target")
    val target = t.path("kind").asText() match
      case "route" => Layout.GrantTarget.Route(t.path("service").asText(), t.path("method").asText(), t.path("path").asText())
      case _       => Layout.GrantTarget.Topic(t.path("topic").asText(), t.path("right").asText())
    // A `{module, service}` principal is within the project and is no grant (FR-007); `{shared}` resolves at apply.
    Option(g.get("principal")).filter(_.isTextual).map(p => Layout.Grant(p.asText(), target))

  def read(): Vector[Layout.Module] =
    val mapper = ObjectMapper()
    resource("/modules/index").linesIterator.map(_.trim).filter(_.nonEmpty).toVector.map { name =>
      val node = mapper.readTree(resource(s"/modules/$name/module.json"))
      Layout.Module(
        name = node.path("name").asText(),
        kind = node.path("kind").asText(),
        services = array(node, "services").map(_.path("name").asText()),
        topics = array(node, "topics").map(_.path("name").asText()),
        grantsMade = array(node.path("grants"), "made").flatMap(grant)
      )
    }
