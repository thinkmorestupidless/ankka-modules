package io.eitheror.casino.programme.intake

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.Instant

/**
 * One fact received (data-model.md *FactInbox*), id `<brand>/<factId>`: claimed once on any channel
 * before anything is written (FR-009), then what became of it. The fact's body is not kept: its country
 * and click id are a player's, which FR-038 forgets on `player-closed`, and the counts need only its kind
 * and currency.
 */
final case class InboxState(
    brand: String = "",
    factId: String = "",
    kind: String = "",
    channel: String = "",
    currency: Option[String] = None,
    occurredAt: Option[Instant] = None,
    receivedAt: Option[Instant] = None,
    outcome: Option[String] = None,
    duplicates: Int = 0
):
  def claimed: Boolean = factId.nonEmpty

final case class ClaimFact(brand: String, factId: String, kind: String, channel: String, currency: Option[String], occurredAt: Instant,
    at: Instant)

/** `new` the first time, `duplicate` after, on whichever channel. */
final case class Claimed(outcome: String, state: InboxState):
  def isNew: Boolean = outcome == FactInbox.New

object FactInboxCodecs:
  given Serializer[InboxState] = ProgrammeCodecs.serializer[InboxState]("inbox")
  given Serializer[ClaimFact]  = ProgrammeCodecs.serializer[ClaimFact]("claim-fact")
  given Serializer[Claimed]    = ProgrammeCodecs.serializer[Claimed]("claimed")

import FactInboxCodecs.given
import com.thinkmorestupidless.ankka.core.Serializers.string

final class FactInbox(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[InboxState]:
  def emptyState: InboxState = InboxState()

  def claim(cmd: ClaimFact): Effect[Claimed] =
    if currentState.claimed then
      effects.updateState(currentState.copy(duplicates = currentState.duplicates + 1)).thenReply(s => Claimed(FactInbox.Duplicate, s))
    else
      effects.updateState(InboxState(cmd.brand, cmd.factId, cmd.kind, cmd.channel, cmd.currency, Some(cmd.occurredAt), Some(cmd.at)))
        .thenReply(s => Claimed(FactInbox.New, s))

  /** What became of the fact: `bound`, `recorded`, `unattributed:<reason>`, `ignored:<reason>`, `mismatched`, …; the last recorded stands, and the same one again changes nothing. */
  def outcome(o: String): Effect[InboxState] =
    if currentState.outcome.contains(o) then effects.reply(currentState)
    else effects.updateState(currentState.copy(outcome = Some(o))).thenReplyState

  def get: ReadOnlyEffect[InboxState] = effects.reply(currentState)

object FactInbox extends KeyValueEntity.Companion[FactInbox, InboxState](
      componentId = ComponentId("fact-inbox"),
      stateSerializer = FactInboxCodecs.given_Serializer_InboxState
    ):
  val New       = "new"
  val Duplicate = "duplicate"

  def entityId(brand: String, factId: String): EntityId = EntityId(s"$brand/$factId")

  private val CurrencyField = """"currency"\s*:\s*"([A-Z]{3})"""".r

  /** The fact's currency, when it has an amount, read from its body as it arrived. */
  def currencyOf(body: String): Option[String] = CurrencyField.findFirstMatchIn(body).map(_.group(1))

  def create(context: KeyValueEntityContext) = new FactInbox(context)

  val claim   = command("claim")(_.claim)
  val outcome = command("outcome")(_.outcome)
  val get     = query("get")(_.get)
