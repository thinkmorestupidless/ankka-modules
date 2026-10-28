package io.eitheror.casino.attribution.money

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs

/**
 * Where a wallet's real balance stood after the last fact read (data-model.md *WalletCursor*; research
 * R10), id `<player>/<currency>`. A fact's real movement is the difference from here; the first fact of a
 * wallet starts from 0, as the wallet's balance does.
 */
final case class CursorState(lastRealAfter: Long = 0, recent: Vector[(String, Long)] = Vector.empty)

final case class Advance(dedupeId: String, realAfter: Long)

sealed trait Advanced
object Advanced:
  final case class Delta(amount: Long) extends Advanced
  /** A dedupe id read before, with the delta it was given then, so a redelivery can finish what it began. */
  final case class Seen(amount: Long) extends Advanced

object WalletCursorCodecs:
  given Serializer[CursorState] = AttributionCodecs.serializer[CursorState]("wallet-cursor")
  given Serializer[Advance]     = AttributionCodecs.serializer[Advance]("advance")
  given Serializer[Advanced]    = AttributionCodecs.serializer[Advanced]("advanced")

import WalletCursorCodecs.given

final class WalletCursor(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[CursorState]:
  def emptyState: CursorState = CursorState()

  def advance(cmd: Advance): Effect[Advanced] =
    val s = currentState
    s.recent.collectFirst { case (id, delta) if id == cmd.dedupeId => delta } match
      case Some(delta) => effects.reply(Advanced.Seen(delta))
      case None =>
        val delta = cmd.realAfter - s.lastRealAfter
        effects
          .updateState(CursorState(cmd.realAfter, (s.recent :+ (cmd.dedupeId -> delta)).takeRight(WalletCursor.MaxRecent)))
          .thenReply(_ => Advanced.Delta(delta))

  def get: ReadOnlyEffect[CursorState] = effects.reply(currentState)

object WalletCursor extends KeyValueEntity.Companion[WalletCursor, CursorState](
      componentId = ComponentId("wallet-cursor"),
      stateSerializer = WalletCursorCodecs.given_Serializer_CursorState
    ):
  val MaxRecent = 200

  def id(player: String, currency: String): String = s"$player/$currency"

  def create(context: KeyValueEntityContext) = new WalletCursor(context)

  val advance = command("advance")(_.advance)
  val get     = query("get")(_.get)
