package io.eitheror.casino.payouts.facts

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.MinorUnits
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.limits.Pair
import io.eitheror.casino.payouts.limits.LimitsEvent.LimitsSet
import io.eitheror.casino.payouts.player.{Destination, PlayerEvent}
import io.eitheror.casino.payouts.request.*
import io.eitheror.casino.payouts.request.RequestEvent.*

import java.time.Instant

/** contracts/facts.md: each event to its family or none, the actor on each audit record, and no personal value on either. */
class PublishersSuite extends munit.FunSuite:
  private val at       = Instant.parse("2026-10-15T12:00:00Z")
  private val terminal = TerminalRef("t-1", "sb-1", "Bank", "unityfinance", "EUBANKING", true, hasStatus = true)
  private val payee = Payee(destinationId = Some("d-1"), holder = Some(Holder(Personal("Ana"), Personal("Silva"))),
    iban = Some(Personal("DE89370400440532013000")))
  private val opened = Opened("r-1", "p-1", 5000L, 4800L, "EUR", terminal, payee, Forfeit(true, 200L, 0L), "verified", at)
  private val state = RequestState(opened = true, requestId = "r-1", playerId = "p-1", amount = 5000L, paidAmount = 4800L,
    currency = "EUR", terminal = Some(terminal), payee = payee, providerReference = Some("uf-1"))
  private val secret = Personal("an operator's own words")

  private val events: Vector[RequestEvent] = Vector(
    opened,
    Cancelled(Actor.Player, Some("player_cancelled"), at),
    Approved("op-1", at),
    Rejected("op-1", Some(secret), at),
    AwaitingManualPayout("no_automated_payout", at),
    PayoutInitiated("initiated", Some("uf-1"), at),
    OutcomeRecorded(OutcomeRecord(Source.Outcome, "completed", None, Some("uf-1"), Some(secret), at)),
    OutcomeRecorded(OutcomeRecord(Source.Operator, "completed", None, None, Some(secret), at)),
    MarkedNeedsReview("in_doubt", at),
    Completed(Source.Operator, None, Some(secret), at, Some("op-2")),
    Failed(Source.Gateway, "provider_rejected", None, at),
    Reversed("chargeback", Some("uf-2"), at),
    ReferenceMismatched("uf-9", at),
    Erased(at)
  )

  private def family(e: RequestEvent) = Facts.withdrawal("r-1", 7, e, state).map(_.fact.getClass.getSimpleName)

  test("each event to its family or to none"):
    assertEquals(events.map(family), Vector(Some("WithdrawalRequested"), Some("WithdrawalCancelled"), Some("WithdrawalApproved"),
      Some("WithdrawalCancelled"), None, None, None, None, None, Some("WithdrawalCompleted"), Some("WithdrawalFailed"),
      Some("WithdrawalReversed"), None, None))

  test("a rejection is withdrawal_cancelled by an operator, its reason rejected"):
    val Some(Facts.Published(c: f.WithdrawalCancelled, id)) = Facts.withdrawal("r-1", 4, Rejected("op-1", Some(secret), at), state): @unchecked
    assertEquals((c.actor, c.reason, c.payoutId, c.terminal, c.gateway, id), ("operator", Some("rejected"), "r-1", Some("t-1"),
      Some("unityfinance"), "withdrawal-r-1-4"))

  test("approval emits withdrawal_approved alone, naming the operator"):
    val out = Facts.withdrawal("r-1", 3, Approved("op-1", at), state).toVector
    assertEquals(out.map(_.fact), Vector(f.WithdrawalApproved("p-1", "r-1", MinorUnits.unsafe(4800),
      state.currencyOf, "op-1", at, Some("t-1"), Some("unityfinance"))))

  test("amountCents is the paid amount; the reference is the gateway's"):
    val requested = Facts.withdrawal("r-1", 1, opened, RequestState()).get.fact.asInstanceOf[f.WithdrawalRequested]
    assertEquals((requested.amountCents.value, requested.playerId, requested.gateway), (4800L, "p-1", Some("unityfinance")))
    val completed = Facts.withdrawal("r-1", 9, Completed(Source.Outcome, None, None, at), state).get.fact.asInstanceOf[f.WithdrawalCompleted]
    assertEquals((completed.amountCents.value, completed.providerReference), (4800L, Some("uf-1")))

  test("each audit record names its actor"):
    def who(e: RequestEvent) = Facts.requestAudit("goldvibe", "r-1", 1, e, state).map(r => (r.fact.actorKind, r.fact.actorId))
    assertEquals(events.map(who), Vector(
      Some(("player", "p-1")), Some(("player", "p-1")), Some(("operator", "op-1")), Some(("operator", "op-1")),
      Some(("system", "payouts")), Some(("service", "psp-gateway")), Some(("service", "psp-gateway")), None,
      Some(("system", "payouts")), Some(("operator", "op-2")), Some(("service", "psp-gateway")), Some(("service", "psp-gateway")),
      Some(("service", "psp-gateway")), None))
    val poll = OutcomeRecorded(OutcomeRecord(Source.Poll, "pending", None, None, None, at))
    assertEquals(who(poll), Some(("system", "payouts")))

  test("never a personal value or free text on a fact or an audit record"):
    val destination = Destination("d-1", Personal("Ana"), Personal("Silva"), Personal("DE89370400440532013000"), Personal("main"),
      true, true, at)
    val written =
      events.flatMap(e => Facts.withdrawal("r-1", 1, e, state).map(p => writeToString(p.fact: f.CasinoWithdrawalsFact))) ++
        events.flatMap(e => Facts.requestAudit("goldvibe", "r-1", 1, e, state).map(p => writeToString(p.fact: f.PlatformAuditFact))) ++
        Vector(PlayerEvent.DestinationSaved(destination), PlayerEvent.DefaultSet("d-1", at), PlayerEvent.DestinationDeleted("d-1", at))
          .flatMap(e => Facts.playerAudit("goldvibe", "p-1", 1, e).map(p => writeToString(p.fact: f.PlatformAuditFact)))
    assert(written.size > 15, written.size.toString)
    for text <- written; value <- Vector("Ana", "Silva", "DE89370400440532013000", "words", "main\"") do
      assert(!text.contains(value), s"$value in $text")

  test("a limits change names each limit before and after"):
    val set = LimitsSet(Map("EUR" -> Pair(Some(100000), None)), Map("EUR" -> Pair(Some(50000), Some(200000)), "GBP" -> Pair(Some(1000))),
      "op-1", at)
    val r = Facts.limitsAudit("goldvibe", 2, set).fact
    assertEquals((r.actorKind, r.actorId, r.subjectKind, r.subjectId), ("operator", "op-1", "brand", "goldvibe"))
    assertEquals(r.changes, Vector(
      f.AuditChange("limits.EUR.weekly", Some("100000"), Some("50000")), f.AuditChange("limits.EUR.monthly", None, Some("200000")),
      f.AuditChange("limits.GBP.weekly", None, Some("1000"))))
