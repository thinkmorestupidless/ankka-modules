package io.eitheror.casino.engagement.channel

import com.thinkmorestupidless.ankka.core.effect.Retention
import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit
import io.eitheror.casino.engagement.TestKeyring
import io.eitheror.casino.engagement.channel.AgentTicketCommand.{Issue, Redeem}

import java.time.Instant

/** data-model.md's `AgentTicket`: the original's `AgentStreamTicketStoreSpec`, and why each refusal was made. */
class AgentTicketSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-07T10:00:00Z")

  private def issued(ticket: String = "t-1", brand: String = "brand-a") =
    val kit = KeyValueEntityTestKit.of(AgentTicket, ticket)
    assert(kit.call(AgentTicket.issue)(Issue(brand, "operator-1", at)).replyValue.issued)
    kit

  test("a freshly issued ticket is redeemed for its own brand, naming its operator") {
    TestKeyring.around { _ =>
      val kit = issued()
      val r   = kit.call(AgentTicket.redeem)(Redeem("brand-a", at.plusSeconds(5))).replyValue
      assertEquals(r, Redemption(operator = Some("operator-1")))
      assert(r.ok)
    }
  }

  test("a ticket is answered once only: the second redemption is refused, though within 30 s") {
    TestKeyring.around { _ =>
      val kit = issued()
      assert(kit.call(AgentTicket.redeem)(Redeem("brand-a", at.plusSeconds(1))).replyValue.ok)
      val second = kit.call(AgentTicket.redeem)(Redeem("brand-a", at.plusSeconds(2))).replyValue
      assertEquals(second, Redemption(refused = Some(TicketRefusal.Used)))
      assert(!second.ok)
    }
  }

  test("a ticket older than 30 s is refused as expired, and at 30 s exactly too, as the original") {
    TestKeyring.around { _ =>
      assertEquals(issued().call(AgentTicket.redeem)(Redeem("brand-a", at.plusSeconds(31))).replyValue.refused,
        Some(TicketRefusal.Expired))
      assertEquals(issued().call(AgentTicket.redeem)(Redeem("brand-a", at.plus(AgentTicket.Ttl))).replyValue.refused,
        Some(TicketRefusal.Expired))
      assert(issued().call(AgentTicket.redeem)(Redeem("brand-a", at.plus(AgentTicket.Ttl).minusMillis(1))).replyValue.ok)
    }
  }

  test("another brand's ticket is refused, and is spent: the right brand cannot use it after") {
    TestKeyring.around { _ =>
      val kit = issued()
      assertEquals(kit.call(AgentTicket.redeem)(Redeem("brand-b", at.plusSeconds(1))).replyValue.refused,
        Some(TicketRefusal.AnotherBrand))
      assertEquals(kit.call(AgentTicket.redeem)(Redeem("brand-a", at.plusSeconds(2))).replyValue.refused,
        Some(TicketRefusal.Used))
    }
  }

  test("an unknown ticket is refused and nothing is stored") {
    TestKeyring.around { _ =>
      val kit = KeyValueEntityTestKit.of(AgentTicket, "not-a-real-ticket")
      assertEquals(kit.call(AgentTicket.redeem)(Redeem("brand-a", at)).replyValue.refused, Some(TicketRefusal.Unknown))
      assertEquals(kit.currentState, AgentTicketState())
    }
  }

  test("issuing a ticket again keeps the first issue") {
    TestKeyring.around { _ =>
      val kit   = issued()
      val again = kit.call(AgentTicket.issue)(Issue("brand-b", "operator-2", at.plusSeconds(10))).replyValue
      assertEquals(again, Issued(issued = false, expiresAt = at.plus(AgentTicket.Ttl)))
      assertEquals(kit.currentState.brand, "brand-a")
    }
  }

  test("a ticket is kept for a while after its issue and its use, then removed") {
    TestKeyring.around { _ =>
      val kit = KeyValueEntityTestKit.of(AgentTicket, "t-1")
      kit.call(AgentTicket.issue)(Issue("brand-a", "operator-1", at)): Unit
      assertEquals(kit.lastRetention, Some(Retention.ExpireAfter(AgentTicket.Kept)))
      kit.call(AgentTicket.redeem)(Redeem("brand-a", at)): Unit
      assertEquals(kit.lastRetention, Some(Retention.ExpireAfter(AgentTicket.Kept)))
      assertEquals(kit.currentState.usedAt, Some(at))
    }
  }

  test("successive tickets are distinct and opaque, URL-safe") {
    val tickets = Vector.fill(50)(AgentTicket.newTicket())
    assertEquals(tickets.distinct.size, 50)
    assert(tickets.forall(_.matches("[A-Za-z0-9_-]{43}")), tickets.head)
  }

  test("each refusal has its wire name") {
    assertEquals(TicketRefusal.values.map(_.wire).toVector, Vector("unknown", "used", "another_brand", "expired"))
  }
