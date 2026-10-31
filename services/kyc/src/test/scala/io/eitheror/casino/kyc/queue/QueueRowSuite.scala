package io.eitheror.casino.kyc.queue

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.kyc.KycCodecs
import io.eitheror.casino.kyc.record.*
import io.eitheror.casino.kyc.record.KycEvent.*

import java.time.Instant

/** The queue's row (data-model.md *KycQueue*): its columns follow the record's own rule, and it keeps no personal value. */
class QueueRowSuite extends munit.FunSuite:
  private val t0 = Instant.parse("2026-10-30T12:00:00Z")
  private def at(m: Int) = t0.plusSeconds(60L * m)

  private def doc(id: String, m: Int, by: Uploader = Uploader.Player) =
    Document(id, Category.IdentityFront, s"goldvibe/p-1/$id", "image/png", 10, at(m), by)

  private def fold(events: KycEvent*): QueueRow = events.foldLeft(QueueRow("p-1"))(QueueRow.next)

  test("documents awaiting review are counted, and the oldest is the first uploaded"):
    val row = fold(DocumentRecorded("p-1", doc("d-1", 1), at(1)), DocumentRecorded("p-1", doc("d-2", 2), at(2)))
    assertEquals((row.awaiting, row.oldestAwaitingAt, row.updatedAt), (2, Some(at(1)), Some(at(2))))
    val reviewed = QueueRow.next(row, DocumentReviewed("d-1", Category.IdentityFront, Review(Outcome.Approved, None, "op-1", at(3)), at(3)))
    assertEquals((reviewed.awaiting, reviewed.oldestAwaitingAt), (1, Some(at(2))))

  test("a flag is listed while open, and leaves the row when dismissed or closed by verification"):
    val flagged = fold(Flagged("p-1", FlagReason.Threshold, at(1)), Flagged("p-1", FlagReason.SourceOfFunds, at(2)))
    assertEquals((flagged.flags, flagged.flaggedAt), (Vector("threshold", "source_of_funds"), Some(at(1))))
    val dismissed = QueueRow.next(flagged, FlagDismissed(FlagReason.SourceOfFunds, "op-1", Personal("seen the payslip"), at(3)))
    assertEquals(dismissed.flags, Vector("threshold"))
    val verified = QueueRow.next(dismissed, StatusChanged(Status.Unverified, Status.Verified, Actor.System, "documents_approved", None, at(4)))
    assertEquals((verified.flags, verified.flaggedAt, verified.status), (Vector.empty, None, "verified"))

  test("deposits are totalled per currency, in minor units"):
    val row = fold(DepositCounted("p-1", "EUR", 5000, 1, at(1)), DepositCounted("p-1", "EUR", 7000, 2, at(2)), DepositCounted("p-1", "GBP", 100, 1, at(3)))
    assertEquals(row.depositsByCurrency, Map("EUR" -> 12000L, "GBP" -> 100L))

  test("the row keeps no personal value: no upload reason, no dismissal note, no date of birth"):
    val support = Uploader(Some("op-1"), Some(Personal("asked on the phone")))
    val row = fold(
      DocumentRecorded("p-1", doc("d-1", 1, support), at(1)),
      Flagged("p-1", FlagReason.Threshold, at(2)),
      FlagDismissed(FlagReason.Threshold, "op-1", Personal("known customer"), at(3)),
      Flagged("p-1", FlagReason.Threshold, at(4))
    )
    val json = writeToString(row)(using KycCodecs.stored[QueueRow])
    assert(!json.contains("asked on the phone") && !json.contains("known customer"), json)
    assertEquals(row.pending.map(_.by), Vector(Uploader(Some("op-1"), None)))
    assertEquals(row.flags, Vector("threshold"))

  test("a row's mode, readiness and source of funds follow the record"):
    val row = fold(ModeChanged(Mode.Manual, Mode.Automatic, "op-1", at(1)), ReadySet(at(2)), SofCleared("op-1", at(3)))
    assertEquals((row.mode, row.readyAt, row.sof), ("manual", Some(at(2)), "cleared"))
    assertEquals(QueueRow.next(row, ReadyCleared(at(4))).readyAt, None)
