package io.eitheror.casino.modules

import com.thinkmorestupidless.ankka.controlplane.api.{GrantRequest as _, Installation as _, *}
import io.eitheror.casino.contract.Layout
import io.eitheror.casino.contract.Layout.{Grant, GrantTarget, Retention}

/** One topic as it will be declared, and what the brand's status should say about it. */
final case class TopicPlan(declaration: TopicDeclaration, request: TopicRequest, notes: Vector[String])

/** Everything an apply will make true, computed from the manifest without a call. */
final case class Plan(
    projectId: String,
    name: String,
    topics: Vector[TopicPlan],
    descriptors: Vector[ServiceDescriptor],
    exposed: Vector[String],
    secrets: Vector[SecretRef],
    grantsMade: Vector[GrantRequest],
    grantsReceived: Vector[GrantRequest],
    realm: Option[String]
)

object Render:

  /** Services written in TypeScript, hosted as a process beside ankka's sidecar (spec 016). psp-gateway
    * was here while spec 005 planned it in TypeScript; it is Scala (005 R1), embedded like the rest.
    */
  val ProcessHosted: Set[String] = Set("partner-api")

  /** `secret:<name>/<key>` in a manifest's env is a reference to a project secret, never a value. */
  private val SecretReference = "secret:([a-z][a-z0-9-]*)/([A-Z][A-Z0-9_]*)".r

  /**
   * `setting:<KEY>` in a manifest's env is the installation's setting of that name (`settings.env`), so one
   * manifest serves every installation: a provider's base URL is psp-stub's on dev-kind and nothing on gke.
   * A setting the installation lacks, or leaves empty, is left out of the descriptor, and the service
   * refuses by name until an operator sets it (spec 005 FR-020).
   */
  private val SettingReference = "setting:([A-Z][A-Z0-9_]*)".r

  /** One env entry's setting reference: (service, variable, setting, the installation's value if any). */
  final case class SettingUse(service: String, variable: String, setting: String, value: Option[String])

  /** Every `setting:` reference a manifest's services make, resolved against the installation when given. */
  def settingUses(manifest: Manifest, installation: Option[Installation]): Vector[SettingUse] =
    manifest.services.flatMap(s => s.env.toVector.sortBy(_._1).collect {
      case (k, SettingReference(key)) => SettingUse(s.name, k, key, installation.flatMap(_.get(key)))
    })

  def plan(manifest: Manifest, installation: Option[Installation], brands: Vector[String] = Vector.empty): Plan =
    Plan(
      projectId = manifest.projectId,
      name = manifest.name,
      topics = manifest.topics.map(topic(_, installation)),
      descriptors = manifest.services.map(descriptor(manifest, _, installation, brands)),
      exposed = manifest.services.filter(_.expose).map(_.name),
      secrets = manifest.secrets,
      grantsMade = (manifest match
        case shared: SharedManifest => shared.grantsFor(brands)
        case other                  => other.grantsMade
      ).map(request),
      grantsReceived = manifest match
        case brand: BrandManifest => brand.grantsReceived.map(request)
        case _                    => Vector.empty,
      realm = manifest match
        case brand: BrandManifest => Some(s"casino-${brand.slug}")
        case _                    => None
    )

  def request(grant: Grant): GrantRequest = GrantRequest(grant.principal, target(grant.target))

  def target(target: GrantTarget): String = target match
    case GrantTarget.Route(service, method, path) => s"route:$service $method $path"
    case GrantTarget.Topic(name, right)           => s"topic:$name:$right"
    case GrantTarget.Erasure                      => "erasure"

  /** The contract's schema for a topic, from the contract artifact (`contract/schemas`). */
  def schema(topic: String): Option[String] =
    Option(getClass.getResourceAsStream(s"/$topic.v1.json")).map { in =>
      try String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally in.close()
    }

  def topic(declaration: TopicDeclaration, installation: Option[Installation]): TopicPlan =
    val contractName = s"${declaration.name}.v1"
    val schemaText   = schema(declaration.name)
    // ankka 0.11 declares partitions and a contract; retention is ankka 043's. Until then the topic
    // gets the installation's default, and the status says so rather than pretending otherwise.
    val retentionNotes =
      Vector("retention unset: platform lacks 043") ++
        installation.flatMap(_.longestRetentionDays).flatMap { longest =>
          (declaration.retention, longest) match
            case (Retention.Fact, Some(days)) =>
              Some(s"retention below intent: kept for a fact topic, the installation allows $days days")
            case (Retention.Days(want), Some(days)) if want > days =>
              Some(s"retention below intent: $want days wanted, the installation allows $days")
            case _ => None
        }
    val schemaNotes = Option.when(schemaText.isEmpty)(s"no schema for $contractName in the contract artifact").toVector
    TopicPlan(
      declaration,
      TopicRequest(declaration.partitions, schemaText.map(contractName -> _.trim)),
      retentionNotes ++ schemaNotes
    )

  /**
   * The brands whose hierarchy shared payments' `merchant` seeds on start (spec 004 FR-003, research R15):
   * a JSON list of `{slug}` from every brand manifest in the repository, sorted, since the tool cannot call
   * a service and the service cannot read the repository.
   */
  def merchantBrands(brands: Vector[String]): String =
    brands.sorted.map(b => s"""{"slug":"$b"}""").mkString("[", ",", "]")

  def descriptor(manifest: Manifest, pin: ServicePin, installation: Option[Installation] = None, brands: Vector[String] = Vector.empty): ServiceDescriptor =
    // Tokens carry the public issuer; a service reads the keys from inside the cluster when the installation
    // says where (KEYCLOAK_JWKS_BASE), since the public name may not resolve to Keycloak from a pod.
    val keys = installation.flatMap(_.get("KEYCLOAK_JWKS_BASE")).filter(_.nonEmpty)
    def issuer(name: String, realm: String, base: String, audience: String) = Render.issuer(name, realm, base, keys, audience)
    val auth = manifest match
      case brand: BrandManifest =>
        Vector("ANKKA_AUTH_ISSUERS" -> "player,staff", "ANKKA_AUTH_REALM" -> s"casino-${brand.slug}") ++
          issuer("PLAYER", s"casino-${brand.slug}", brand.issuerBase, brand.playerAudience) ++
          issuer("STAFF", "eitheror", brand.issuerBase, brand.staffAudience)
      case shared: SharedManifest =>
        Vector("ANKKA_AUTH_ISSUERS" -> "staff", "ANKKA_AUTH_REALM" -> "eitheror") ++
          issuer("STAFF", "eitheror", shared.issuerBase, shared.staffAudience)
    val identity = Vector("CASINO_PROJECT" -> manifest.projectId) ++ (manifest match
      // Whether the brand takes real money: game-provider refuses a provider key document then (spec 003 FR-026).
      case brand: BrandManifest => Vector("CASINO_BRAND" -> brand.slug, "CASINO_REAL_MONEY" -> brand.realMoney.toString)
      case shared: SharedManifest if shared.projectId == "payments" && pin.name == "merchant" => Vector("MERCHANT_BRANDS" -> merchantBrands(brands))
      case _                    => Vector.empty)
    val env =
      (identity ++ auth).map((k, v) => EnvVar(k, value = Some(v))) ++
        pin.env.toVector.sortBy(_._1).flatMap {
          case (k, SecretReference(name, key)) => Some(EnvVar(k, secretKeyRef = Some(SecretKeyRef(name, key))))
          case (k, SettingReference(key))      => installation.flatMap(_.get(key)).map(v => EnvVar(k, value = Some(v)))
          case (k, v)                          => Some(EnvVar(k, value = Some(v)))
        }
    val hosting = if ProcessHosted.contains(pin.name) then ServiceSpec.Process else ServiceSpec.Embedded
    val process = for
      cpu    <- pin.cpu
      memory <- pin.memory
    yield ProcessResources(cpu, memory)
    ServiceDescriptor(
      pin.name,
      ServiceSpec(
        image = pin.image,
        env = env,
        runtime = Some(manifest.runtime),
        hosting = hosting,
        protocol = Option.when(hosting == ServiceSpec.Process)("1.0"),
        resources = ServiceResources(instanceType = pin.instanceType.getOrElse("small"), process = process),
        provisionObjectStorage = pin.objectStorage
      )
    )

  /** One issuer's settings: the public issuer, and its keys read from `keys` (in the cluster) when given. */
  def issuer(name: String, realm: String, issuerBase: String, keys: Option[String], audience: String): Vector[(String, String)] =
    val issuer = s"${issuerBase.stripSuffix("/")}/realms/$realm"
    Vector(
      s"ANKKA_AUTH_${name}_ISSUER"   -> issuer,
      s"ANKKA_AUTH_${name}_JWKS_URL" -> s"${keys.getOrElse(issuerBase).stripSuffix("/")}/realms/$realm/protocol/openid-connect/certs",
      s"ANKKA_AUTH_${name}_AUDIENCE" -> audience
    )

