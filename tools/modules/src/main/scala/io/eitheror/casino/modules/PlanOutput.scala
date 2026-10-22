package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** `render <slug>` for a person: the project, each module's services with their env, topics and grants, and what is held. */
object PlanText:
  def lines(plan: BrandPlan): Vector[String] =
    def module(m: ModulePlan, held: Boolean) =
      Vector(s"module ${m.name} ${m.version.getOrElse("(tree)")}${if held then s" held: missing ${m.missing.mkString(", ")}" else ""}") ++
        m.topics.map(t => s"  topic ${t.declaration.name} ${t.declaration.partitions} partitions ${Manifest.show(t.declaration.retention)}") ++
        m.services.flatMap { d =>
          s"  service ${d.name} ${d.service.image}${if m.exposed.contains(d.name) then " exposed" else ""}" +:
            d.service.env.map(e => s"    ${e.name}=${e.value.getOrElse(e.secretKeyRef.fold("")(r => s"<secret ${r.name}/${r.key}>"))}")
        } ++
        m.grantsMade.map(g => s"  grant ${g.target} to ${g.principal}") ++
        m.grantsReceived.map(g => s"  receives ${g.target} as ${g.principal}")
    Vector(s"project ${plan.project} (brand ${plan.slug}${if plan.realMoney then ", real money" else ""})") ++
      plan.modules.flatMap(module(_, held = false)) ++ plan.held.flatMap(module(_, held = true))

/** `render <slug> --format json`: the plan as a document. */
object PlanJson:
  private val mapper = ObjectMapper()

  def node(plan: BrandPlan): ObjectNode =
    val doc = mapper.createObjectNode()
    doc.put("project", plan.project).put("slug", plan.slug).put("realMoney", plan.realMoney)
    def module(m: ModulePlan): ObjectNode =
      val n = mapper.createObjectNode()
      n.put("name", m.name).put("kind", m.kind).put("retention", m.retention)
      m.version.foreach(n.put("version", _))
      val ts = n.putArray("topics")
      m.topics.foreach(t => ts.addObject().put("name", t.declaration.name).put("partitions", t.request.partitions))
      val ss = n.putArray("services")
      m.services.foreach { d =>
        val s = ss.addObject().put("name", d.name).put("image", d.service.image).put("exposed", m.exposed.contains(d.name))
        val env = s.putObject("env")
        d.service.env.foreach(e => env.put(e.name, e.value.getOrElse(e.secretKeyRef.fold("")(r => s"secret:${r.name}/${r.key}"))))
      }
      val gs = n.putArray("grantsMade")
      m.grantsMade.foreach(g => gs.addObject().put("principal", g.principal).put("target", g.target))
      val ms = n.putArray("missing")
      m.missing.foreach(ms.add)
      n
    val ms = doc.putArray("modules")
    plan.modules.foreach(m => ms.add(module(m)))
    val hs = doc.putArray("held")
    plan.held.foreach(m => hs.add(module(m)))
    doc

  def render(plan: BrandPlan): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node(plan))
