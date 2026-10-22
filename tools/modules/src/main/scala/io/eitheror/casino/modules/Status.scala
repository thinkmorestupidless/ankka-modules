package io.eitheror.casino.modules

/** `status`: readiness, then services, topics, grants and realm, one line each (spec 001 FR-040). */
object Status:

  def lines(plan: Plan, cp: ControlPlane, readiness: Option[Vector[Readiness.Condition]], realm: Option[RealmAdmin]): Vector[String] =
    val header = Vector(s"${plan.projectId} (${plan.name})")
    val ready = readiness match
      case Some(conditions) =>
        (s"real money: ${if Readiness.fit(conditions) then "fit" else "not fit"}" +: conditions.map(c => s"  ${c.line}"))
      case None => Vector("real money: n/a (no installation named)")
    val project = cp.project(plan.projectId)
    if project.isEmpty then return header ++ ready ++ Vector("project: absent")
    val services = cp.services(plan.projectId).map(s => s.name -> s).toMap
    val serviceLines = plan.descriptors.map { d =>
      services.get(d.name) match
        case None => s"  ${d.name}: absent"
        case Some(s) =>
          val drift = if s.image != d.service.image then s" (manifest pins ${d.service.image})" else ""
          s"  ${d.name}: ${s.lifecycle} generation ${s.generation} ${s.image}$drift${if s.exposed then s" exposed ${s.hostname.getOrElse("")}".stripTrailing else ""}"
    }
    val topics = cp.topics(plan.projectId).map(t => t.name -> t).toMap
    val topicLines = plan.topics.flatMap { t =>
      val name = t.declaration.name
      val head = topics.get(name) match
        case None    => s"  $name: absent"
        case Some(p) => s"  $name: ${p.phase.getOrElse("unreported")} ${p.partitions} partitions${p.contract.fold("")(c => s" contract ${c.name}")}"
      head +: t.notes.map(n => s"    $n")
    }
    val grantLines = cp.grants(plan.projectId) match
      case None => Vector("  platform lacks 040: no grant can be made or read")
      case Some(made) =>
        plan.grantsMade.map { g =>
          val effect = made.find(m => m.principal == g.principal && m.target == g.target).map(_.effect).getOrElse("absent")
          s"  made ${g.target} to ${g.principal}: $effect"
        } ++ {
          val received = cp.receivedGrants(plan.projectId).getOrElse(Vector.empty)
          plan.grantsReceived.map { g =>
            val effect = received.find(r => r.principal == g.principal && r.target == g.target).map(_.effect).getOrElse("absent")
            s"  received ${g.target} to ${g.principal}: $effect"
          }
        }
    val realmLines = (plan.realm, realm) match
      case (Some(r), Some(admin)) =>
        Vector(s"  $r: ${if admin.realmExists(r) then "present" else "absent"}",
          s"  brand_${plan.projectId} in eitheror: ${if admin.roleExists(Realm.OperatorsRealm, s"brand_${plan.projectId}") then "present" else "absent"}")
      case (Some(r), None) => Vector(s"  $r: not checked (no Keycloak administrator)")
      case _               => Vector.empty
    header ++ ready ++ Vector("services:") ++ serviceLines ++ Vector("topics:") ++ topicLines ++
      Vector("grants:") ++ grantLines ++ (if realmLines.nonEmpty then "realm:" +: realmLines else Vector.empty)
