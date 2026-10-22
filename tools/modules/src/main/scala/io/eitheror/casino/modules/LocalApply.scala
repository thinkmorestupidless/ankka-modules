package io.eitheror.casino.modules

import com.thinkmorestupidless.ankka.controlplane.api.ServiceLifecycle

import scala.collection.mutable

/**
 * `apply <slug>` in local mode (contracts/modules-tool.md, "Local mode"): the tool plays the vendor's
 * agent on an installation with no vendor, in the agent's order (ankka-vendor 004 `contracts/agent.md`):
 * the project, the brand's values into `tenant-values` (as `values push` would), then per module, core
 * first and each after its dependencies: the keys checked against `tenant-values`, topics with their
 * schemas, services in manifest order and waited for, then grants, made here since locally the tool is
 * the project's owner; then hostnames and the realm. A module whose key is missing is held, naming the
 * key, and so is every module after it that depends on it.
 */
object LocalApply:

  final case class Options(
      organization: String,
      realm: Option[(RealmAdmin, () => String)] = None,
      operatorsRealm: () => Option[String] = () => None,
      /** Waits for one service to be Ready; the tests' control plane is Ready at once. */
      ready: (ControlPlane, String, String) => Either[String, Unit] = LocalApply.poll()
  )

  /** `GET /services/{p}/{name}` every `every` until Ready, up to `within`; failed naming the service. */
  def poll(every: java.time.Duration = java.time.Duration.ofSeconds(5), within: java.time.Duration = java.time.Duration.ofMinutes(10))
      : (ControlPlane, String, String) => Either[String, Unit] =
    (cp, project, name) =>
      val deadline = System.nanoTime() + within.toNanos
      def ready = cp.services(project).find(_.name == name).exists(_.lifecycle == ServiceLifecycle.Ready)
      while !ready && System.nanoTime() < deadline do Thread.sleep(every.toMillis)
      Either.cond(ready, (), s"service $name not Ready within ${within.toMinutes}m")

  /** The installation is local: its overlay names no vendor and `ANKKA_VENDOR_URL` is unset. Else the refusal. */
  def local(installation: Installation, env: Map[String, String]): Either[String, Unit] =
    installation.get("VENDOR_URL").orElse(env.get("ANKKA_VENDOR_URL")) match
      case Some(url) => Left(s"installation ${installation.name} has a vendor ($url): its agent applies modules; use values push and the vendor's entitlements")
      case None      => Right(())

  def run(plan: BrandPlan, values: Values, installation: Option[Installation], cp: ControlPlane, options: Options): ApplyResult =
    val changes = mutable.ArrayBuffer.empty[String]
    val notes   = mutable.ArrayBuffer.empty[String]
    def step[A](name: String)(body: => A): Either[String, A] =
      try Right(body)
      catch case e: ControlPlaneError => Left(s"$name: ${e.message}")

    val project = plan.project
    val result = for
      _ <- step("project") {
        if cp.project(project).isEmpty then
          cp.createProject(project, values.name, options.organization)
          changes += s"project $project created"
      }
      present <- step("values") {
        val entries = Values.all(values, project, installation)
        // Entries are added or replaced and every other entry kept (control plane reference), so the secret keys `secrets` set survive.
        cp.setSecret(project, BrandRender.TenantValues, entries)
        notes += s"values ${BrandRender.TenantValues} written (${entries.size} values)"
        cp.secrets(project).find(_.name == BrandRender.TenantValues).map(_.entries.toSet).getOrElse(Set.empty)
      }
      _ <- {
        val held = mutable.ArrayBuffer.empty[String]
        plan.inOrder.foldLeft[Either[String, Unit]](Right(())) { (acc, m) =>
          acc.flatMap { _ =>
            val missing = m.configuration.filterNot(present)
            val waiting = m.dependencies.filter(held.contains)
            if missing.nonEmpty || waiting.nonEmpty then
              held += m.name
              notes += s"module ${m.name} held: ${if missing.nonEmpty then s"missing ${missing.mkString(", ")}: set it with values push or secrets" else s"its dependency ${waiting.mkString(", ")} is held"}"
              Right(())
            else module(project, m, cp, options, changes, notes)
          }
        }
      }
      _ <- step("hostnames")(BrandCommands.addHostnames(values, project, cp, new Output:
        def line(text: String) = if text.startsWith("changed ") then changes += text.stripPrefix("changed ") else notes += text.stripPrefix("note ")
        def err(text: String)  = notes += text
      ))
      _ <- step("realm") {
        options.realm match
          case Some((admin, json)) => changes ++= Realm.ensure(admin, values.slug, json(), options.operatorsRealm())
          case None => notes += s"realm casino-${values.slug} not checked: no Keycloak administrator (CASINO_KEYCLOAK_ADMIN_USERNAME, _PASSWORD)"
      }
    yield ()
    ApplyResult(changes.toVector, notes.toVector, result.left.toOption)

  /** One module: topics, its services in order each waited for, exposure, grants (spec 001's diffing, per module). */
  private def module(project: String, m: ModulePlan, cp: ControlPlane, options: Options, changes: mutable.ArrayBuffer[String], notes: mutable.ArrayBuffer[String]): Either[String, Unit] =
    val legacy = Plan(project, project, m.topics, m.services, m.exposed, Vector.empty, m.grantsMade, m.grantsReceived, None)
    val r = Apply.run(legacy, cp, Apply.Options(options.organization))
    changes ++= r.changes.filterNot(_.startsWith("project "))
    notes ++= r.notes
    r.failure.map(f => s"module ${m.name}: $f").toLeft(()).flatMap { _ =>
      m.services.foldLeft[Either[String, Unit]](Right(()))((acc, d) => acc.flatMap(_ => options.ready(cp, project, d.name).left.map(e => s"module ${m.name}: $e")))
    }
