package io.eitheror.casino.programme.ledger

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

/**
 * The last event of one player (`PlayerAttribution`'s id, `<brand>/<pseudonym>`) whose ledger writes are
 * done: `ToLedger` skips an event at or below it, so a redelivery however old (an offset reset, a rebuild)
 * writes nothing. One number a player, where a mark per account and player would grow with the account.
 */
final case class LedgerMarkState(seq: Long = 0L)

object LedgerMarkCodecs:
  given Serializer[LedgerMarkState] = ProgrammeCodecs.serializer[LedgerMarkState]("ledger-mark")

import com.thinkmorestupidless.ankka.core.Serializers.given

final class LedgerMark(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[LedgerMarkState]:
  def emptyState: LedgerMarkState = LedgerMarkState()

  /** Only forwards: a mark never moves back. */
  def mark(seq: Long): Effect[LedgerMarkState] =
    if seq <= currentState.seq then effects.reply(currentState) else effects.updateState(LedgerMarkState(seq)).thenReplyState

  def get: ReadOnlyEffect[LedgerMarkState] = effects.reply(currentState)

object LedgerMark extends KeyValueEntity.Companion[LedgerMark, LedgerMarkState](
      componentId = ComponentId("ledger-mark"),
      stateSerializer = LedgerMarkCodecs.given_Serializer_LedgerMarkState
    ):
  def create(context: KeyValueEntityContext) = new LedgerMark(context)

  val mark = command("mark")(_.mark)
  val get  = query("get")(_.get)
