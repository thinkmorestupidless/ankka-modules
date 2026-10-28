package io.eitheror.casino.modules

import com.thinkmorestupidless.ankka.controlplane.api.{GrantRequest as _, *}

import scala.collection.mutable

/**
 * A control plane held in memory, answering as ankka does where the tool depends on it: a deleted
 * project's id is refused with 409; a service's generation grows on every apply; topics cannot lose
 * partitions. It records every call, so a test can say what was called and in what order.
 * `hasGrants` false answers as ankka 0.11 does, without 040.
 */
final class InMemoryControlPlane(val hasGrants: Boolean = false, var installationJson: Option[String] = None, val hasHostnames: Boolean = true) extends ControlPlane:

  private val log       = mutable.ArrayBuffer.empty[String]
  val projects          = mutable.LinkedHashMap.empty[String, ProjectSummary]
  val tombstones        = mutable.Set.empty[String]
  val topicsBy          = mutable.Map.empty[String, mutable.LinkedHashMap[String, (ProjectTopic, TopicRequest)]]
  val secretsBy         = mutable.Map.empty[String, mutable.LinkedHashMap[String, Map[String, String]]]
  val servicesBy        = mutable.Map.empty[String, mutable.LinkedHashMap[String, Vector[ServiceDescriptor]]]
  val exposed           = mutable.Set.empty[(String, String)]
  val grantsBy          = mutable.Map.empty[String, mutable.ArrayBuffer[GrantRequest]]

  def calls: Vector[String] = log.toVector
  def writes: Vector[String] = log.toVector.filterNot(_.startsWith("GET"))

  def deleteProject(id: String): Unit =
    projects.remove(id); topicsBy.remove(id); secretsBy.remove(id); servicesBy.remove(id); tombstones += id
    generations.keys.filter(_._1 == id).toVector.foreach(generations.remove)

  /** Every generation of a service, oldest first: its history entry and the descriptor it recorded, if any. */
  private val generations = mutable.Map.empty[(String, String), Vector[(HistoryEntry, Option[ServiceDescriptor])]]

  private def record(project: String, name: String, entry: Long => HistoryEntry, descriptor: Option[ServiceDescriptor]): Unit =
    val held = generations.getOrElse((project, name), Vector.empty)
    generations((project, name)) = held :+ (entry(held.size + 1L) -> descriptor)
    descriptor.foreach(d => servicesBy(project)(name) = servicesBy(project).getOrElse(name, Vector.empty) :+ d)

  /** An operator's emergency rollback: a new generation with an earlier descriptor (033). */
  def rollback(project: String, name: String, toGeneration: Int): Unit =
    val earlier = generations((project, name))(toGeneration - 1)._2
    record(project, name, g => HistoryEntry("rolled-back", g, rolledBackTo = Some(toGeneration.toLong)), earlier)

  /** A restart: a new generation that records no descriptor, as the platform's does. */
  def restart(project: String, name: String): Unit =
    record(project, name, g => HistoryEntry("restarted", g), None)

  def project(id: String) = { log += s"GET /projects/$id"; projects.get(id) }

  def createProject(id: String, name: String, organization: String) =
    log += s"POST /projects/$id"
    if tombstones.contains(id) then throw ControlPlaneError(409, s"project id '$id' was deleted and cannot be reused")
    if projects.contains(id) then throw ControlPlaneError(409, s"project '$id' exists")
    val problems = ProjectId.problems(id)
    if problems.nonEmpty then throw ControlPlaneError(400, problems.mkString("; "))
    projects(id) = ProjectSummary(id, name, organization, 0)

  private def mustExist(project: String): Unit =
    if !projects.contains(project) then throw ControlPlaneError(404, s"no project '$project'")

  def topics(project: String) =
    log += s"GET /projects/$project/topics"
    topicsBy.get(project).map(_.values.map(_._1).toVector).getOrElse(Vector.empty)

  def declaredTopic(project: String, name: String): Option[TopicRequest] = topicsBy.get(project).flatMap(_.get(name)).map(_._2)

  def declareTopic(project: String, name: String, request: TopicRequest) =
    log += s"PUT /projects/$project/topics/$name"
    mustExist(project)
    val problems = ProjectTopics.problems(name, request.partitions)
    if problems.nonEmpty then throw ControlPlaneError(400, problems.mkString("; "))
    val existing = topicsBy.getOrElseUpdate(project, mutable.LinkedHashMap.empty)
    existing.get(name).foreach { (t, _) =>
      if request.partitions < t.partitions then throw ControlPlaneError(409, ProjectTopics.fewer(name, t.partitions, request.partitions))
    }
    val contract = request.contract.map((n, schema) =>
      com.thinkmorestupidless.ankka.core.Contract.fromSchema(n, schema.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .fold(e => throw ControlPlaneError(400, e), identity))
    existing(name) = (ProjectTopic(name, request.partitions, Some("provisioned"), contract = contract), request)

  def secrets(project: String) =
    log += s"GET /projects/$project/secrets"
    secretsBy.get(project).map(_.toVector.map((n, e) => ProjectSecretSummary(n, e.keys.toVector.sorted))).getOrElse(Vector.empty)

  def setSecret(project: String, name: String, entries: Map[String, String]) =
    log += s"PUT /projects/$project/secrets/$name"
    mustExist(project)
    val all = secretsBy.getOrElseUpdate(project, mutable.LinkedHashMap.empty)
    all(name) = all.getOrElse(name, Map.empty) ++ entries

  private def status(project: String, name: String): ServiceStatus =
    ServiceStatus(name, project, ServiceLifecycle.Ready, generations((project, name)).size.toLong, servicesBy(project)(name).last.service.image, 1, 1,
      exposed = exposed.contains(project -> name))

  def services(project: String) =
    log += s"GET /services/$project"
    servicesBy.get(project).map(_.keys.toVector.map(status(project, _))).getOrElse(Vector.empty)

  def descriptor(project: String, name: String, generation: Long) =
    log += s"GET /services/$project/$name/descriptor"
    generations.get((project, name)).flatMap(_.lift(generation.toInt - 1)).map {
      case (_, Some(d)) => d
      case (e, None)    => throw ControlPlaneError(409, s"generation $generation was a ${e.kind} and recorded no descriptor")
    }

  def history(project: String, name: String) =
    log += s"GET /services/$project/$name/history"
    // Newest first, as the platform answers.
    generations.get((project, name)).map(_.map(_._1).reverse).getOrElse(Vector.empty)

  def apply(project: String, descriptor: ServiceDescriptor) =
    log += s"PUT /services/$project/${descriptor.name}"
    mustExist(project)
    val problems = descriptor.problems
    if problems.nonEmpty then throw ControlPlaneError(400, problems.mkString("; "))
    servicesBy.getOrElseUpdate(project, mutable.LinkedHashMap.empty)
    record(project, descriptor.name, g => HistoryEntry("applied", g), Some(descriptor))
    status(project, descriptor.name)

  def expose(project: String, name: String) =
    log += s"POST /services/$project/$name/expose"
    exposed += project -> name
    status(project, name)

  def grants(project: String) =
    log += s"GET /projects/$project/grants"
    Option.when(hasGrants)(grantsBy.get(project).map(_.toVector).getOrElse(Vector.empty).map(g => GrantStatus(g.principal, g.target, "in effect")))

  def makeGrant(project: String, grant: GrantRequest) =
    log += s"POST /projects/$project/grants"
    Option.when(hasGrants)(grantsBy.getOrElseUpdate(project, mutable.ArrayBuffer.empty) += grant).map(_ => ())

  def receivedGrants(project: String) =
    log += s"GET /projects/$project/grants/received"
    Option.when(hasGrants)(
      grantsBy.toVector.flatMap((_, gs) => gs.filter(_.principal.startsWith(s"service:$project/")))
        .map(g => GrantStatus(g.principal, g.target, "in effect"))
    )

  val hostnames = mutable.Map.empty[(String, String), Vector[String]]

  def installation() =
    log += "GET /installation"
    installationJson

  def addHostname(project: String, service: String, hostname: String) =
    log += s"PUT /services/$project/$service/hostnames/$hostname"
    Option.when(hasHostnames) {
      mustExist(project)
      hostnames((project, service)) = (hostnames.getOrElse((project, service), Vector.empty) :+ hostname).distinct
    }

  def delete(project: String, name: String) =
    log += s"DELETE /services/$project/$name"
    val existed = servicesBy.get(project).exists(_.contains(name))
    servicesBy.get(project).foreach(_.remove(name))
    generations.remove((project, name))
    exposed -= project -> name
    existed

