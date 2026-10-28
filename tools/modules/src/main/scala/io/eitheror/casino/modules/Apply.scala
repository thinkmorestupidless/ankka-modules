package io.eitheror.casino.modules

import com.thinkmorestupidless.ankka.core.Contract

import scala.collection.mutable

/** What an apply did: a line per change, notes for the status, and the step it stopped at. */
final case class ApplyResult(changes: Vector[String], notes: Vector[String], failure: Option[String]):
  def ok: Boolean = failure.isEmpty

/**
 * Makes the installation hold what the plan says, in order: project, topics, secrets, services,
 * exposure, grants, realm (spec 001 research R1). It reads before it writes, changes only what
 * differs, and stops at the first failure naming the step. A second apply of the same manifest
 * changes nothing.
 */
object Apply:

  /** The history kinds whose generation recorded a descriptor. */
  val Recorded: Set[String] = Set("applied", "rolled-back")

  final case class Options(
      organization: String,
      secretValues: Map[String, Map[String, String]] = Map.empty,
      realm: Option[(RealmAdmin, () => String)] = None,
      /** The operators' realm, imported when the installation lacks it (brands/_realm/eitheror-operators.json). */
      operatorsRealm: () => Option[String] = () => None
  )

  def run(plan: Plan, cp: ControlPlane, options: Options): ApplyResult =
    val changes = mutable.ArrayBuffer.empty[String]
    val notes   = mutable.ArrayBuffer.empty[String]
    def step[A](name: String)(body: => A): Either[String, A] =
      try Right(body)
      catch case e: ControlPlaneError => Left(s"$name: ${e.message}")

    val result = for
      _ <- step("project") {
        if cp.project(plan.projectId).isEmpty then
          cp.createProject(plan.projectId, plan.name, options.organization)
          changes += s"project ${plan.projectId} created"
      }
      _ <- step("topics") {
        val existing = cp.topics(plan.projectId).map(t => t.name -> t).toMap
        plan.topics.foreach { t =>
          val name = t.declaration.name
          val current = existing.get(name)
          val contractName = t.request.contract.map(_._1)
          val wanted = t.request.contract.flatMap((n, schema) =>
            Contract.fromSchema(n, schema.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toOption)
          val needs = current match
            case None    => true
            case Some(c) => c.partitions != t.request.partitions || c.contract != wanted
          if needs then
            cp.declareTopic(plan.projectId, name, t.request)
            changes += s"topic $name declared (${t.request.partitions} partitions${contractName.fold("")(n => s", contract $n")})"
          notes ++= t.notes.map(n => s"topic $name: $n")
        }
      }
      _ <- step("secrets") {
        val existing = cp.secrets(plan.projectId).map(s => s.name -> s.entries.toSet).toMap
        plan.secrets.foreach { secret =>
          val supplied = options.secretValues.getOrElse(secret.name, Map.empty)
          val missing = secret.keys.filterNot(k => existing.get(secret.name).exists(_.contains(k)))
          val toSet = supplied.filter((k, _) => secret.keys.contains(k) && missing.contains(k))
          if toSet.nonEmpty then
            cp.setSecret(plan.projectId, secret.name, toSet)
            changes += s"secret ${secret.name} set (${toSet.keys.toVector.sorted.mkString(", ")})"
          val still = missing.filterNot(toSet.contains)
          if still.nonEmpty then notes += s"secret ${secret.name} lacks ${still.mkString(", ")}: set it with --secrets"
        }
      }
      _ <- step("services") {
        val statuses = cp.services(plan.projectId).map(s => s.name -> s).toMap
        plan.descriptors.foreach { wanted =>
          statuses.get(wanted.name) match
            case None =>
              cp.apply(plan.projectId, wanted)
              changes += s"service ${wanted.name} applied at ${wanted.service.image}"
            case Some(status) =>
              // The newest generation that recorded a descriptor: the history is newest first, and a restart, a
              // pause or a resume records none (asked for, the platform answers 409; found on kind, 2026-10-28).
              val recorded = cp.history(plan.projectId, wanted.name).find(e => Apply.Recorded(e.kind))
              val current  = recorded.flatMap(e => cp.descriptor(plan.projectId, wanted.name, e.generation))
              if !current.contains(wanted) then
                recorded.filter(_.kind == "rolled-back") match
                  case Some(entry) =>
                    notes += s"service ${wanted.name}: manifest behind cluster: an operator rolled it back to generation ${entry.rolledBackTo.getOrElse(0L)}; change the manifest to match, then apply"
                  case None =>
                    cp.apply(plan.projectId, wanted)
                    changes += s"service ${wanted.name} applied at ${wanted.service.image} (was ${status.image})"
        }
      }
      _ <- step("expose") {
        val statuses = cp.services(plan.projectId).map(s => s.name -> s).toMap
        plan.exposed.foreach { name =>
          if !statuses.get(name).exists(_.exposed) then
            cp.expose(plan.projectId, name)
            changes += s"service $name exposed"
        }
      }
      _ <- step("grants") {
        cp.grants(plan.projectId) match
          case None =>
            if plan.grantsMade.nonEmpty || plan.grantsReceived.nonEmpty then
              notes += s"grants: platform lacks 040; ${plan.grantsMade.size} to make and ${plan.grantsReceived.size} to receive wait for it"
          case Some(existing) =>
            val made = existing.map(g => g.principal -> g.target).toSet
            plan.grantsMade.filterNot(g => made.contains(g.principal -> g.target)).foreach { g =>
              cp.makeGrant(plan.projectId, g) match
                case Some(_) => changes += s"grant ${g.target} made to ${g.principal}"
                case None    =>
                  // 404: the grantee's project (or the topic) is not yet created; the grant is made on the next apply.
                  notes += s"grant ${g.target} to ${g.principal} waits: the grantee's project is not yet created; apply its manifest, then this one again"
            }
            val received = cp.receivedGrants(plan.projectId).getOrElse(Vector.empty).map(g => g.principal -> g.target).toSet
            plan.grantsReceived.filterNot(g => received.contains(g.principal -> g.target)).foreach { g =>
              notes += s"grant ${g.target} to ${g.principal} not yet received: apply the granting project's manifest"
            }
      }
      _ <- step("realm") {
        (plan.realm, options.realm) match
          case (Some(_), Some((admin, json))) =>
            changes ++= Realm.ensure(admin, plan.projectId, json(), options.operatorsRealm())
          case (Some(realm), None) =>
            notes += s"realm $realm not checked: no Keycloak administrator (CASINO_KEYCLOAK_ADMIN_USERNAME, _PASSWORD)"
          case _ => ()
      }
    yield ()
    ApplyResult(changes.toVector, notes.toVector, result.left.toOption)
