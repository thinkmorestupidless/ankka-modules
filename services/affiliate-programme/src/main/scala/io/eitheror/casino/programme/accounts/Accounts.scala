package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*

/** Every account as its entity holds it, the identity still sealed (data-model.md *Views*): the network walk and the listings read it. */
final class AccountsView extends View[Affiliate.Event, AffiliateState]:
  def onChange(event: Affiliate.Event): Effect =
    val row  = rowState.getOrElse(AffiliateState())
    val next = Affiliate.next(updateContext.subject, row, event)
    if rowState.isDefined && next == row then effects.ignore() else effects.updateRow(next)

object Accounts extends View.Companion[AccountsView, Affiliate.Event, AffiliateState](
      componentId = ComponentId("accounts"),
      source = ChangeSource.eventsOf(Affiliate),
      rowSerializer = AffiliateCodecs.state
    ):
  val byId      = query("by-id")(s"SELECT payload FROM $table WHERE row_key = :id")
  val childrenOf = query("children-of")(s"SELECT payload FROM $table WHERE payload::jsonb->>'parent' = :parent ORDER BY row_key")
  /** Every account, for the network walk (FR-028); the walk reads the parent of each, so the rows are small. */
  val all = query("all")(s"SELECT payload FROM $table ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new AccountsView
