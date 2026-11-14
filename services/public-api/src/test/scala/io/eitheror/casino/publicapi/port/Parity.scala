package io.eitheror.casino.publicapi.port

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.contract.port.Recording
import io.eitheror.casino.publicapi.it.{Games, Keys, Kit, MadeKey, Reply}
import io.eitheror.casino.publicapi.records.Records

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Replays the recordings of tests/port/public-api (contracts/recording.md) against the service.
 *
 * The seed is not replayed: the original made it through its own routes (registration, the wallet's deposit route,
 * fake-st8's rounds, the operator's grants), which here are other services. Instead every record the original served
 * for a seeded player, in any recording, becomes the fact that yields that record here, published to the topics of a
 * kit of the record's brand. Then each recording's steps are sent to that brand's kit with an access key of the grant
 * the step's token stood for, and the answers are compared by the contract's rules: ids by role, authentication codes
 * by class, `computedAt` and snapshot ids by shape, and only the seeded records of a list (the kind stack holds others).
 */
object Parity:
  val root: Path      = Paths.get(sys.props.getOrElse("casino.root", "../..")).toAbsolutePath.normalize
  val directory: Path = root.resolve("tests/port/public-api")
  val SeedName        = "public-api-seed"

  private val mapper = ObjectMapper()

  def file(name: String): Path = directory.resolve(s"$name.json")
  def present(name: String): Boolean = Files.isRegularFile(file(name))

  final case class Difference(recording: String, step: String, at: String, expected: String, observed: String):
    def message: String = s"$recording / $step $at: expected $expected, observed $observed"

  // ── divergences ─────────────────────────────────────────────────────────────────────────────────

  /**
   * Where the service answers otherwise than the original on purpose: why (the decision that makes it so), the steps it
   * holds at, and the fields it covers (a prefix; `*` for every field of the step).
   */
  final case class Divergence(why: String, holds: (String, Recording.Step) => Boolean, fields: Seq[String]):
    def covers(at: String): Boolean = fields.contains("*") || fields.exists(p => at == p || at.startsWith(p + ".") || at.startsWith(p + "["))

  private def collection(word: String)(s: Recording.Step): Boolean =
    s.request.path.takeWhile(_ != '?').split('/').lastOption.contains(word)
  private def record(word: String)(s: Recording.Step): Boolean =
    val parts = s.request.path.takeWhile(_ != '?').split('/').toVector
    parts.length >= 2 && parts(parts.length - 2) == word
  private def anyTransactions(s: Recording.Step): Boolean = collection("transactions")(s) || record("transactions")(s)

  /** Every field of a record of `kind` in an answer: a single record, or any element of a page. */
  private def fieldOf(name: String): Seq[String] = Seq(s"$$.$name") ++ (0 until 500).map(i => s"$$.data[$i].$name")

  val divergences: Vector[Divergence] = Vector(
    Divergence("a deposit or bonus credit is `completed` here (fields.md Enums: the wallet view's word); the original says `settled`",
      (_, s) => anyTransactions(s), fieldOf("status")),
    Divergence("a loss row carries no amount here (`round_settled_as_loss` has none, fields.md Transaction.amount); the original copies the stake",
      (_, s) => anyTransactions(s), fieldOf("amount")),
    Divergence("a bonus award's value is the reward's amount in major units here; the original renders `face_value` cents as major units",
      (_, s) => collection("bonus-awards")(s) || record("bonus-awards")(s), fieldOf("value")),
    Divergence("free rounds with no offer are an award here (FreeSpinsGranted, fields.md G6); the original counts their claim and serves nothing",
      (_, s) => collection("bonus-awards")(s), Seq("$.page.total", "$.page.hasMore")),
    Divergence("a bet and its BET transaction are one fact here (fields.md Bet.id), so the bet is placed at the transaction's time",
      (_, s) => collection("bets")(s) || record("bets")(s), fieldOf("placedAt")),
    Divergence("a lost round's bet is `loss` here (fields.md Bet.status, `round_settled_as_loss`); the original's bet record says `settled`",
      (_, s) => collection("bets")(s) || record("bets")(s), fieldOf("status"))
    ,
    Divergence("the original's players page answers a total twice the players it serves (6 for 3 on a fresh stack); here the total is the records paged",
      (_, s) => s.request.path.takeWhile(_ != '?') == "/v1/players", Seq("$.page.total")),
    Divergence("the OpenAPI document and its reference page are the amended contract (contracts/amendments.md: keys for partners, the key_* codes); ContractSnapshotSuite holds them to the committed document",
      (_, s) => s.request.path == "/v1/openapi.json" || s.request.path == "/v1/docs", Seq("*"))
  )

  def divergence(recording: String, step: Recording.Step, at: String): Option[Divergence] =
    divergences.find(d => d.holds(recording, step) && d.covers(at))

  // ── authentication codes (contracts/recording.md) ───────────────────────────────────────────────

  val codeClass: Map[String, String] = Map(
    "jwt_missing" -> "key_missing", "jwt_invalid" -> "key_invalid", "jwt_verification_failed" -> "key_invalid",
    "jwt_issuer_mismatch" -> "key_invalid", "jwt_expired" -> "key_expired", "grant_disabled" -> "key_disabled")

  /** Codes with no counterpart here; a step answered one is not replayed. */
  val unreplayable: Set[String] = Set("actor_type_invalid", "grant_absent")
  def unreplayed(step: Recording.Step): Boolean =
    step.response.body.exists(b => Option(b.get("error")).map(_.asText).exists(e => unreplayable(e) || e.startsWith("scope_")))

  // ── the recordings ──────────────────────────────────────────────────────────────────────────────

  /** A recording and the run's roles (role → the original's id). */
  final case class Loaded(recording: Recording, roles: Map[String, String], brand: String, otherBrand: String)

  def load(name: String): Loaded =
    val path = file(name)
    val raw  = mapper.readTree(path.toFile)
    val rec  = Recording.parse(path, raw).fold(e => throw AssertionError(s"$path: $e"), identity)
    val roles = Option(raw.get("roles")).toVector.flatMap(_.properties().asScala.map(e => e.getKey -> e.getValue.asText)).toMap
    Loaded(rec, roles, rec.original.getOrElse("brand", "spinvibe"), rec.original.getOrElse("otherBrand", "goldvibe"))

  def all: Vector[Loaded] =
    if !Files.isDirectory(directory) then Vector.empty
    else Files.list(directory).iterator.asScala.map(_.getFileName.toString).filter(_.endsWith(".json")).map(_.stripSuffix(".json"))
      .toVector.sorted.map(load)

  // ── the world: the seed's records as facts ──────────────────────────────────────────────────────

  private val Words = Set("players", "sessions", "rounds", "bets", "transactions", "bonus-awards")

  /** Every record a step served, with the kind its route names. */
  private def served(step: Recording.Step): Vector[(String, JsonNode)] =
    val parts = step.request.path.takeWhile(_ != '?').split('/').toVector.filter(_.nonEmpty)
    val body  = step.response.body.filter(_ => step.response.status == 200)
    val last  = parts.lastOption.getOrElse("")
    body.toVector.flatMap { b =>
      if Words(last) && b.has("data") then b.get("data").elements().asScala.toVector.map(last -> _)
      else if parts.length >= 2 && Words(parts(parts.length - 2)) && b.has("id") then Vector(parts(parts.length - 2) -> b)
      else Vector.empty
    }

  private def text(n: JsonNode, field: String): Option[String] = Option(n.get(field)).filterNot(_.isNull).map(_.asText).filter(_.nonEmpty)
  private def instant(n: JsonNode, field: String): Option[Instant] = text(n, field).map(Instant.parse)
  private def minor(major: String): Long = (BigDecimal(major) * 100).toLongExact

  /** A brand's facts in the order the original made what they stand for, and the ids the service gives each record. */
  final case class BrandWorld(brand: String, facts: Vector[(Instant, Kit => Unit)], ids: Map[String, String], games: Vector[JsonNode],
      counts: Map[(String, String), Int])

  final case class World(a: BrandWorld, b: BrandWorld, roles: Map[String, String]):
    /** The original's id → the service's. */
    val ids: Map[String, String] = a.ids ++ b.ids
    def ours(original: String): String = ids.getOrElse(original, original)

  def world(): World =
    val loaded = all
    val roles  = loaded.flatMap(_.roles).toMap
    val first  = loaded.headOption.getOrElse(throw AssertionError("no recordings"))
    val seeded = roles.collect { case (r, id) if r.startsWith("player-") => id }.toSet
    // Each record once, the fullest copy (a full grant serves more fields than an empty one).
    val records = loaded.flatMap(_.recording.steps).flatMap(served).groupBy((k, n) => k -> n.path("id").asText).view
      .mapValues(_.map(_._2).maxBy(_.size)).toMap
      .collect { case ((kind, id), n) if seeded(id) || text(n, "playerId").exists(seeded) => (kind, id, n) }.toVector
    val seed = loaded.find(_.recording.scenario == SeedName).map(_.recording)
    def build(brand: String) = brandWorld(brand, records.filter((_, _, n) => text(n, "brandId").contains(brand)), seed, loaded)
    World(build(first.brand), build(first.otherBrand), roles)

  private def brandWorld(brand: String, records: Vector[(String, String, JsonNode)], seed: Option[Recording], loaded: Vector[Loaded]): BrandWorld =
    def of(kind: String) = records.collect { case (k, _, n) if k == kind => n }
    val txs    = of("transactions")
    // A player whose rounds no recording read (player-2-of-A's 250 transactions): each round from its stake and
    // settlement, in the player's session, named by the provider's round. Its fields are read by nothing but the runs.
    val observedPlayers = of("rounds").map(_.path("playerId").asText).toSet
    val made = txs.filter(t => text(t, "type").contains("BET") && !observedPlayers(t.path("playerId").asText)).map { bet =>
      val pl = bet.path("playerId").asText; val provider = bet.path("roundId").asText
      val settle = txs.find(t => t.path("roundId").asText == provider && t.path("id").asText != bet.path("id").asText)
      val session = of("sessions").filter(_.path("playerId").asText == pl).filter(s => !instant(s, "startedAt").exists(_.isAfter(instant(bet, "createdAt").get)))
        .maxByOption(s => instant(s, "startedAt")).map(_.path("id").asText).getOrElse("")
      val o = mapper.createObjectNode()
      o.put("id", provider); o.put("brandId", brand); o.put("playerId", pl); o.put("sessionId", session)
      o.put("gameId", bet.path("gameId").asText); o.put("startedAt", bet.path("createdAt").asText)
      settle.foreach(x => o.put("settledAt", x.path("createdAt").asText))
      o.put("stakeTotal", bet.path("amount").asText)
      o.put("winTotal", settle.filter(x => text(x, "type").contains("WIN")).map(_.path("amount").asText).getOrElse("0.00"))
      o.put("currency", bet.path("currency").asText); o.put("status", "settled")
      o: JsonNode
    }
    val rounds = (of("rounds") ++ made).map(n => n.path("id").asText -> n).toMap
    // The original's transactions name the provider's round (their `roundId`); its rounds and bets name the round record.
    // Every seeded round has one stake, so a player's BET transactions and rounds, each in time order, are the same rounds.
    def inOrder(ns: Vector[JsonNode], at: String) = ns.groupBy(_.path("playerId").asText).view.mapValues(_.sortBy(n => instant(n, at))).toMap
    val roundsInOrder = inOrder(rounds.values.toVector, "startedAt")
    val betTxs        = inOrder(txs.filter(t => text(t, "type").contains("BET")), "createdAt")
    val roundOfProvider: Map[String, String] = betTxs.toVector.flatMap { (pl, ts) =>
      val rs = roundsInOrder.getOrElse(pl, Vector.empty)
      assert(rs.size == ts.size, s"$pl has ${ts.size} BET transactions and ${rs.size} rounds")
      ts.zip(rs).flatMap((t, r) => text(t, "roundId").map(_ -> r.path("id").asText))
    }.toMap
    val betTxOfRound: Map[String, String] = betTxs.values.flatten.flatMap(t => text(t, "roundId").flatMap(roundOfProvider.get).map(_ -> t.path("id").asText)).toMap
    val facts  = Vector.newBuilder[(Instant, Kit => Unit)]
    val ids    = Map.newBuilder[String, String]
    val eur    = Currency.unsafe("EUR")
    def cur(n: JsonNode) = text(n, "currency").map(Currency.unsafe).getOrElse(eur)
    def key(round: String) = s"st8/$round"
    def wallet(round: String) = s"w-$round"

    // The emails the original registered: a record under an empty grant does not carry them.
    val emails = seed.toVector.flatMap(_.steps).filter(_.name.startsWith("setup: register ")).flatMap { s =>
      for b <- s.request.body; e <- text(b, "email"); body <- s.response.body; id <- text(body, "playerId") yield id -> e
    }.toMap

    of("players").foreach { n =>
      val id = n.path("id").asText; val at = instant(n, "registeredAt").get
      def p(field: String) = text(n, field).map(Personal(_))
      facts += at -> (_.publish(f.CasinoPlayers, f.PlayerRegistered(id, at, None, None, None, text(n, "country"), text(n, "currency"), None, at,
        givenName = p("firstName"), familyName = p("lastName"), dateOfBirth = p("dateOfBirth"), telephone = p("phone"),
        addressLine = p("addressLine1"), city = p("city"), postCode = p("postalCode"),
        email = text(n, "email").orElse(emails.get(id)).map(Personal(_)), language = text(n, "language"), stateProvince = p("stateProv"))))
    }
    of("sessions").foreach { n =>
      val at = instant(n, "startedAt").get
      facts += at -> (_.publish(f.CasinoSessions, f.GameSessionStarted(n.path("playerId").asText, n.path("id").asText, n.path("gameId").asText,
        n.path("id").asText, at)))
    }
    rounds.values.foreach { n =>
      val id = n.path("id").asText; val pl = n.path("playerId").asText; val game = n.path("gameId").asText
      val session = n.path("sessionId").asText; val c = cur(n)
      val stake = minor(n.path("stakeTotal").asText("0")); val win = minor(n.path("winTotal").asText("0"))
      val opened = instant(n, "startedAt").get
      ids += id -> Records.roundId(key(id))
      facts += opened -> (_.publish(f.CasinoRounds, f.RoundOpened(pl, c, key(id), "st8", id, game, session, None, opened)))
      instant(n, "settledAt").foreach { at =>
        val outcome = if text(n, "status").contains("cancelled") then "cancelled" else if win > 0 then "win" else "loss"
        facts += at -> (_.publish(f.CasinoRounds, f.RoundSettled(pl, c, key(id), "st8", id, game, session, MinorUnits.unsafe(stake),
          MinorUnits.unsafe(win), SignedMinorUnits(win - stake), outcome, Vector.empty, Vector.empty, Vector.empty, None, at,
          openedAt = Some(opened), realStakeCents = Some(MinorUnits.unsafe(stake)), realWinCents = Some(MinorUnits.unsafe(win)))))
      }
    }
    val awards = of("bonus-awards")
    txs.foreach { t =>
      val id = t.path("id").asText; val pl = t.path("playerId").asText; val c = cur(t); val at = instant(t, "createdAt").get
      val amount = MinorUnits.unsafe(minor(t.path("amount").asText("0")))
      val round  = text(t, "roundId").map(p => roundOfProvider.getOrElse(p, throw AssertionError(s"no bet names the round of $t")))
      val game   = round.flatMap(rounds.get).flatMap(text(_, "gameId")).orElse(text(t, "gameId")).getOrElse("")
      val session = round.flatMap(rounds.get).flatMap(text(_, "sessionId"))
      val z = SignedMinorUnits(0); val m0 = MinorUnits.unsafe(0)
      text(t, "type") match
        case Some("DEPOSIT") =>
          facts += at -> (_.publish(f.MoneyTransactions, f.DepositConfirmed(pl, c, id, amount, text(t, "paymentRef").getOrElse(""), 1, z, m0, at)))
        case Some("BET") =>
          facts += at -> (_.publish(f.MoneyTransactions, f.BetPlaced(pl, c, id, wallet(round.get), game, amount, "real", z, m0, at,
            gameRoundId = round.map(key), sessionId = session)))
        case Some("WIN") =>
          facts += at -> (_.publish(f.MoneyTransactions, f.WinCredited(pl, c, id, wallet(round.get), game, amount, "real", z, m0, at,
            gameRoundId = round.map(key), sessionId = session)))
        case Some("LOSS") =>
          facts += at -> (_.publish(f.MoneyTransactions, f.RoundSettledAsLoss(pl, c, id, wallet(round.get), z, m0, at, gameRoundId = round.map(key))))
        case Some("BONUS") =>
          val award = awards.find(a => text(a, "playerId").contains(pl)).map(_.path("id").asText).getOrElse(id)
          facts += at -> (_.publish(f.MoneyTransactions, f.RestrictedFundsAwarded(pl, c, id, award, amount, amount, z, amount, at)))
        case other => throw AssertionError(s"a $other transaction has no fact here: $t")
    }
    // A bet is its BetPlaced transaction here; the original's bet record had an id of its own. A transaction's round is
    // the provider's round there and the round record here: the same round, by role.
    of("bets").foreach(b => text(b, "roundId").flatMap(betTxOfRound.get).foreach(t => ids += b.path("id").asText -> t))
    roundOfProvider.foreach((p, r) => ids += p -> Records.roundId(key(r)))
    awards.foreach { n =>
      val pl = n.path("playerId").asText; val at = instant(n, "grantedAt").get
      // The reward's amount: the bonus credit the wallet made for it, which the original's `value` misstates.
      val amount = txs.find(t => text(t, "type").contains("BONUS") && text(t, "playerId").contains(pl)).map(t => minor(t.path("amount").asText))
        .getOrElse(minor(n.path("value").asText("0")))
      facts += at -> (_.publish(f.CasinoRewards, f.BonusGranted(pl, n.path("id").asText, n.path("name").asText, n.path("offerId").asText,
        n.path("type").asText, MinorUnits.unsafe(amount), cur(n), None, at, expiresAt = instant(n, "expiresAt"))))
    }
    // The operator's free rounds with no offer (the seed's `give … free rounds`): an award here, nothing the original served.
    seed.toVector.flatMap(_.steps).filter(_.name.contains("free rounds with no offer")).foreach { s =>
      val player = s.request.path.split('/').last
      if records.exists((k, id, _) => k == "players" && id == player) then
        for b <- s.request.body do
          val spins = b.path("spinCount").asInt; val value = minor(b.path("spinValue").asText("0"))
          val at = seed.get.steps.takeWhile(_ ne s).reverse.flatMap(_.response.body).flatMap(x => instant(x, "grantedAt")).headOption
            .getOrElse(Instant.EPOCH)
          facts += at -> (_.publish(f.CasinoRewards, f.FreeSpinsGranted(player, UUID.nameUUIDFromBytes(s"spins/$player".getBytes).toString,
            "free rounds", "", "FREE_SPINS", spins, Vector(b.path("gameId").asText), at, amountCents = Some(MinorUnits.unsafe(spins * value)),
            currency = Some(Currency.unsafe("EUR")))))
    }
    val games = loaded.filter(_.recording.scenario != SeedName).flatMap(_.recording.steps)
      .filter(s => s.request.path.startsWith("/v1/games?") && s.response.status == 200 && s.request.headers.values.exists(_.contains("{fullToken}")))
      .flatMap(_.response.body).headOption.toVector.flatMap(_.path("data").elements().asScala)
    // What each player's collections must reach before the world is read: one record per fact of the kind.
    val spins = seed.toVector.flatMap(_.steps).count(s => s.name.contains("free rounds with no offer"))
    val counts = of("players").map(_.path("id").asText).flatMap { pl =>
      def n(kind: String) = of(kind).count(r => text(r, "playerId").contains(pl))
      val extra = if spins > 0 && seed.exists(_.steps.exists(s => s.name.contains("free rounds with no offer") && s.request.path.endsWith(pl))) then 1 else 0
      Vector((pl, "player") -> 1, (pl, "sessions") -> n("sessions"), (pl, "rounds") -> rounds.values.count(r => text(r, "playerId").contains(pl)),
        (pl, "transactions") -> n("transactions"), (pl, "bonus-awards") -> (n("bonus-awards") + extra))
    }.toMap
    BrandWorld(brand, facts.result().sortBy(_._1), ids.result(), games, counts)

  /** A recorded game as `game-provider`'s route answers it (`CatalogueReads.toJson`). */
  def providerGame(g: JsonNode): String =
    val o = mapper.createObjectNode()
    o.put("gameId", g.path("id").asText); o.put("provider", "st8")
    Seq("name", "category", "developerId", "developerName", "thumbnailUrl").foreach(k => o.set(k, g.get(k)))
    o.put("enabled", true); o.set("funMode", g.get("funMode"))
    Seq("bonusTypes", "features", "themes", "tags", "restrictedTerritories", "prohibitedTerritories").foreach(k => o.set(k, g.get(k)))
    o.putNull("rtp"); o.putNull("volatility"); o.putNull("hitRatio"); o.putNull("maxPayoutCoeff"); o.putNull("releaseDate")
    o.putNull("deprecationDate"); o.put("removedFromFeed", false)
    mapper.writeValueAsString(o)

  // ── the replay ──────────────────────────────────────────────────────────────────────────────────

  /** One brand's service with the seed's facts in it, and the keys that stand for the original's partners. */
  final class Brand(val world: BrandWorld):
    val kit = Kit(world.brand)
    kit.provider.set(world.games.map(providerGame)*)
    // The facts' instants are the original's: the clock goes past them, as the original's ran on after the seed.
    world.facts.lastOption.foreach((last, _) => kit.clock.moveBy(scala.concurrent.duration.Duration.fromNanos(
      java.time.Duration.between(kit.now, last).toNanos.max(0))))
    world.facts.foreach((_, publish) => publish(kit))
    val full: MadeKey  = Keys.allFields(kit)
    val bare: MadeKey  = Keys.noFields(kit)
    val quota: MadeKey = Keys.create(kit, "quota", rate = Some(5))
    /** The snapshot the seed's run published here, which stands for the original's `snapshot-1-of-<brand>`. */
    @volatile var seeded: Option[String] = None

  /** The steps the driver made with `Session.Eventually`: their recorded answer is the last of a poll. */
  val Polled: Set[String] = Set("a disabled game")

  private val Placeholder = """\{([A-Za-z0-9_\-]+)\}""".r

  /** Replays one recording; `a` and `b` are the brands' services, the seed's facts already in them. */
  def replay(loaded: Loaded, world: World, a: Brand, b: Brand, stats: Brand => Option[String]): Vector[Difference] =
    val name    = loaded.recording.scenario
    val values  = scala.collection.mutable.Map.empty[String, String]
    def value(role: String): String =
      values.getOrElseUpdate(role, role match
        case "snapshot-1-of-A" => a.seeded.getOrElse(throw AssertionError("the seed's run published no snapshot"))
        case "snapshot-1-of-B" => b.seeded.getOrElse(throw AssertionError("the seed's run published no snapshot"))
        case _                 => loaded.roles.get(role).map(world.ours).getOrElse(UUID.randomUUID().toString))
    def fill(t: String) = Placeholder.replaceAllIn(t, m => java.util.regex.Matcher.quoteReplacement(value(m.group(1))))
    loaded.recording.steps.flatMap { step =>
      if step.name.startsWith("setup: the player statistics run for ") then
        stats(if step.name.endsWith(" A") then a else b).foreach(snapshot =>
          step.capture.keys.foreach(role => values(role) = snapshot))
        Vector.empty
      else if step.name.startsWith("setup: disable ") then
        val game = step.name.stripPrefix("setup: disable ").takeWhile(_ != ' ')
        a.kit.provider.set(a.world.games.filterNot(_.path("id").asText == game).map(providerGame)*)
        // Past the catalogue's cache, as the original's poll waited out its own.
        a.kit.moveClock(scala.concurrent.duration.Duration.fromNanos(a.kit.settings.catalogueFor.plusSeconds(1).toNanos))
        Vector.empty
      else if step.name.startsWith("setup: put the brand") then
        a.kit.provider.set(a.world.games.map(providerGame)*)
        Vector.empty
      else if step.name.startsWith("setup:") || step.name.startsWith("observe:") || step.request.service.exists(_ != "public-api") then Vector.empty
      else if unreplayed(step) then Vector.empty
      else
        val token = step.request.headers.get("Authorization")
        val (brand, key) = token match
          case Some(t) if t.contains("{otherToken}") => (b, Some(b.bare.secret))
          case Some(t) if t.contains("{bareToken}")  => (a, Some(a.bare.secret))
          case Some(t) if t.contains("{quotaToken}") => (a, Some(a.quota.secret))
          case Some(t) if t.contains("{fullToken}")  => (a, Some(a.full.secret))
          case Some(t)                               => (a, Some(t.stripPrefix("Bearer ")))
          case None                                  => (a, None)
        val path  = fill(step.request.path)
        def send() = brand.kit.request(step.request.method, path, step.request.body.map(_.toString),
          brand.kit.fromInternet ++ key.map(k => "Authorization" -> s"Bearer $k"))
        // A step the driver polled (`Eventually`) recorded its last answer; it is polled here too, and only it.
        val reply =
          if !Polled(step.name) then send()
          else Iterator.continually { val r = send(); if r.status != step.response.status then Thread.sleep(500); r }
            .take(60).find(_.status == step.response.status).getOrElse(send())
        compare(name, step, reply, world)
    }

  private val Uuid    = """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""".r
  private val Instant_ = """\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z""".r
  private val ByShape = Set("computedAt", "snapshot", "snapshotId")

  private def compare(name: String, step: Recording.Step, reply: Reply, world: World): Vector[Difference] =
    def diff(at: String, e: String, o: String) = Difference(name, step.name, at, e, o)
    val status = Option.when(reply.status != step.response.status)(diff(".status", step.response.status.toString, s"${reply.status} ${reply.text.take(300)}"))
    val observed = Try(mapper.readTree(reply.text)).toOption.filter(_ != null)
    val known    = world.ids.keySet ++ world.ids.values ++ world.roles.values
    val fields = step.response.body.toVector.flatMap { expected =>
      // Only the seeded records of a list: the kind stack held other players' records too.
      val (body, filtered) = seededOnly(expected, known)
      val e = flatten("$", body).map((p, v) => p -> world.ours(v))
      val o = observed.toVector.flatMap(n => flatten("$", seededOnly(n, known)._1)).toMap
      e.flatMap { (path, ev) =>
        val leaf = if path.startsWith("$.snapshot.") then "snapshot" else path.split('.').last.takeWhile(_ != '[')
        o.get(path) match
          case _ if filtered && path.startsWith("$.page.") => None
          case None if divergence(name, step, path).isDefined => None
          case None => Some(diff(path, ev, "absent"))
          case Some(ov) if ByShape(leaf) => Option.when(shape(ov) != shape(ev))(diff(path, s"a value shaped like $ev", ov))
          case Some(ov) if path == "$.error" && codeClass.getOrElse(ev, ev) == ov => None
          case Some(ov) if ov == ev => None
          case Some(_) if divergence(name, step, path).isDefined => None
          case Some(ov) => Some(diff(path, ev, ov))
      }
    }
    status.toVector ++ fields

  private def shape(v: String): String =
    if Uuid.matches(v) then "uuid" else if Instant_.matches(v) then "instant" else if v == "null" then "null" else "text"

  /** The answer with its `data` cut to the seeded records (by id, or a ranking's player), and whether any was cut. */
  private def seededOnly(n: JsonNode, known: Set[String]): (JsonNode, Boolean) =
    Option(n.get("data")).filter(_.isArray) match
      case Some(data) if data.elements().asScala.exists(d => d.has("id") || d.has("playerId")) =>
        val keep = data.elements().asScala.toVector.filter(d =>
          Option(d.get("id")).map(_.asText).exists(known) || (!d.has("id") && Option(d.get("playerId")).map(_.asText).exists(known)))
        val copy = n.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]()
        val arr  = copy.putArray("data"); keep.foreach(arr.add)
        (copy, keep.size != data.size)
      case _ => (n, false)

  private def flatten(prefix: String, n: JsonNode): Vector[(String, String)] =
    if n.isObject then n.properties().asScala.toVector.flatMap(e => flatten(s"$prefix.${e.getKey}", e.getValue))
    else if n.isArray then n.elements().asScala.toVector.zipWithIndex.flatMap((c, i) => flatten(s"$prefix[$i]", c))
    else Vector(prefix -> (if n.isNull then "null" else n.asText))

  // ── one world per test JVM ──────────────────────────────────────────────────────────────────────

  /** The seed's world in two brands' services, built once; the statistics run once at the original's hour. */
  lazy val shared: Option[(World, Brand, Brand)] =
    Option.when(present(SeedName)) {
      val w = world()
      val a = Brand(w.a); val b = Brand(w.b)
      Seq(a, b).foreach(x => sys.addShutdownHook(x.kit.stop()))
      // The seed's runs: at the hour the original's ran (its answers' `computedAt`), once the facts are in the views.
      val hour = all.flatMap(_.recording.steps).flatMap(_.response.body).flatMap(n => text(n, "computedAt")).map(Instant.parse).minOption
      Seq(a, b).foreach { x =>
        hour.foreach(h => if h.isAfter(x.kit.now) then x.kit.clock.moveBy(scala.concurrent.duration.Duration.fromNanos(
          java.time.Duration.between(x.kit.now, h).toNanos)))
        settle(x)
        x.seeded = runStats(x)
      }
      (w, a, b)
    }

  /** Waits until every record the facts make is readable: each player, and their collections at the counts made. */
  def settle(x: Brand): Unit =
    x.world.counts.foreach { case ((player, collection), n) =>
      x.kit.eventually(s"${x.world.brand}: $player's $collection at $n", scala.concurrent.duration.Duration(120, "s")) {
        val path = if collection == "player" then s"/players/$player" else s"/players/$player/$collection?limit=1"
        val r    = x.kit.v1(path, Some(x.full.secret))
        Option.when(r.status == 200 && (collection == "player" || r.json.path("page").path("total").asInt == n))(())
      }
    }

  /** Runs the brand's statistics as the original's hourly run did, and answers the snapshot it published. */
  def runStats(brand: Brand): Option[String] =
    Games.compute(brand.kit).currentSlot.flatMap(_._2.snapshotId)
