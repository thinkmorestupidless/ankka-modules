package io.eitheror.casino.programme.players

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.intake.Intake

import java.time.Instant

/** One fact a player's record applied, with what it came to (research.md *Found during implementation*). */
final case class PlayerFactRow(
    brand: String = "",
    pseudonym: String = "",
    affiliate: String = "",
    factId: String = "",
    kind: String = "",
    outcome: String = "",
    currency: Option[String] = None,
    amount: Long = 0,
    period: String = "",
    campaign: Option[String] = None,
    occurredAt: Instant = Instant.EPOCH,
    /** On a registration only, until the player is closed (FR-038). */
    country: Option[String] = None,
    /** On a deposit: which of the player's deposits it is, in every currency (the original's 2nd and 3rd deposit counts). */
    ordinal: Int = 0
)

object PlayerFactRow:
  given Serializer[PlayerFactRow] = ProgrammeCodecs.serializer[PlayerFactRow]("player-fact-row")

/**
 * A row per `Applied` event at a bound player: the counts a revenue read gives fold from these. A
 * player's `player-closed` forgets the country its registration row holds (FR-038).
 */
final class PlayerFactsView extends KeyedView[PlayerFactRow]:
  def onPlayer(event: PlayerAttribution.Event, change: Change): Effect =
    (event.binding, event.fact.period) match
      case (Some(_), _) if event.outcome == Outcome.Closed =>
        val f    = event.fact
        val held = change.rows.ask(PlayerFacts.ofPlayer, "brand" -> f.brand, "pseudonym" -> f.pseudonym).filter(_.country.isDefined)
        if held.isEmpty then effects.ignore()
        else effects.updateRows(held.map(r => PlayerFacts.key(r.brand, r.factId) -> r.copy(country = None)))
      case (Some(b), Some(period)) =>
        val f       = event.fact
        val ordinal =
          if f.kind != PlayerFact.Deposit then 0
          else change.rows.ask(PlayerFacts.ofPlayer, "brand" -> f.brand, "pseudonym" -> f.pseudonym)
            .count(r => r.kind == PlayerFact.Deposit && r.factId != f.factId) + 1
        effects.updateRow(PlayerFacts.key(f.brand, f.factId),
          PlayerFactRow(f.brand, f.pseudonym, b.affiliate, f.factId, f.kind, Intake.describe(event.outcome), f.currency, f.amount,
            period, b.campaign, f.occurredAt, f.country.filter(_ => f.kind == PlayerFact.Registration), ordinal))
      case _ => effects.ignore()

object PlayerFacts extends KeyedView.Companion[PlayerFactsView, PlayerFactRow](
      componentId = ComponentId("player-facts"),
      rowSerializer = PlayerFactRow.given_Serializer_PlayerFactRow
    ):
  def key(brand: String, factId: String): String = s"$brand|$factId"

  val players = source(ChangeSource.eventsOf(PlayerAttribution))(_.onPlayer)

  /** One affiliate's player facts at one brand for a period range (`yyyy-MM`, inclusive). */
  val ofAffiliate = query("of-affiliate")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate AND payload::jsonb->>'brand' = :brand " +
      "AND payload::jsonb->>'period' >= :from AND payload::jsonb->>'period' <= :to ORDER BY row_key"
  )

  val ofPlayer = query("of-player")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand AND payload::jsonb->>'pseudonym' = :pseudonym ORDER BY row_key"
  )

  /** A brand's player facts for a period range. */
  val ofBrand = query("of-brand")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand " +
      "AND payload::jsonb->>'period' >= :from AND payload::jsonb->>'period' <= :to ORDER BY row_key"
  )

  def create(ctx: ViewComponentContext) = new PlayerFactsView
