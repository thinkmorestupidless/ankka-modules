package io.eitheror.casino.simulator.st8

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.simulator.Simulator
import io.eitheror.casino.simulator.api.Access
import io.eitheror.casino.simulator.core.{BrandDefaults, SessionEntity, Wiring}
import io.eitheror.casino.simulator.st8.GoJson.Value.*

/**
 * The provider's operator API (FR-007; `main.go:729-758, 593-634, 194-317`): launch, the games
 * listing and free-round provisioning, each answered as the stand-in answers. Public: a brand calls
 * it as it calls the real provider (FR-027).
 */
final class OperatorApi(sim: Simulator) extends HttpEndpoint("/api/operator/v1"):

  val acl: Acl = Access.public

  postBody("/launch") { (text: String) =>
    Http.read(text, strings = Set("game_code", "currency", "token", "player"), booleans = Set("fun_mode")) match
      case None => Http.badRequest
      case Some(body) =>
        val token = body.string("token")
        val site = Option(body.raw.get("site")).filter(!_.isNull).map(s => if s.isObject then St8Protocol.text(s, "id").getOrElse(s.toString) else s.asText())
        sim.table.bySite(site) match
          case None => Http.error(400, s"unknown site: no brand of this simulator names site ${site.getOrElse("(none)")}")
          case Some(brand) =>
            val scenario = sim.client.forKeyValueEntity(EntityId(brand.slug)).call(BrandDefaults.get).invoke()
            sim.client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.launch).invoke(
              SessionEntity.Launch(brand.slug, body.string("player"), body.string("currency"), body.string("game_code"), scenario, token,
                seed = java.util.concurrent.ThreadLocalRandom.current().nextLong(), at = Wiring.now())
            )
            Http.json(200, GoJson.strings("game_url" -> s"${sim.table.externalUrl}/game?token=$token"))
  }

  // Any method, the query and the signature ignored, as the stand-in does.
  get("/games")(() => Http.json(200, sim.catalogue.listing))
  post("/games")(() => Http.json(200, sim.catalogue.listing))

  postBody("/bonus/create") { (text: String) =>
    Http.read(text, strings = Set("bonus_id", "value", "type", "currency", "site"), stringArrays = Set("players", "game_codes"), flexInts = Set("count", "duration")) match
      case None => Http.badRequest
      case Some(body) =>
        val codes = body.strings("game_codes")
        if codes.exists(c => !sim.catalogue.has(c)) then Http.json(200, GoJson.sorted("error" -> Str("unknown_game"), "status" -> Str("error")))
        else
          val players = body.strings("players")
          val count   = body.flexInt("count")
          val bonus = Bonus(body.string("bonus_id"), players, count, body.string("value"), body.string("type"), body.string("currency"), codes,
            body.string("site"), players.map(_ -> Instance("active", count)).toMap)
          OperatorApi.envelope(sim.client.forKeyValueEntity(EntityId(BonusBook.Id)).call(BonusBook.createBonus).invoke(bonus))
  }

  get("/bonus/get") { () =>
    val id = query.raw("bonus_id").getOrElse("")
    try OperatorApi.envelope(sim.client.forKeyValueEntity(EntityId(BonusBook.Id)).call(BonusBook.get).invoke(id))
    catch case _: CommandError => Http.json(200, GoJson.strings("status" -> "error"))
  }

  postBody("/bonus/cancel") { (text: String) =>
    Http.read(text, strings = Set("bonus_id", "player", "site")) match
      case None => Http.badRequest
      case Some(body) =>
        try OperatorApi.envelope(sim.client.forKeyValueEntity(EntityId(BonusBook.Id)).call(BonusBook.cancel).invoke(BonusBook.Cancel(body.string("bonus_id"), body.string("player"))))
        catch case _: CommandError => Http.json(200, GoJson.strings("status" -> "error"))
  }

object OperatorApi:
  /** `{"bonus":{"bonus_id","instances":[{player,remaining,status}]},"status":"ok"}`, instances in `players` order. */
  def envelope(b: Bonus): Http.Answer =
    val instances = b.players.flatMap(p => b.instances.get(p).map(i =>
      GoJson.sorted("player" -> Str(p), "remaining" -> Num(i.remaining.toString), "status" -> Str(i.status))))
    Http.json(200, GoJson.sorted("bonus" -> GoJson.sorted("bonus_id" -> Str(b.bonusId), "instances" -> Arr(instances)), "status" -> Str("ok")))
