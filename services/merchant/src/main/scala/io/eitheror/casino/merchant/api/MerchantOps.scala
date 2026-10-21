package io.eitheror.casino.merchant.api

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.api.Answers.*
import io.eitheror.casino.merchant.hierarchy.*
import io.eitheror.casino.merchant.keys.RawKeys
import io.eitheror.casino.merchant.routing.{Evaluator, Resolution}
import io.eitheror.casino.merchant.security.Secrets
import io.eitheror.casino.merchant.terminals.*
import io.eitheror.casino.merchant.terminals.catalogue.Catalogue

import java.util.UUID

/**
 * What an operator does to one sub-brand (FR-004, FR-006, FR-007, FR-009), shared by `/v1/merchants`
 * and `/v1/sub-brands`, which the original served alike. Every operation reads the sub-brand first:
 * unknown is `SUB_BRAND_NOT_FOUND`, outside the operator's level is `scope_violation` (FR-002).
 */
final class MerchantOps(ctx: Ctx):
  private def sb(id: String) = ctx.components.forEventSourcedEntity(EntityId(id))
  private def terminal(id: String) = ctx.components.forEventSourcedEntity(EntityId(id))

  def scoped(op: Operator, id: String)(f: SubBrandState => Respond[Bytes]): Respond[Bytes] =
    guarded {
      val s = ctx.subBrand(id)
      if !s.exists then refuse(404, "SUB_BRAND_NOT_FOUND")
      else if !op.permits(ctx.subBrandTarget(s)) then scopeViolation
      else f(s)
    }

  // ── keys (FR-004) ────────────────────────────────────────────────────────────────────────────

  def generateKey(op: Operator, id: String, text: String): Respond[Bytes] =
    scoped(op, id) { _ =>
      body(text).fold(identity, b =>
        val raw = RawKeys.mint()
        val key = sb(id).call(SubBrand.generateKey).invoke(GenerateKey(UUID.randomUUID().toString, b.textOr("name", "default"),
          RawKeys.digest(raw), op.actor, ctx.now()))
        created(Json.obj("keyId" -> key.keyId, "name" -> key.name, "rawKey" -> raw, "createdAt" -> key.createdAt.toString)))
    }

  def listKeys(op: Operator, id: String): Respond[Bytes] =
    scoped(op, id)(s => ok(Json.obj("keys" -> Json.arr(s.keys.map(Render.key)*))))

  def disableKey(op: Operator, id: String, keyId: String): Respond[Bytes] =
    scoped(op, id) { _ =>
      val s = sb(id).call(SubBrand.disableKey).invoke(DisableKey(keyId, op.actor, ctx.now()))
      ok(Render.key(s.keys.find(_.keyId == keyId).get))
    }

  // ── credentials (FR-006, FR-008) ─────────────────────────────────────────────────────────────

  /**
   * Registers a credential: its secrets are written to the store under a fresh id first, then the
   * record; when the same provider, name and environment exists, it is returned and what was written
   * is removed, so a duplicate never replaces secrets (`PspCredentialLifecycleSpec`).
   */
  def registerCredential(op: Operator, id: String, text: String): Respond[Bytes] =
    scoped(op, id) { _ =>
      body(text).fold(identity, b =>
        val provider = b.textOr("provider", "")
        val name     = b.text("name").orElse(b.text("credentialName")).getOrElse("")
        if provider.isEmpty || name.isEmpty then refuse(400, "INVALID_REQUEST", "a credential names its provider and its name")
        else
          val secrets = b.stringMap("credentials")
          val credId  = UUID.randomUUID().toString
          ctx.secrets.putMap(Secrets.credential(credId), secrets)
          val r = sb(id).call(SubBrand.registerCredential).invoke(RegisterCredential(credId, provider, name, b.textOr("environment", "production"),
            b.text("scheme"), secrets.keys.toVector, op.actor, ctx.now()))
          if !r.created then
            ctx.secrets.delete(Secrets.credential(credId))
            ok(Render.credential(r.record))
          else created(Render.credential(r.record)))
    }

  def listCredentials(op: Operator, id: String): Respond[Bytes] =
    scoped(op, id)(s => ok(Json.obj("credentials" -> Json.arr(s.liveCredentials.map(Render.credential)*))))

  /** The legacy listing: the same, under the original's older names and without credentials. */
  def listConfigurations(op: Operator, id: String): Respond[Bytes] =
    scoped(op, id)(s => ok(Json.obj("configurations" -> Json.arr(s.liveCredentials.map(Render.credential)*))))

  private def named(op: Operator, id: String, provider: String, name: Option[String])(f: (SubBrandState, CredentialRecord) => Respond[Bytes]): Respond[Bytes] =
    scoped(op, id) { s =>
      name match
        case None => refuse(400, "INVALID_REQUEST", "the credential's name is the query parameter `name` (research R14)")
        case Some(name) => s.credentialNamed(provider, name).fold(refuse(404, "CREDENTIAL_NOT_FOUND"))(c => f(s, c))
    }

  def readCredential(op: Operator, id: String, provider: String, name: Option[String]): Respond[Bytes] =
    named(op, id, provider, name)((_, c) => ctx.secrets.credential(c.id).fold(e => refuse(503, "UNAVAILABLE", e.reason), m => ok(Render.credentialDetail(c, m))))

  def replaceCredentialSecrets(op: Operator, id: String, provider: String, name: Option[String], text: String): Respond[Bytes] =
    named(op, id, provider, name) { (_, c) =>
      body(text).fold(identity, b =>
        val secrets = b.stringMap("credentials")
        ctx.secrets.putMap(Secrets.credential(c.id), secrets)
        sb(id).call(SubBrand.changeCredentialSecrets).invoke(ChangeCredentialSecrets(c.id, secrets.keys.toVector, op.actor, ctx.now())): Unit
        ok(Render.credential(c)))
    }

  def credentialStatus(op: Operator, id: String, provider: String, name: Option[String], status: String): Respond[Bytes] =
    named(op, id, provider, name) { (_, c) =>
      val s = sb(id).call(SubBrand.changeCredentialStatus).invoke(ChangeCredentialStatus(c.id, status, op.actor, ctx.now()))
      ok(Render.credential(s.liveCredentials.find(_.id == c.id).get))
    }

  def deleteCredential(op: Operator, id: String, provider: String, name: Option[String]): Respond[Bytes] =
    named(op, id, provider, name) { (_, c) =>
      sb(id).call(SubBrand.deleteCredential).invoke(DeleteCredential(c.id, op.actor, ctx.now())): Unit
      ctx.secrets.delete(Secrets.credential(c.id))
      noContent
    }

  // ── terminals (FR-007, FR-009) ───────────────────────────────────────────────────────────────

  private def capabilityFrom(b: ObjectNode, base: Capability): Capability =
    Capability(b.bool("supportsDeposit").getOrElse(base.supportsDeposit), b.bool("supportsWithdrawal").getOrElse(base.supportsWithdrawal),
      b.bool("requiresPayoutDestination").getOrElse(base.requiresPayoutDestination))

  def createIntegration(op: Operator, id: String, gateway: String, text: String): Respond[Bytes] =
    scoped(op, id) { _ =>
      body(text).fold(identity, b =>
        val scheme             = b.textOr("scheme", "")
        val (kept, secret)     = Catalogue.split(gateway, scheme, b.stringMap("properties"))
        // The original's id (MerchantRoutes, gateways/{gateway}/integrations): its trees and routes name the terminal by it.
        val tid                = s"${id}_${gateway}_${scheme}_${b.textOr("name", "")}".replaceAll("\\s+", "_")
        ctx.secrets.putMap(Secrets.terminal(tid), secret)
        terminal(tid).call(Terminal.create).invoke(CreateTerminal(id, gateway, scheme, b.textOr("name", ""), b.strings("regions"), kept,
          secret.keys.toVector, capabilityFrom(b, Capability(true, false, false)), op.actor, ctx.now())): Unit
        created(Json.obj("integrationId" -> tid)))
    }

  private def integration(op: Operator, id: String, tid: String)(f: TerminalState => Respond[Bytes]): Respond[Bytes] =
    scoped(op, id) { _ =>
      val t = terminal(tid).call(Terminal.get).invoke()
      if !t.exists || t.subBrandId != id then refuse(404, "INTEGRATION_NOT_FOUND") else f(t)
    }

  private def summary(t: TerminalState): Respond[Bytes] =
    ctx.secrets.terminal(t.id).fold(e => refuse(503, "UNAVAILABLE", e.reason), s => ok(Render.integration(t, s)))

  def listIntegrations(op: Operator, id: String): Respond[Bytes] =
    scoped(op, id) { _ =>
      val ts = terminalsOf(id)
      val rendered = ts.map(t => ctx.secrets.terminal(t.id).map(s => Render.integration(t, s)))
      rendered.collectFirst { case Left(e) => refuse(503, "UNAVAILABLE", e.reason) }
        .getOrElse(ok(Json.obj("integrations" -> Json.arr(rendered.collect { case Right(o) => o }*))))
    }

  /** The sub-brand's terminal by its id, read from the entity: a terminal made moments ago is not yet in the view. */
  def terminalOf(subBrandId: String, id: String): Option[TerminalState] =
    scala.util.Try(terminal(id).call(Terminal.get).invoke()).toOption.filter(t => t.exists && t.subBrandId == subBrandId)

  def terminalsOf(subBrandId: String): Vector[TerminalState] =
    import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
    ctx.views.forView(TerminalsBySubBrand).where(jsonText("subBrandId") ++ sql" = $subBrandId", 10_000)
      .map(r => terminal(r.id).call(Terminal.get).invoke()).filter(_.exists).sortBy(_.name)

  def readIntegration(op: Operator, id: String, tid: String): Respond[Bytes] = integration(op, id, tid)(summary)

  def setProperties(op: Operator, id: String, tid: String, text: String): Respond[Bytes] =
    integration(op, id, tid) { t =>
      body(text).fold(identity, b =>
        val (kept, secret) = Catalogue.split(t.gateway, t.scheme, b.stringMap("properties"))
        ctx.secrets.putMap(Secrets.terminal(tid), secret)
        summary(terminal(tid).call(Terminal.setProperties).invoke(SetProperties(kept, secret.keys.toVector, op.actor, ctx.now()))))
    }

  def setCapability(op: Operator, id: String, tid: String, text: String): Respond[Bytes] =
    integration(op, id, tid) { t =>
      body(text).fold(identity, b =>
        val cap = capabilityFrom(b, Capability(t.supportsDeposit, t.supportsWithdrawal, t.requiresPayoutDestination))
        summary(terminal(tid).call(Terminal.setCapability).invoke(SetCapability(cap, op.actor, ctx.now()))))
    }

  /** Answered `204` with no body, as the original answers both. */
  def activate(op: Operator, id: String, tid: String, on: Boolean): Respond[Bytes] =
    integration(op, id, tid) { _ =>
      val change = TerminalChange(op.actor, ctx.now())
      terminal(tid).call(if on then Terminal.activate else Terminal.disable).invoke(change): Unit
      noContent
    }

  /** Refused while the sub-brand's resolved tree names the terminal (`TerminalDeleteRoutesSpec`). */
  def deleteIntegration(op: Operator, id: String, tid: String): Respond[Bytes] =
    integration(op, id, tid) { t =>
      val named = Resolution.resolve(ctx.components, id).exists(r => r.tree.nodes.values.exists(_.terminals.exists(_.exists(x => x == tid || x == t.name))))
      if named then refuse(409, "TERMINAL_IN_USE", "the sub-brand's live routing tree names this terminal")
      else
        terminal(tid).call(Terminal.delete).invoke(TerminalChange(op.actor, ctx.now())): Unit
        ctx.secrets.delete(Secrets.terminal(tid))
        noContent
    }

  /** Which gateways serve each scheme for the sub-brand: its active credentials and terminals (`GatewayConfigResponse`). */
  def gatewayConfig(op: Operator, id: String): Respond[Bytes] =
    scoped(op, id) { s =>
      val fromCredentials = s.liveCredentials.filter(_.active).map(c => c.scheme.getOrElse(c.provider) -> c.provider)
      val fromTerminals   = terminalsOf(id).filter(_.active).map(t => t.scheme -> t.gateway)
      val grouped = (fromCredentials ++ fromTerminals).groupBy(_._1).view.mapValues(_.map(_._2).distinct.sorted).toMap
      val o = Json.obj()
      grouped.toVector.sortBy(_._1).foreach((k, v) => Json.set(o, k, v))
      ok(Json.obj("methodGateways" -> o))
    }

  /** Credential-named terminals and terminals together, with the directions each takes (FR-009). */
  def terminalSummaries(s: SubBrandState): Vector[ObjectNode] =
    val credentials = s.liveCredentials.map { c =>
      Json.obj("terminalEntityId" -> c.name, "gateway" -> c.provider, "scheme" -> c.scheme.getOrElse(c.provider), "name" -> c.name,
        "status" -> (if c.active then TerminalState.Active else TerminalState.Inactive), "integrationType" -> "credential",
        "supportsDeposit" -> true, "supportsWithdrawal" -> false, "requiresPayoutDestination" -> false)
    }
    val terminals = terminalsOf(s.id).map { t =>
      Json.obj("terminalEntityId" -> t.id, "gateway" -> t.gateway, "scheme" -> t.scheme, "name" -> t.name, "status" -> t.status,
        "integrationType" -> "integration", "supportsDeposit" -> t.supportsDeposit, "supportsWithdrawal" -> t.supportsWithdrawal,
        "requiresPayoutDestination" -> t.requiresPayoutDestination)
    }
    credentials ++ terminals

  /** `simulate`: the walk over a tree the request carries (FR-010). */
  def simulate(text: String): Respond[Bytes] =
    body(text).fold(identity, b =>
      Codec.tree(Option(b.get("tree"))) match
        case None => refuse(400, "INVALID_REQUEST", "the body carries no tree")
        case Some(tree) =>
          val c = io.eitheror.casino.merchant.routing.RoutingContext(b.textOr("currency", ""), b.long("amountCents").getOrElse(0L),
            b.textOr("country", ""), b.textOr("customerId", ""), b.long("depositCount").getOrElse(0L))
          Evaluator.evaluate(tree, c) match
            case io.eitheror.casino.merchant.routing.Outcome.Match(ts) => ok(Json.obj("outcome" -> "MATCH", "terminals" -> ts))
            case io.eitheror.casino.merchant.routing.Outcome.NoMatch  => ok(Json.obj("outcome" -> "NO_MATCH", "terminals" -> Vector.empty[String]))
            case io.eitheror.casino.merchant.routing.Outcome.NoTree   => ok(Json.obj("outcome" -> "NO_TREE", "terminals" -> Vector.empty[String])))
