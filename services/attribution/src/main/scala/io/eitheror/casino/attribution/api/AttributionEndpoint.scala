package io.eitheror.casino.attribution.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.api.Wire.{*, given}
import io.eitheror.casino.attribution.carriers.{Cursor, FeedRows, Pages}
import io.eitheror.casino.attribution.money.Days
import io.eitheror.casino.attribution.networks.Network
import io.eitheror.casino.attribution.record.AttributionRecord
import io.eitheror.casino.attribution.settings.{BrandSettings, SettingsEntity}
import io.eitheror.casino.contract.access.Routes

import java.time.LocalDate
import scala.util.Try

/**
 * The brand's protocol surface (contracts/routes.md *Public and machines*, *Service*): the key document
 * for anyone, a network's feed for the machine its grant names, the erasure for `players`.
 */
final class AttributionEndpoint(api: Api) extends HttpEndpoint(s"/v1/casino/brands/${api.brand}/attribution"):
  val acl: Acl = Acl.DenyAll

  withAcl(Routes.public) {
    get("/keys") { () => raw(200, Json.render(AttributionEndpoint.keyDocument(settings, api.settings.reportingZone, api.now()))) }
  }

  withAcl(Routes.machine) {
    get("/networks/{network}/feed") { (network: String) => feed(network) }
  }

  withAcl(Routes.service()) {
    post("/players/{player}/erasure") { (player: String) => erase(player) }
  }

  /**
   * The player's erasure, for `players` (research R11): the record keeps its binding, since nothing in it is
   * personal; a `player-closed` is recorded when none was. Answers that nothing personal is held.
   */
  private def erase(player: String): Respond[Bytes] =
    api.components.forEventSourcedEntity(EntityId(player)).call(AttributionRecord.erase).invoke(api.now()): Unit
    ok(ErasureAnswer(player, erased = true, Vector.empty))

  private def settings: BrandSettings =
    api.components.forKeyValueEntity(EntityId(SettingsEntity.Id)).call(SettingsEntity.get).invoke()

  /** The page size asked for, 1 to 1,000, the maximum when none is asked; `Left` when it is not one. */
  private def limit: Either[String, Int] =
    query.raw("limit").map(_.trim).filter(_.nonEmpty) match
      case None => Right(Pages.MaxLimit)
      case Some(v) => Try(v.toInt).toOption.filter(l => l >= 1 && l <= Pages.MaxLimit).toRight(s"a limit is 1 to ${Pages.MaxLimit}")

  /**
   * A signed page of a network's facts after the cursor (research R14). Only the machine the network names
   * reads it: any other, or an unknown network, is `404`, so a machine learns nothing of other networks.
   */
  private def feed(network: String): Respond[Bytes] =
    Routes.machineCaller(caller) match
      case Left(problem) => refuse(problem.status, "FORBIDDEN", problem.message)
      case Right(machine) =>
        val node = api.components.forKeyValueEntity(EntityId(network)).call(Network.get).invoke()
        if !node.exists || !machine.forall((org, name) => node.machine.contains(s"$org/$name")) then
          refuse(404, Codes.NotFound, s"no feed for network $network")
        else
          (limit, Cursor.decode(network, query.raw("cursor"))) match
            case (Left(reason), _) => invalid("limit", reason)
            case (_, Left(reason)) => invalid("cursor", reason)
            case (Right(size), Right(after)) =>
              val s = settings
              s.signingKey.flatMap(k => api.secrets.get(AttributionEndpoint.secretName(k.kid)).map(k.kid -> _)) match
                case None => refuse(503, "NO_SIGNING_KEY", "the brand has no signing key: an operator makes one")
                case Some((kid, privateKey)) =>
                  val rows  = api.views.forView(FeedRows).ask(FeedRows.page, size, "network" -> network, "after" -> after.toString)
                  val facts = rows.flatMap(r => Json.parse(r.fact))
                  raw(200, Json.render(Pages.assemble(facts, network, rows.lastOption.fold(after)(_.seq), kid, privateKey)))

object AttributionEndpoint:
  val CacheSeconds = 3600

  /** Where a signing key's private half is kept in the service's store. */
  def secretName(kid: String): String = s"signing-key/$kid"

  /** The brand's key document (contracts/protocol.md, research R14, R16): its live keys and the transition. */
  def keyDocument(s: BrandSettings, zone: java.time.ZoneId, now: java.time.Instant) =
    val today = LocalDate.parse(Days.dayOf(now, zone))
    val keys  = s.liveKeys.map(k => Json.obj("kid" -> k.kid, "kty" -> "OKP", "crv" -> "Ed25519", "x" -> k.publicKey))
    val doc   = Json.obj("keys" -> Json.arr(keys*), "cacheSeconds" -> CacheSeconds)
    s.firstDay.foreach { first =>
      Json.set(doc, "transition", Json.obj(
        "firstDay"             -> first,
        "endsAt"               -> Option.when(!s.transitionOver)(LocalDate.parse(first).plusMonths(12).toString),
        "endedOn"              -> s.transitionEnd.map(_.day),
        "endedBy"              -> s.transitionEnd.map(_.endedBy),
        "legacyTagsLast30Days" -> s.legacyLast30Days(today)
      ))
    }
    doc
