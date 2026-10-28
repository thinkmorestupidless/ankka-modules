package io.eitheror.casino.gameprovider.tickets

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
import io.eitheror.casino.gameprovider.tickets.TicketCodecs.given
import com.thinkmorestupidless.ankka.core.Serializers.given

import java.time.Instant

/** FR-010 and research R5, through the entity alone. */
class TicketSuite extends munit.FunSuite with LogCapturing:

  private val at = Instant.parse("2026-10-09T10:00:00Z")

  private def issued(token: String = "tok-1") =
    val kit = KeyValueEntityTestKit.of(Ticket, token)
    kit.call(Ticket.issue)(IssueTicket("st8", "p-1", "book-of-ra", Some("slots"), "gs-1", "EUR", Some("grant-1"), funMode = false, at))
    kit

  test("an issued ticket checked for its own game is accepted and becomes the session ticket") {
    val kit    = issued()
    val result = kit.call(Ticket.check)(CheckTicket(Some("book-of-ra"), at.plusSeconds(5)))
    assert(result.replyValue.accepted)
    assertEquals(result.replyValue.ticket.playerId, "p-1")
    assertEquals(result.replyValue.ticket.freeRoundsGrant, Some("grant-1"))
    assert(kit.currentState.isChecked)
    assertEquals(kit.currentState.checkedAt, Some(at.plusSeconds(5)))
  }

  test("a ticket already checked is refused when checked again") {
    val kit = issued()
    kit.call(Ticket.check)(CheckTicket(Some("book-of-ra"), at))
    val again = kit.call(Ticket.check)(CheckTicket(Some("book-of-ra"), at))
    assert(!again.replyValue.accepted)
    assert(!again.changed)
  }

  test("a ticket checked for another game is refused and stays unchecked") {
    val kit = issued()
    assert(!kit.call(Ticket.check)(CheckTicket(Some("fake-slot-002"), at)).replyValue.accepted)
    assertEquals(kit.currentState.status, TicketState.Issued)
  }

  test("a check naming no game matches the ticket's game") {
    assert(issued().call(Ticket.check)(CheckTicket(None, at)).replyValue.accepted)
  }

  test("an unknown token is refused") {
    val kit = KeyValueEntityTestKit.of(Ticket, "never-issued")
    assert(!kit.call(Ticket.check)(CheckTicket(None, at)).replyValue.accepted)
  }

  test("a ticket issued long ago is still checked: neither ticket expires") {
    val kit = issued()
    assert(kit.call(Ticket.check)(CheckTicket(None, at.plusSeconds(400L * 24 * 3600))).replyValue.accepted)
    assertEquals(kit.call(Ticket.get).replyValue.status, TicketState.Checked, "and stays the session ticket")
  }

  test("a token is issued once") {
    val kit = issued()
    assert(kit.call(Ticket.issue)(IssueTicket("st8", "p-2", "g", None, "s", "EUR", None, false, at)).isError)
    assertEquals(kit.currentState.playerId, "p-1")
  }

  test("a launch that failed discards its ticket, which is then never accepted") {
    val kit = issued()
    assert(kit.call(Ticket.discard).replyValue)
    assert(kit.isDeleted)
    assert(!kit.call(Ticket.check)(CheckTicket(None, at)).replyValue.accepted)
  }

  test("the game session is attached while the ticket is unchecked, and not after") {
    val kit = issued()
    kit.call(Ticket.attachSession)("gs-9")
    assertEquals(kit.currentState.sessionId, "gs-9")
    kit.call(Ticket.check)(CheckTicket(None, at))
    assert(!kit.call(Ticket.attachSession)("gs-10").changed)
  }
