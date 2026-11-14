package io.eitheror.casino.support.limits

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.support.{Stored, SupportCodecs}

import java.time.{Instant, LocalDate, ZoneOffset}

/**
 * A brand's model tokens on one UTC day (data-model.md `Budget`, id = `<slug>/<yyyy-mm-dd>`; FR-024, R10). After each
 * turn the chat route adds the session's increase; once the day's tokens reach the brand's limit, every turn is
 * answered with the unavailable message until the next day.
 */
final class Budget(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[BudgetState]:
  def emptyState: BudgetState = BudgetState()

  def add(a: AddTokens): Effect[BudgetState] =
    val next = currentState.add(a.session, a.from, a.upTo)
    if next == currentState then effects.reply(currentState) else effects.updateState(next).thenReply(identity)

  def get: ReadOnlyEffect[BudgetState] = effects.reply(currentState)

object Budget
    extends KeyValueEntity.Companion[Budget, BudgetState](
      componentId = ComponentId("support-budget"),
      stateSerializer = Stored.budget
    ):
  given Serializer[AddTokens]   = SupportCodecs.serializer[AddTokens]("budget-add")
  given Serializer[BudgetState] = SupportCodecs.serializer[BudgetState]("budget-reply")

  def day(at: Instant): LocalDate = at.atZone(ZoneOffset.UTC).toLocalDate

  def id(slug: String, at: Instant): EntityId = EntityId(s"$slug/${day(at)}")

  def create(context: KeyValueEntityContext) = new Budget(context)

  val add = command("add")(_.add)
  val get = query("get")(_.get)
