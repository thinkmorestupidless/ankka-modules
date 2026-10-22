package io.eitheror.casino.modules

import io.eitheror.casino.contract.facts.ModuleChange

import java.time.{Duration, Instant}
import scala.collection.mutable

/**
 * The vendor's agent as ankka-vendor 004 `contracts/agent.md` (at ca846e8) describes it, modelled over
 * `InMemoryControlPlane` so this repository's manifests can be proven against its apply order without
 * the vendor: resolve, values, requires, topics, services in order, grants verified; remove in reverse
 * with `dataKeptUntil`; disabling a dependency refused naming the dependent. It holds the module entity
 * as the vendor's data-model gives it and writes a `platform.modules` fact per change.
 *
 * What is the hub's here is the hub's there: `deliver` makes the module's grants, as the hub does at
 * delivery, and the agent only verifies them. The model is not the agent: the agent is proven in
 * ankka-vendor (its quickstart); this proves the manifests and the order they are applied in.
 */
final class AgentModel(
    val cp: InMemoryControlPlane,
    val project: String,
    manifests: Vector[ModuleManifest],
    installation: Option[Installation] = None,
    clock: () => Instant = () => Instant.parse("2026-10-22T12:00:00Z")
):
  import AgentModel.*

  val entity: mutable.LinkedHashMap[String, ModuleRecord] = mutable.LinkedHashMap.empty
  val entitlements: mutable.LinkedHashMap[String, Entitlement] = mutable.LinkedHashMap.empty
  val facts: mutable.ArrayBuffer[ModuleChange] = mutable.ArrayBuffer.empty

  def manifest(name: String): ModuleManifest = manifests.find(_.name == name).getOrElse(throw AssertionError(s"no manifest '$name'"))

  /** The keys and values the agent reads: the project's `tenant-values` (values push and secrets write it). */
  def tenantValues: Map[String, String] = cp.secretsBy.get(project).flatMap(_.get(BrandRender.TenantValues)).getOrElse(Map.empty)

  /** The installation's facts as the agent reads them: `GET /installation`, else (local mode's model) the overlay. */
  private def installationFacts: Readiness.Facts =
    cp.installation().map(Readiness.fromControlPlane(_, "GET /installation"))
      .orElse(installation.map(Readiness.fromOverlay))
      .getOrElse(Readiness.Facts(Map.empty, "no installation"))

  /** The hub delivers an entitlement: it makes the module's `made` grants on the project, then the agent applies. */
  def deliver(module: String, version: String, actor: String, channel: Option[String] = Some("stable")): ModuleRecord =
    entitlements(module) = Entitlement(module, version, channel, ended = false)
    manifest(module).grantsMade.collect { case ModuleManifest.MadeGrant(ModuleManifest.Principal.Literal(p), t) => GrantRequest(p, BrandRender.target(t)) }
      .foreach(g => if !cp.grantsBy.get(project).exists(_.contains(g)) then cp.makeGrant(project, g))
    apply(module, actor)

  /** The apply order of `contracts/agent.md`, step by step; the first failure stops it and is recorded. */
  def apply(module: String, actor: String): ModuleRecord =
    val e = entitlements(module)
    val m = manifest(module)
    def stop(status: String, reason: String, missing: Vector[String] = Vector.empty, change: String = "failed"): ModuleRecord =
      val r = entity.getOrElse(module, ModuleRecord(module)).copy(status = status, version = Some(e.version), reason = Some(reason), missing = missing, lastChange = clock())
      entity(module) = r
      fact(module, change, Some(e.version), e.channel, actor)
      r
    // 1. resolve
    m.dependencies.find(d => !entity.get(d.module).exists(_.status == Enabled)) match
      case Some(d) => stop(Failed, s"dependency ${d.module} is not enabled")
      case None =>
        // 2. values
        val values  = tenantValues
        val missing = m.configuration.map(_.key).filterNot(values.contains)
        if missing.nonEmpty then stop(Failed, s"missing value ${missing.head}", missing)
        else
          // 3. requires
          val unmet = Readiness.check(m.requires, values, installationFacts)
          if unmet.nonEmpty then stop(Unfit, s"requirement unmet: ${unmet.head}")
          else
            // 4. topics
            m.topics.foreach { t =>
              val plan = Render.topic(TopicDeclaration(t.name, t.partitions, io.eitheror.casino.contract.Layout.retentionOf(t.name)), installation)
              cp.declareTopic(project, t.name, plan.request)
            }
            // 5. services, in manifest order; a dependency's services are its own module's and never touched
            val applied = m.services.map { s =>
              val d = BrandRender.descriptor(s, m.secretKeys, values, Some(e.version))
              cp.apply(project, d)
              if s.expose then cp.expose(project, s.name)
              s.name -> "Ready"
            }
            // 6. grants: verified, made by the hub at delivery; within the project there is no grant
            val inForce = cp.grants(project).getOrElse(Vector.empty).map(g => GrantRequest(g.principal, g.target)).toSet
            val absent = m.grantsMade.collect { case ModuleManifest.MadeGrant(ModuleManifest.Principal.Literal(p), t) => GrantRequest(p, BrandRender.target(t)) }
              .filterNot(inForce)
            if absent.nonEmpty then stop(Failed, s"grant ${absent.head.target} to ${absent.head.principal} is not in force")
            else
              // 7. done
              val r = ModuleRecord(module, Enabled, Some(e.version), e.channel, applied.toMap, clock(), None, None, Vector.empty)
              entity(module) = r
              fact(module, "enabled", Some(e.version), e.channel, actor)
              r

  /** The end of an entitlement: refused while an enabled module depends on it; else services removed in reverse, data kept for the retention. */
  def disable(module: String, actor: String): Either[String, ModuleRecord] =
    val dependents = entity.values.filter(_.status == Enabled).map(_.name).filter(n => manifest(n).dependencies.exists(_.module == module)).toVector
    if dependents.nonEmpty then Left(s"module '${dependents.head}' depends on '$module'")
    else
      val m = manifest(module)
      entitlements.get(module).foreach(e => entitlements(module) = e.copy(ended = true))
      m.services.reverse.foreach(s => cp.delete(project, s.name))
      val until = clock().plus(Duration.ofDays(days(m.retention)))
      val r = entity.getOrElse(module, ModuleRecord(module)).copy(status = Disabled, services = Map.empty, lastChange = clock(), dataKeptUntil = Some(until))
      entity(module) = r
      fact(module, "disabled", r.version, r.channel, actor)
      Right(r)

  private def fact(module: String, change: String, version: Option[String], channel: Option[String], actor: String): Unit =
    facts += ModuleChange(module, change, version, channel, actor, clock())

object AgentModel:
  val Enabled  = "Enabled"
  val Disabled = "Disabled"
  val Failed   = "Failed"
  val Unfit    = "Unfit"

  /** The module entity's record per module (vendor 004 data-model.md), plus why it stopped. */
  final case class ModuleRecord(
      name: String,
      status: String = "Pending",
      version: Option[String] = None,
      channel: Option[String] = None,
      services: Map[String, String] = Map.empty,
      lastChange: Instant = Instant.EPOCH,
      dataKeptUntil: Option[Instant] = None,
      reason: Option[String] = None,
      missing: Vector[String] = Vector.empty
  )

  final case class Entitlement(module: String, version: String, channel: Option[String], ended: Boolean)

  def days(retention: String): Long =
    retention.stripSuffix("d").toLongOption.getOrElse(throw AssertionError(s"retention '$retention' is not <n>d"))