/** One module as it would be applied in a brand's project (spec 021 data-model.md, "Plan"). */
final case class ModulePlan(
    name: String,
    kind: String,
    version: Option[String],
    dependencies: Vector[String],
    topics: Vector[TopicPlan],
    /** In the manifest's order, which is the order they are applied and waited for. */
    services: Vector[ServiceDescriptor],
    exposed: Vector[String],
    grantsMade: Vector[GrantRequest],
    grantsReceived: Vector[GrantRequest],
    retention: String,
    requires: Vector[ModuleManifest.Requirement],
    /** The configuration keys the brand's values do not supply: the module is held until each is set (FR-009). */
    missing: Vector[String],
    /** Every configuration key the manifest names, which the agent finds in `tenant-values` before applying. */
    configuration: Vector[String] = Vector.empty
)

/**
 * What the agent would apply in one brand's project, from the brand's values, the manifests its
 * entitlements (or `--modules`) name and the versions a channel (or `--version`) gives (spec 021
 * data-model.md). A module missing a key is held, not planned: it is in `held`, naming the keys.
 */
final case class BrandPlan(
    project: String,
    slug: String,
    realMoney: Boolean,
    modules: Vector[ModulePlan],
    held: Vector[ModulePlan],
    /** The module names, core first and each after its dependencies. */
    order: Vector[String] = Vector.empty
):
  /** Every module, planned or held, in `order`. */
  def inOrder: Vector[ModulePlan] = (modules ++ held).sortBy(m => order.indexOf(m.name))
  def missing: Vector[(String, String)] = held.flatMap(m => m.missing.map(m.name -> _))
  def services: Vector[ServiceDescriptor] = modules.flatMap(_.services)
  def module(name: String): Option[ModulePlan] = (modules ++ held).find(_.name == name)

