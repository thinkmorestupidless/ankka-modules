package io.eitheror.casino.programme.intake

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

/** One received fact, without its body: what the operator's counts and the usage report count (FR-042). */
final case class InboxRow(brand: String = "", factId: String = "", kind: String = "", channel: String = "", day: String = "",
    outcome: Option[String] = None, duplicates: Int = 0, receivedAt: Option[java.time.Instant] = None, currency: Option[String] = None)

object InboxRow:
  given Serializer[InboxRow] = ProgrammeCodecs.serializer[InboxRow]("inbox-row")

  private val CurrencyField = """"currency"\s*:\s*"([A-Z]{3})"""".r

  /** The day the fact was received, in UTC, and the instant, for a month in the brand's zone; the fact's currency, when it has an amount. */
  def of(s: InboxState): InboxRow =
    InboxRow(s.brand, s.factId, s.kind, s.channel, s.receivedAt.fold("")(_.toString.take(10)), s.outcome, s.duplicates, s.receivedAt,
      CurrencyField.findFirstMatchIn(s.fact).map(_.group(1)))

final class InboxView extends View[InboxState, InboxRow]:
  def onChange(s: InboxState): Effect = effects.updateRow(InboxRow.of(s))

object Inbox extends View.Companion[InboxView, InboxState, InboxRow](
      componentId = ComponentId("inbox"),
      source = ChangeSource.stateOf(FactInbox),
      rowSerializer = InboxRow.given_Serializer_InboxRow
    ):
  /** A brand's facts received between two days (`yyyy-MM-dd`, inclusive). */
  val byBrandAndDays = query("by-brand-and-days")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand AND payload::jsonb->>'day' >= :from " +
      "AND payload::jsonb->>'day' <= :to ORDER BY row_key"
  )
  /** A brand's unattributed facts, for its manager to see (commission.feature). */
  val unattributed = query("unattributed")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand AND payload::jsonb->>'outcome' LIKE 'unattributed%' ORDER BY row_key"
  )

  def create(ctx: ViewComponentContext) = new InboxView
