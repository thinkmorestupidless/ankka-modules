package io.eitheror.casino.gameprovider.catalogue

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, ErrorCode, Serializer, Serializers}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.gameprovider.rounds.{Round, RoundEvent}

import java.time.Instant

// ── Game: one game of one provider (FR-021, data-model.md) ─────────────────────────────────────

/** The provider's fields for a game, as its game list sends them (`St8GameSeeder.scala:157-218`). */
final case class GameFields(
    name: String,
    developerId: String,
    developerName: String,
    category: String,
    themes: Vector[String] = Vector.empty,
    features: Vector[String] = Vector.empty,
    bonusTypes: Vector[String] = Vector.empty,
    restrictedTerritories: Vector[String] = Vector.empty,
    prohibitedTerritories: Vector[String] = Vector.empty,
    deprecationDate: Option[String] = None,
    releaseDate: Option[String] = None,
    rtp: Option[String] = None,
    volatility: Option[Int] = None,
    hitRatio: Option[String] = None,
    maxPayoutCoeff: Option[String] = None,
    thumbnailUrl: String = "",
    funMode: Boolean = false,
    enabled: Boolean = true
):
  /** Bonus types, features and themes, the original's derived tags. */
  def tags: Vector[String] = (bonusTypes ++ features ++ themes).distinct

final case class GameState(
    provider: String = "",
    code: String = "",
    fields: Option[GameFields] = None,
    removedFromFeed: Boolean = false,
    lastSeenAt: Option[Instant] = None
):
  def exists: Boolean = fields.isDefined

final case class UpsertGame(provider: String, code: String, fields: GameFields, at: Instant)

object GameCodecs:
  given Serializer[GameState]  = Codecs.serializer[GameState]("game-state")
  given Serializer[UpsertGame] = Codecs.serializer[UpsertGame]("upsert-game")

import GameCodecs.given
import Serializers.given

/** A game of one provider, id `<provider>/<code>`. The brand's own decision is not here: it is read at read time (FR-022). */
final class Game(context: KeyValueEntityContext) extends KeyValueEntity[GameState]:
  def emptyState: GameState = GameState()

  /** Replaces every provider field and clears `removedFromFeed`: the game is listed again. */
  def upsert(cmd: UpsertGame): Effect[GameState] =
    val next = GameState(cmd.provider, cmd.code, Some(cmd.fields), removedFromFeed = false, Some(cmd.at))
    if currentState.copy(lastSeenAt = next.lastSeenAt) == next then effects.reply(currentState)
    else effects.updateState(next).thenReplyState

  /** Marks the game removed from the provider's feed; its enabled flag is left as the provider last sent it (FR-021). */
  def markRemoved: Effect[GameState] =
    if !currentState.exists || currentState.removedFromFeed then effects.reply(currentState)
    else effects.updateState(currentState.copy(removedFromFeed = true)).thenReplyState

  def get: ReadOnlyEffect[GameState] = effects.reply(currentState)

object Game extends KeyValueEntity.Companion[Game, GameState](
      componentId = ComponentId("game"),
      stateSerializer = GameCodecs.given_Serializer_GameState
    ):
  def create(context: KeyValueEntityContext) = new Game(context)
  val upsert      = command("upsert")(_.upsert)
  val markRemoved = command("mark-removed")(_.markRemoved)
  val get         = query("get")(_.get)

  def entityId(provider: String, code: String): String = s"$provider/$code"

// ── The brand's curation (FR-022) ──────────────────────────────────────────────────────────────

final case class CatalogueConfiguration(
    disabledDevelopers: Set[String] = Set.empty,
    disabledCategories: Set[String] = Set.empty,
    gameOverrides: Map[String, Boolean] = Map.empty
)

/** A write, with the games it would turn on that are marked removed, which refuses it. */
final case class SetCatalogueConfiguration(configuration: CatalogueConfiguration, removedGames: Set[String])

object BrandCatalogueCodecs:
  given Serializer[CatalogueConfiguration]    = Codecs.serializer[CatalogueConfiguration]("catalogue-configuration")
  given Serializer[SetCatalogueConfiguration] = Codecs.serializer[SetCatalogueConfiguration]("set-catalogue-configuration")

import BrandCatalogueCodecs.given