object BrandRender:

  /** The project secret every secret key is read from, as the vendor's agent renders it. */
  val TenantValues = "tenant-values"

  /**
   * The modules named, with their dependencies, `core` first and each after what it depends on.
   * A name with no manifest, or a dependency cycle, is an error naming it.
   */
  def ordered(named: Vector[String], manifests: Vector[ModuleManifest]): Either[String, Vector[ModuleManifest]] =
    val byName = manifests.map(m => m.name -> m).toMap
    def visit(name: String, path: Vector[String], acc: Vector[ModuleManifest]): Either[String, Vector[ModuleManifest]] =
      if acc.exists(_.name == name) then Right(acc)
      else if path.contains(name) then Left(s"modules depend on each other: ${(path :+ name).mkString(" -> ")}")
      else
        byName.get(name) match
          case None => Left(s"no module '$name'${path.lastOption.fold("")(p => s" (a dependency of $p)")}")
          case Some(m) =>
            m.dependencies.map(_.module).foldLeft[Either[String, Vector[ModuleManifest]]](Right(acc))((a, d) => a.flatMap(visit(d, path :+ name, _)))
              .map(_ :+ m)
    (("core" +: named).distinct).foldLeft[Either[String, Vector[ModuleManifest]]](Right(Vector.empty))((a, n) => a.flatMap(visit(n, Vector.empty, _)))

  def plan(
      values: Values,
      keys: Keys,
      manifests: Vector[ModuleManifest],
      named: Vector[String],
      versions: Map[String, String],
      installation: Option[Installation],
      organization: Option[String] = None
  ): Either[String, BrandPlan] =
    ordered(named, manifests).map { order =>
      val project = Values.tenantProject(values.slug, organization)
      // The modules the brand runs, core among them so the value is never empty (the platform refuses an empty entry),
      // for a core service that reads a module's topic (spec 012 R7), unless the brand wrote them.
      val modules = "casino-modules" -> order.map(_.name).mkString(",")
      val known   = Values.all(values, project, installation) ++ Option.unless(values.values.contains("casino-modules"))(modules)
      val planned = order.map(module(_, values, keys, known, versions.get, installation))
      // A module held for a key holds every module that depends on it: the agent resolves dependencies first.
      val held = planned.foldLeft(Vector.empty[ModulePlan]) { (held, m) =>
        if m.missing.nonEmpty || m.dependencies.exists(d => held.exists(_.name == d)) then held :+ m else held
      }
      BrandPlan(project, values.slug, values.realMoney, planned.filterNot(held.contains), held, order.map(_.name))
    }

  private def module(m: ModuleManifest, values: Values, keys: Keys, known: Map[String, String], version: String => Option[String], installation: Option[Installation]): ModulePlan =
    val v = version(m.name)
    val missing = m.configuration.collect {
      case c if c.secret && keys.named(c.key).flatMap(values.secretFor).isEmpty => c.key
      case c if !c.secret && !known.contains(c.key)                            => c.key
    }
    ModulePlan(
      name = m.name,
      kind = m.kind,
      version = v,
      dependencies = m.dependencies.map(_.module),
      topics = m.topics.map(t => Render.topic(TopicDeclaration(t.name, t.partitions, Layout.retentionOf(t.name)), installation)),
      services = m.services.map(descriptor(_, m.secretKeys, known, v)),
      exposed = m.services.filter(_.expose).map(_.name),
      grantsMade = m.grantsMade.collect {
        // `{module, service}` is within the project and is no grant (FR-007); `{shared}` resolves to a bound instance, none today.
        case ModuleManifest.MadeGrant(ModuleManifest.Principal.Literal(p), t) => GrantRequest(p, target(t))
      },
      grantsReceived = m.grantsReceived.map(g => GrantRequest(s"service:{project}/${g.service}", target(g.target))),
      retention = m.retention,
      requires = m.requires,
      missing = missing,
      configuration = m.configuration.map(_.key)
    )

  def target(t: ModuleManifest.Target): String = t match
    case ModuleManifest.Target.Route(service, method, path) => s"route:$service $method $path"
    case ModuleManifest.Target.Topic(topic, right)          => s"topic:$topic:$right"

  /** A service's descriptor as the agent renders it: keys to values, secret keys to `tenant-values`, the image at the version. */
  def descriptor(s: ModuleManifest.Service, secretKeys: Set[String], known: Map[String, String], version: Option[String]): ServiceDescriptor =
    val env = s.env.flatMap { e =>
      e.key match
        case Some(k) if secretKeys.contains(k) => Some(EnvVar(e.name, secretKeyRef = Some(SecretKeyRef(TenantValues, k))))
        case Some(k)                           => known.get(k).map(v => EnvVar(e.name, value = Some(v)))
        case None                              => e.value.map(v => EnvVar(e.name, value = Some(v)))
    }
    val d       = s.descriptor
    val hosting = Option(d.get("hosting")).map(_.asText()).getOrElse(ServiceSpec.Embedded)
    val image   = version.fold(s.image)(v => s.image.replaceAll(":0\\.0\\.0$", s":$v"))
    ServiceDescriptor(
      s.name,
      ServiceSpec(
        image = image,
        env = env,
        runtime = Option(d.get("runtime")).map(_.asText()),
        hosting = hosting,
        protocol = Option(d.get("protocol")).map(_.asText()),
        resources = ServiceResources(instanceType = Option(d.path("resources").get("instanceType")).map(_.asText()).getOrElse("small")),
        provisionObjectStorage = s.objectStorage
      )
    )
