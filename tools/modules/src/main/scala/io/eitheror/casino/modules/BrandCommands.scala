package io.eitheror.casino.modules

import io.eitheror.casino.modules.Main.Args

import java.nio.file.Path

/**
 * The brand-side writes that are no module's (spec 021 FR-005, contracts/values.md): `values push`,
 * `secrets`, `hostnames`, `realm`. The secret ones go straight to the brand's project, never through
 * the vendor.
 */
object BrandCommands:

  /** Where a brand's values go: the vendor (its tenant) or, locally, the project secret. */
  enum Mode:
    case Local(installation: Installation)
    case Vendor(tenant: String)

  /**
   * Local when the installation named has no `VENDOR_URL` in its overlay and `ANKKA_VENDOR_URL` is unset
   * (contracts/modules-tool.md, "Local mode"); else through the vendor, to the tenant `--organization` names.
   */
  def mode(args: Args, installation: Option[Installation], env: Map[String, String]): Either[String, Mode] =
    installation match
      case Some(i) if i.get("VENDOR_URL").isEmpty && !env.contains("ANKKA_VENDOR_URL") => Right(Mode.Local(i))
      case _ =>
        args.flags.get("--organization").map(Mode.Vendor(_))
          .toRight("through the vendor, --organization names the tenant; locally, --installation names an overlay with no VENDOR_URL")

  /** The project the brand's values and secrets go to: the slug locally, the vendor's tenant project otherwise. */
  def project(values: Values, mode: Mode): String = mode match
    case Mode.Local(_)       => Values.tenantProject(values.slug, None)
    case Mode.Vendor(tenant) => Values.tenantProject(values.slug, Some(tenant))

  /** The facts a real-money check reads: the control plane's `GET /installation` when it answers, else the overlay. */
  def facts(installation: Option[Installation], cp: Option[ControlPlane]): Option[Readiness.Facts] =
    cp.flatMap(c => scala.util.Try(c.installation()).toOption.flatten.map(Readiness.fromControlPlane(_, "GET /installation")))
      .orElse(installation.map(Readiness.fromOverlay))

  /** The real-money refusals for these values, from the core's requirements (research R6); none for a brand not taking real money. */
  def realMoney(values: Values, project: String, installation: Option[Installation], facts: Option[Readiness.Facts], force: Boolean = false): Vector[String] =
    if !values.realMoney && !force then Vector.empty
    else
      val all = Values.all(values, project, installation) + ("casino-real-money" -> "true")
      facts match
        case None    => Vector("a brand taking real money needs --installation to check the core's requirements against")
        case Some(f) => Readiness.check(Readiness.core.requires, all, f).map(u => s"real money: ${Readiness.conditionOf(u.fact)}: $u")

  /** `values push <slug>`: the non-secret values and the derived keys; refuses a real-money brand on an unfit installation. */
  def push(args: Args, root: Path, slug: String, out: Output, controlPlane: () => ControlPlane, vendor: () => Vendor, env: Map[String, String]): Int =
    loadInstallation(args, root, out).flatMap { inst =>
      Main.values(root, slug, out).flatMap(v => mode(args, inst, env).left.map { e => out.err(e); 2 }.map(m => (inst, v, m)))
    } match
      case Left(code) => code
      case Right((inst, v, m)) =>
        val project = this.project(v, m)
        val cp      = m match
          case Mode.Local(_) => Some(controlPlane())
          case _             => None
        val refusals = realMoney(v, project, inst, facts(inst, cp))
        if refusals.nonEmpty then
          refusals.foreach(r => out.line(s"refused $r")); 1
        else
          val entries = Values.all(v, project, inst)
          m match
            case Mode.Local(_) =>
              val c = cp.get
              if c.project(project).isEmpty then
                out.line(s"failed project $project does not exist: apply creates it"); 1
              else
                c.setSecret(project, BrandRender.TenantValues, entries)
                out.line(s"changed secret ${BrandRender.TenantValues} of $project set (${entries.size} values: ${entries.keys.toVector.sorted.mkString(", ")})"); 0
            case Mode.Vendor(tenant) =>
              vendor().setValues(tenant, entries) match
                case Left(e) => out.line(s"failed $e"); 1
                case Right(_) => out.line(s"changed values of tenant $tenant set at the vendor (${entries.size} values)"); 0

  /**
   * `secrets <slug> --secrets <file>`: each secret key of the brand's modules into `tenant-values` from the
   * line its `contract/keys.md` row names, and each named secret as the brand declares it; straight to
   * the project, never through the vendor.
   */
  def secrets(args: Args, root: Path, slug: String, out: Output, controlPlane: () => ControlPlane, env: Map[String, String]): Int =
    val file = args.flags.get("--secrets")
    if file.isEmpty then { out.err("secrets needs --secrets <file>: <secret>/<KEY>=<value> lines, kept outside the repository"); return 2 }
    (for
      inst <- loadInstallation(args, root, out)
      v    <- Main.values(root, slug, out)
      m    <- mode(args, inst, env).left.map { e => out.err(e); 2 }
      supplied <- SecretsFile.load(root.resolve(file.get)).left.map { e => out.err(e); 2 }
    yield (v, m, supplied)) match
      case Left(code) => code
      case Right((v, m, supplied)) =>
        val project = this.project(v, m)
        val cp      = controlPlane()
        val keys    = Keys.load(root)
        val tenant = keys.rows.filter(_.secret).flatMap { row =>
          v.secretFor(row).flatMap((secret, entry) => supplied.get(secret).flatMap(_.get(entry))).map(row.key -> _)
        }.toMap
        val named = v.secrets.flatMap(s => Option(supplied.getOrElse(s.name, Map.empty).filter((k, _) => s.keys.contains(k))).filter(_.nonEmpty).map(s.name -> _))
        if cp.project(project).isEmpty then { out.line(s"failed project $project does not exist: apply creates it"); return 1 }
        if tenant.nonEmpty then
          cp.setSecret(project, BrandRender.TenantValues, tenant)
          out.line(s"changed secret ${BrandRender.TenantValues} of $project set (${tenant.keys.toVector.sorted.mkString(", ")})")
        named.foreach { (name, entries) =>
          cp.setSecret(project, name, entries)
          out.line(s"changed secret $name of $project set (${entries.keys.toVector.sorted.mkString(", ")})")
        }
        v.secrets.foreach(s => s.keys.filterNot(k => supplied.get(s.name).exists(_.contains(k))).foreach(k => out.line(s"note ${s.name}/$k is not in the secrets file")))
        0

  /** `hostnames <slug>`: each of the values' hostnames through the control plane's hostname routes. */
  def hostnames(args: Args, root: Path, slug: String, out: Output, controlPlane: () => ControlPlane, env: Map[String, String]): Int =
    (for
      inst <- loadInstallation(args, root, out)
      v    <- Main.values(root, slug, out)
      m    <- mode(args, inst, env).left.map { e => out.err(e); 2 }
    yield (v, m)) match
      case Left(code) => code
      case Right((v, m)) =>
        addHostnames(v, this.project(v, m), controlPlane(), out); 0

  def addHostnames(v: Values, project: String, cp: ControlPlane, out: Output): Unit =
    v.hostnames.toVector.sorted.foreach { (service, host) =>
      cp.addHostname(project, service, host) match
        case Some(_) => out.line(s"changed hostname $host added to $service")
        case None    => out.line(s"note hostname $host for $service waits: the platform has no custom hostnames")
    }

  /** `realm <slug>`: the brand's realm and its operator role in `eitheror`, as `brands apply` step 8 did. */
  def realm(args: Args, root: Path, slug: String, out: Output, realms: Option[RealmAdmin]): Int =
    (for
      inst <- loadInstallation(args, root, out)
      v    <- Main.values(root, slug, out)
      supplied <- args.flags.get("--secrets").fold(Right(Map.empty))(f => SecretsFile.load(root.resolve(f))).left.map { e => out.err(e); 2 }
    yield (inst, v, supplied)) match
      case Left(code) => code
      case Right((inst, v, supplied)) =>
        realms.orElse(KeycloakAdmin.fromEnvironment(v.issuerBase).toOption) match
          case None => out.err("realm needs CASINO_KEYCLOAK_ADMIN_USERNAME and CASINO_KEYCLOAK_ADMIN_PASSWORD"); 2
          case Some(admin) =>
            val own  = supplied.getOrElse(s"${v.slug}-players", Map.empty)
            val json = Realm.withPlayers(Realm.render(Realm.template(root), v.slug, frontend(v, inst)), playersUrl(v, inst), own)
            Realm.ensure(admin, v.slug, json, Some(Realm.operators(root))).foreach(c => out.line(s"changed $c"))
            Seq("REALM_ADMIN", "REALM_SECRET", "WEBHOOK_SECRET").filterNot(own.contains)
              .foreach(k => out.line(s"note ${v.slug}-players/$k is not in the secrets file: a realm created now lacks it"))
            0

  /**
   * Where the realm's plugins reach the brand's `players`: the installation's gateway name for it,
   * `players-<project>.<base>` as psp-gateway's is (`PSP_GATEWAY_PUBLIC_URL`), the base being the
   * installation's `PLAYER_SITE_BASE`.
   */
  def playersUrl(v: Values, inst: Option[Installation]): Option[String] =
    inst.flatMap(_.get("PLAYER_SITE_BASE")).map(b => s"https://players-${v.slug}.$b")

  /** The player site the realm redirects to: the brand's `player-site-base`, else the installation's base. */
  def frontend(v: Values, inst: Option[Installation]): String =
    v.values.get("player-site-base")
      .orElse(inst.flatMap(_.get("PLAYER_SITE_BASE")).map(b => s"https://${v.slug}.$b"))
      .getOrElse(s"https://${v.slug}.invalid")

  def loadInstallation(args: Args, root: Path, out: Output): Either[Int, Option[Installation]] =
    args.flags.get("--installation") match
      case None => Right(None)
      case Some(name) => Installation.load(root, name).map(Some(_)).left.map { e => out.err(e); 2 }
