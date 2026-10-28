package io.eitheror.casino.attribution.api

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.attribution.api.Wire.{*, given}
import io.eitheror.casino.attribution.carriers.Deliveries
import io.eitheror.casino.attribution.money.{Days, OpenDays}
import io.eitheror.casino.attribution.networks.{Addresses, AddNetwork, Cache, Documents, Network, NetworkDocuments, NetworkState, RemoveNetwork, UpdateNetwork}
import io.eitheror.casino.attribution.record.{AttributionRecord, Record}
import io.eitheror.casino.attribution.settings.{AddSigningKey, BrandSettings, RetireSigningKey, SetWindows, SettingsEntity, SigningKey}
import io.eitheror.casino.attribution.tag.{Pseudonyms, Signing}
import io.eitheror.casino.attribution.views.Records
import io.eitheror.casino.contract.access.Routes

import java.time.LocalDate
import java.util.UUID

/** The operator's attribution backoffice (contracts/routes.md *Operator*), for this brand's operators. */
final class BackofficeEndpoint(api: Api) extends HttpEndpoint(s"/v1/casino/backoffice/${api.brand}/attribution"):
  val acl: Acl = Acl.DenyAll

  withAcl(Routes.operator(api.brand, api.authenticate())) {
    get("/settings") { () => ok(windows(settings.call(SettingsEntity.get).invoke())) }
    putBody("/settings") { (body: String) => setWindows(body) }
    get("/status") { () => status() }

    get("/networks") { () => ok(Networks(allNetworks.map(answerOf))) }
    postBody("/networks") { (body: String) => addNetwork(body) }
    get("/networks/{id}") { (id: String) => withNetwork(id)(n => ok(answerOf(n))) }
    putBody("/networks/{id}") { (id: String, body: String) => updateNetwork(id, body) }
    delete("/networks/{id}") { (id: String) => removeNetwork(id) }
    post("/networks/{id}/pseudonym-key") { (id: String) => makePseudonymKey(id) }

    get("/records/{player}") { (player: String) => record(player) }

    get("/signing-keys") { () => ok(SigningKeys(settings.call(SettingsEntity.get).invoke().signingKeys.map(keyAnswer))) }
    post("/signing-keys") { () => makeSigningKey() }
    delete("/signing-keys/{kid}") { (kid: String) => retireSigningKey(kid) }
  }

  private def settings = api.components.forKeyValueEntity(EntityId(SettingsEntity.Id))

  // ── networks ──────────────────────────────────────────────────────────────────────────────────

  private def network(id: String)   = api.components.forKeyValueEntity(EntityId(id))
  private def allNetworks           = api.views.forView(io.eitheror.casino.attribution.networks.Networks).all(1000).filter(_.exists)
  private def documentsOf(id: String) = network(id).call(NetworkDocuments.get).invoke()

  private def withNetwork(id: String)(f: NetworkState => Respond[Bytes]): Respond[Bytes] =
    val n = network(id).call(Network.get).invoke()
    if n.exists then f(n) else refuse(404, Codes.NotFound, s"no network $id")

  private def answerOf(n: NetworkState): NetworkAnswer =
    val docs = documentsOf(n.id)
    val now  = api.now()
    val a    = n.addresses.get
    val documents = Documents.Kind.all.filter(k => a.of(k).isDefined).map { kind =>
      val d = docs.of(kind)
      DocumentStatus(kind, d.flatMap(_.fetchedAt), d.flatMap(_.expiresAt), docs.fresh(kind, now), d.flatMap(_.lastError))
    }
    NetworkAnswer(n.id, a.keyDocument, a.revocationList, a.legacyTagDocument, a.codeDocument, n.roundStream, n.machine,
      n.pseudonymKeyMade, n.removed, documents)

  private def nonEmpty(v: Option[String]) = v.map(_.trim).filter(_.nonEmpty)

  /** A network (FR-025): its id, its key document and revocation list, optionally its legacy and code documents. */
  private def addNetwork(body: String): Respond[Bytes] =
    Wire.read[NetworkBody](body) match
      case Left(_) => invalid("body", "the body is id, keyDocument, revocationList and the optional documents")
      case Right(b) =>
        (nonEmpty(b.id), nonEmpty(b.keyDocument), nonEmpty(b.revocationList)) match
          case (None, _, _) => invalid("id", "a network has an id")
          case (_, None, _) => invalid("keyDocument", "a network has a key document")
          case (_, _, None) => invalid("revocationList", "a network has a revocation list")
          case (Some(id), Some(keys), Some(revocations)) =>
            val addresses = Addresses(keys, revocations, nonEmpty(b.legacyTagDocument), nonEmpty(b.codeDocument))
            try
              network(id).call(Network.add).invoke(AddNetwork(id, addresses, principal.subject, api.now())): Unit
              configure(id, b)
              answer(201, answerOf(network(id).call(Network.get).invoke()))
            catch case e: CommandError => Wire.refused(e)

  private def configure(id: String, b: NetworkBody): Unit =
    b.roundStream.foreach(on => network(id).call(Network.setRoundStream).invoke(on): Unit)
    // `<organization>/<name>`; the grantee form the ankka CLI prints, `machine:<organization>/<name>`, is taken
    // too, since a feed whose machine never matches is one nobody can read (found on kind, 2026-10-28).
    b.machine.foreach(m => network(id).call(Network.setMachine).invoke(m.trim.stripPrefix("machine:")): Unit)

  /** The same fields; an address left out keeps the one held. */
  private def updateNetwork(id: String, body: String): Respond[Bytes] =
    withNetwork(id) { n =>
      Wire.read[NetworkBody](body) match
        case Left(_) => invalid("body", "the body is the network's fields")
        case Right(b) if b.id.exists(_.trim != id) => invalid("id", "a network's id does not change")
        case Right(b) =>
          val held = n.addresses.get
          val addresses = Addresses(nonEmpty(b.keyDocument).getOrElse(held.keyDocument),
            nonEmpty(b.revocationList).getOrElse(held.revocationList),
            b.legacyTagDocument.fold(held.legacyTagDocument)(v => nonEmpty(Some(v))),
            b.codeDocument.fold(held.codeDocument)(v => nonEmpty(Some(v))))
          try
            network(id).call(Network.update).invoke(UpdateNetwork(addresses, principal.subject, api.now())): Unit
            configure(id, b)
            ok(answerOf(network(id).call(Network.get).invoke()))
          catch case e: CommandError => Wire.refused(e)
    }

  /** Removed, not deleted: its tags are refused from now on, its facts and records stand (FR-025). */
  private def removeNetwork(id: String): Respond[Bytes] =
    withNetwork(id) { _ =>
      try ok(answerOf(network(id).call(Network.remove).invoke(RemoveNetwork(principal.subject, api.now()))))
      catch case e: CommandError => Wire.refused(e)
    }

  /** The network's pseudonym key into the store (research R4): made once, never returned, never rotated. */
  private def makePseudonymKey(id: String): Respond[Bytes] =
    withNetwork(id) { n =>
      if n.pseudonymKeyMade || api.secrets.get(Pseudonyms.secretName(id)).isDefined then
        refuse(409, Codes.AlreadyExists, s"network $id has its pseudonym key; it is never replaced")
      else
        api.secrets.put(Pseudonyms.secretName(id), Pseudonyms.newKey())
        network(id).call(Network.pseudonymKeyMade).invoke(api.now()): Unit
        answer(201, PseudonymKeyAnswer(id, made = true))
    }

  // ── status ────────────────────────────────────────────────────────────────────────────────────

  /** What the operator watches: each network's reachability and counts, the transition, the open days. */
  private def status(): Respond[Bytes] =
    val now      = api.now()
    val networks = allNetworks
    val bound    = Records.countsByNetwork(api.views.forView(Records), networks.map(_.id))
    val rows = networks.map { n =>
      val docs = documentsOf(n.id)
      val undelivered = api.views.forView(Deliveries).ask(Deliveries.undelivered, "network" -> n.id).size.toLong
      NetworkStatus(n.id, n.live && Cache.reachable(docs, now), docs.of(Documents.Kind.Keys).flatMap(_.fetchedAt),
        bound.getOrElse(n.id, 0L), undelivered)
    }
    val s     = settings.call(SettingsEntity.get).invoke()
    val today = LocalDate.parse(Days.dayOf(now, api.settings.reportingZone))
    val transition = Transition(s.firstDay,
      s.firstDay.filter(_ => !s.transitionOver).map(f => LocalDate.parse(f).plusMonths(12).toString),
      s.transitionEnd.map(_.day), s.transitionEnd.map(_.endedBy), s.legacyLast30Days(today))
    val open = api.views.forView(OpenDays).ask(OpenDays.openBefore, "day" -> "9999-12-31").size.toLong
    ok(Status(rows, transition, open, s.legacyDaily))

  // ── records ───────────────────────────────────────────────────────────────────────────────────

  /** A player's attribution record, as the regulator would read it: no secret and no personal field. */
  private def record(player: String): Respond[Bytes] =
    val r = api.components.forEventSourcedEntity(EntityId(player)).call(AttributionRecord.get).invoke()
    if r.registeredAt.isEmpty && r.binding.isEmpty && r.touches.isEmpty && r.firstDepositAt.isEmpty then
      refuse(404, Codes.NotFound, s"no attribution record for player $player")
    else ok(recordAnswer(player, r))

  private def recordAnswer(player: String, r: Record.State) =
    RecordAnswer(player, r.registeredAt,
      r.binding.map(b => BindingAnswer(b.network, b.affiliate, b.link, b.campaign, b.subIds, b.clickId, b.boundAt, b.touch)),
      r.refused.map(f => RefusedAnswer(f.tag, f.reason, f.at)),
      r.touches.map(t => TouchAnswer(t.kind, t.value, t.at, t.outcome)),
      r.firstDepositAt, r.closed.map(_.category), r.erased)

  // ── signing keys ──────────────────────────────────────────────────────────────────────────────

  private def keyAnswer(k: SigningKey) = SigningKeyAnswer(k.kid, k.publicKey, k.madeAt, k.retiredAt)

  /**
   * A new signing key (research R14): the private half to the store as `signing-key/<kid>`, the public
   * half to the settings, so the key document lists it and pages are signed under it from now.
   */
  private def makeSigningKey(): Respond[Bytes] =
    val now = api.now()
    val kid = s"b-${now.toString.take(7)}-${UUID.randomUUID().toString.take(8)}"
    val (publicKey, privateKey) = Signing.generate()
    api.secrets.put(AttributionEndpoint.secretName(kid), privateKey)
    try
      val s = settings.call(SettingsEntity.addSigningKey).invoke(AddSigningKey(kid, publicKey, now))
      answer(201, keyAnswer(s.signingKeys.find(_.kid == kid).get))
    catch case e: CommandError => Wire.refused(e)

  /** Retires a key; the last live key is refused 409, or nothing could sign a page. */
  private def retireSigningKey(kid: String): Respond[Bytes] =
    try
      val s = settings.call(SettingsEntity.retireSigningKey).invoke(RetireSigningKey(kid, api.now()))
      ok(keyAnswer(s.signingKeys.find(_.kid == kid).get))
    catch case e: CommandError => Wire.refused(e)

  // ── settings ──────────────────────────────────────────────────────────────────────────────────

  private def windows(s: BrandSettings) = Windows(s.attributionWindowDays, s.bonusCodeWindowDays)

  /** The two windows, each 1 to 365 days (FR-007, FR-008); a refusal names the field. */
  private def setWindows(body: String): Respond[Bytes] =
    Wire.read[Windows](body) match
      case Left(_) => invalid("body", "the body is attributionWindowDays and bonusCodeWindowDays, in days")
      case Right(w) if w.attributionWindowDays < 1 || w.attributionWindowDays > 365 =>
        invalid("attributionWindowDays", "a window is 1 to 365 days")
      case Right(w) if w.bonusCodeWindowDays < 1 || w.bonusCodeWindowDays > 365 =>
        invalid("bonusCodeWindowDays", "a window is 1 to 365 days")
      case Right(w) =>
        ok(windows(settings.call(SettingsEntity.setWindows)
          .invoke(SetWindows(w.attributionWindowDays, w.bonusCodeWindowDays, principal.subject, api.now()))))