/** The brand's disabled developers and categories and per-game overrides, keyed `<provider>/<code>`; one per brand. */
final class BrandCatalogueConfiguration(context: KeyValueEntityContext) extends KeyValueEntity[CatalogueConfiguration]:
  def emptyState: CatalogueConfiguration = CatalogueConfiguration()

  def set(cmd: SetCatalogueConfiguration): Effect[CatalogueConfiguration] =
    val enablingRemoved = cmd.configuration.gameOverrides.collect { case (g, true) if cmd.removedGames(g) => g }.toVector.sorted
    if enablingRemoved.nonEmpty then effects.error(s"game_removed: ${enablingRemoved.mkString(", ")}", ErrorCode.BadRequest)
    else effects.updateState(cmd.configuration).thenReplyState

  def get: ReadOnlyEffect[CatalogueConfiguration] = effects.reply(currentState)

object BrandCatalogueConfiguration extends KeyValueEntity.Companion[BrandCatalogueConfiguration, CatalogueConfiguration](
      componentId = ComponentId("brand-catalogue-configuration"),
      stateSerializer = BrandCatalogueCodecs.given_Serializer_CatalogueConfiguration
    ):
  def create(context: KeyValueEntityContext) = new BrandCatalogueConfiguration(context)
  val set = command("set")(_.set)
  val get = query("get")(_.get)

// ── Enablement: the decision, computed at read time so a change applies at once (FR-022, FR-024) ──

final case class EnablementLayers(
    providerEnabled: Boolean,
    removedFromFeed: Boolean,
    individualOverride: Option[Boolean],
    categoryDisabled: Boolean,
    developerDisabled: Boolean,
    effectiveEnabled: Boolean
)

object Enablement:
  /** Not removed, then category, then developer, then the brand's override, else the provider's flag. */
  def decide(game: GameState, configuration: CatalogueConfiguration): EnablementLayers =
    val f                 = game.fields.get
    // Keyed `<provider>/<code>`; a bare code, as the original's backoffice writes it, is honoured too.
    val overridden        = configuration.gameOverrides.get(Game.entityId(game.provider, game.code)).orElse(configuration.gameOverrides.get(game.code))
    val categoryDisabled  = configuration.disabledCategories(f.category)
    val developerDisabled = configuration.disabledDevelopers(f.developerId)
    val effective = !game.removedFromFeed && !categoryDisabled && !developerDisabled && overridden.getOrElse(f.enabled)
    EnablementLayers(f.enabled, game.removedFromFeed, overridden, categoryDisabled, developerDisabled, effective)

  def listed(game: GameState, configuration: CatalogueConfiguration): Boolean =
    game.exists && decide(game, configuration).effectiveEnabled

// ── The catalogue view (FR-023) ────────────────────────────────────────────────────────────────

/**
 * A game as the catalogue's queries read it: every field a filter names at the top level of the row's
 * JSON, where ankka's `jsonText` and `jsonContains` reach, and the whole game for the answer.
 */
final case class CatalogueRow(
    provider: String,
    code: String,
    codeLower: String,
    nameLower: String,
    category: String,
    developerId: String,
    developerSlug: String,
    themes: Vector[String],
    features: Vector[String],
    bonusTypes: Vector[String],
    tags: Vector[String],
    restrictedTerritories: Vector[String],
    prohibitedTerritories: Vector[String],
    deprecationDate: String,
    game: GameState
)

final class CatalogueView extends View[GameState, CatalogueRow]:
  def onChange(game: GameState): Effect =
    game.fields match
      case None => effects.ignore()
      case Some(f) =>
        effects.updateRow(CatalogueRow(game.provider, game.code, game.code.toLowerCase, f.name.toLowerCase, f.category, f.developerId,
          Catalogue.slug(f.developerName.nonEmpty match { case true => f.developerName; case false => f.developerId }), f.themes,
          f.features, f.bonusTypes, f.tags, f.restrictedTerritories.map(_.toUpperCase), f.prohibitedTerritories.map(_.toUpperCase),
          f.deprecationDate.getOrElse(""), game))

