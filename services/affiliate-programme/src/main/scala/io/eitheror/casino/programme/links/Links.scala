package io.eitheror.casino.programme.links

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.accounts.Affiliate

import java.time.Instant

/** A row per link: what the link routes list, and the imported links the legacy tag document maps (FR-026). */
final class LinksView extends View[LinkState, LinkState]:
  def onChange(s: LinkState): Effect = effects.updateRow(s)

object Links extends View.Companion[LinksView, LinkState, LinkState](
      componentId = ComponentId("links"),
      source = ChangeSource.stateOf(Link),
      rowSerializer = LinkCodecs.given_Serializer_LinkState
    ):
  val ofAffiliate = query("of-affiliate")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate AND payload::jsonb->>'brand' = :brand ORDER BY row_key")
  val ofBrand = query("of-brand")(s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand ORDER BY row_key")
  /** The links imported with the original's `btag` UUID (data-model.md *LegacyTags*). */
  val legacy = query("legacy")(s"SELECT payload FROM $table WHERE payload::jsonb->>'legacyBtag' IS NOT NULL ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new LinksView

/** A revoked link, or an affiliate whose account is closed or erased: an entry of the revocation list. */
final case class RevocationRow(kind: String = "", id: String = "", at: Instant = Instant.EPOCH)

object RevocationRow:
  val Link      = "link"
  val Affiliate = "affiliate"
  given Serializer[RevocationRow] = ProgrammeCodecs.serializer[RevocationRow]("revocation-row")

/** The revocation list's entries (FR-023): links revoked, and affiliates closed or erased. */
final class RevocationsView extends KeyedView[RevocationRow]:
  def onLink(s: LinkState, @scala.annotation.unused change: Change): Effect =
    s.revokedAt.fold(effects.ignore())(at => effects.updateRow(s"link|${s.id}", RevocationRow(RevocationRow.Link, s.id, at)))

  def onAffiliate(event: Affiliate.Event, change: Change): Effect = event match
    case Affiliate.Closed(_, at) => effects.updateRow(s"affiliate|${change.subject}", RevocationRow(RevocationRow.Affiliate, change.subject, at))
    case Affiliate.Erased(at)    => effects.updateRow(s"affiliate|${change.subject}", RevocationRow(RevocationRow.Affiliate, change.subject, at))
    case _                       => effects.ignore()

object Revocations extends KeyedView.Companion[RevocationsView, RevocationRow](
      componentId = ComponentId("revocations"),
      rowSerializer = RevocationRow.given_Serializer_RevocationRow
    ):
  val links      = source(ChangeSource.stateOf(Link))(_.onLink)
  val affiliates = source(ChangeSource.eventsOf(Affiliate))(_.onAffiliate)

  val listed = query("listed")(s"SELECT payload FROM $table ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new RevocationsView

/** Every 300 s, the revocation list's `asOf` (data-model.md *Timed actions*): the list a brand refreshes is as of then. */
final class RevocationsStamp(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    context.componentClient.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(SigningKeys.Id)).call(SigningKeys.stamp)
      .invoke(context.clock.instant()): Unit
    effects.done()

object RevocationsStamp extends TimedAction.Companion[RevocationsStamp](ComponentId("revocations-stamp")):
  val TimerName     = "revocations"
  val RefreshSeconds = 300

  def create(context: TimedActionContext) = new RevocationsStamp(context)
  val stamp = handler("stamp")(_.run)