object Catalogue extends View.Companion[CatalogueView, GameState, CatalogueRow](
      componentId = ComponentId("catalogue"),
      source = ChangeSource.stateOf(Game),
      rowSerializer = Codecs.serializer[CatalogueRow]("catalogue-row")
    ):
  def create(ctx: ViewComponentContext) = new CatalogueView

  def slug(s: String): String = s.trim.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")

// ── Popular games: rounds played at the brand, all time (FR-023, Clarifications 2026-10-09) ─────

final case class GameRoundsState(provider: String = "", code: String = "", rounds: Long = 0)

final case class CountRound(provider: String, code: String)

object GameRoundsCodecs:
  given Serializer[GameRoundsState] = Codecs.serializer[GameRoundsState]("game-rounds-state")
  given Serializer[CountRound]      = Codecs.serializer[CountRound]("count-round")

import GameRoundsCodecs.given

final class GameRounds(context: KeyValueEntityContext) extends KeyValueEntity[GameRoundsState]:
  def emptyState: GameRoundsState = GameRoundsState()
  def increment(cmd: CountRound): Effect[Long] =
    effects.updateState(GameRoundsState(cmd.provider, cmd.code, currentState.rounds + 1)).thenReply(_.rounds)
  def get: ReadOnlyEffect[GameRoundsState] = effects.reply(currentState)

object GameRounds extends KeyValueEntity.Companion[GameRounds, GameRoundsState](
      componentId = ComponentId("game-rounds"),
      stateSerializer = GameRoundsCodecs.given_Serializer_GameRoundsState
    ):
  def create(context: KeyValueEntityContext) = new GameRounds(context)
  val increment = command("increment")(_.increment)
  val get       = query("get")(_.get)

final class GamePopularityView extends View[GameRoundsState, GameRoundsState]:
  def onChange(state: GameRoundsState): Effect = effects.updateRow(state)

object GamePopularity extends View.Companion[GamePopularityView, GameRoundsState, GameRoundsState](
      componentId = ComponentId("game-popularity"),
      source = ChangeSource.stateOf(GameRounds),
      rowSerializer = Codecs.serializer[GameRoundsState]("game-popularity-row")
    ):
  def create(ctx: ViewComponentContext) = new GamePopularityView

/**
 * Counts a round for its game when the round opens. At least once: a redelivery after a crash may count
 * one round twice, which a ranking tolerates (research R10).
 */
final class RoundCounter(client: ComponentClient) extends Consumer[RoundEvent, Nothing]:
  def onMessage(event: RoundEvent): Effect = event match
    case RoundEvent.RoundOpened(o) if o.gameCode.exists(_.nonEmpty) =>
      client.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(Game.entityId(o.provider, o.gameCode.get)))
        .call(GameRounds.increment).invoke(CountRound(o.provider, o.gameCode.get)): Unit
      effects.done()
    case _ => effects.ignore()

object RoundCounter extends Consumer.Companion[RoundCounter, RoundEvent, Nothing](
      componentId = ComponentId("round-counter"),
      source = ChangeSource.eventsOf(Round)
    ):
  def create(ctx: ConsumerContext) = new RoundCounter(ctx.componentClient)

// ── RefreshStatus: each provider's last refresh (FR-021) ───────────────────────────────────────

final case class RefreshState(
    provider: String = "",
    status: String = "idle",
    startedAt: Option[Instant] = None,
    finishedAt: Option[Instant] = None,
    count: Option[Int] = None,
    removed: Option[Int] = None,
    error: Option[String] = None,
    attempt: Int = 0
)

object RefreshCodecs:
  given Serializer[RefreshState] = Codecs.serializer[RefreshState]("refresh-state")

import RefreshCodecs.given

final class RefreshStatus(context: KeyValueEntityContext) extends KeyValueEntity[RefreshState]:
  def emptyState: RefreshState              = RefreshState(context.entityId)
  def set(s: RefreshState): Effect[RefreshState] = effects.updateState(s).thenReplyState
  def get: ReadOnlyEffect[RefreshState]     = effects.reply(currentState)

object RefreshStatus extends KeyValueEntity.Companion[RefreshStatus, RefreshState](
      componentId = ComponentId("refresh-status"),
      stateSerializer = RefreshCodecs.given_Serializer_RefreshState
    ):
  def create(context: KeyValueEntityContext) = new RefreshStatus(context)
  val set = command("set")(_.set)
  val get = query("get")(_.get)
