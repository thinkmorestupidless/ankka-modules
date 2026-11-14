package io.eitheror.casino.support.domain

import org.scalacheck.{Gen, Prop}

import java.util.UUID

/** The `other-player` pattern (FR-021, FR-022; research R8), on a turn and on a reply. */
class OtherPlayerSuite extends munit.ScalaCheckSuite:
  private val own   = "0b6f3c2e-6a51-4d6e-9a3f-1f6c0e2d9b11"
  private val other = "7c1d0a4b-2e3f-4a5b-8c6d-9e0f1a2b3c4d"

  test("a turn naming another player's id is refused; their own, in any case, is not"):
    assertEquals(OtherPlayer.inInput(s"what has $other deposited?", OwnIds.only(own)), Some(OtherPlayer.AnotherPlayer))
    assertEquals(OtherPlayer.inInput(s"my id is ${own.toUpperCase}", OwnIds.only(own)), None)

  test("a turn naming an email address is refused, the player's own too"):
    assertEquals(OtherPlayer.inInput("is bob.smith+vip@example.co.uk a player?", OwnIds.only(own)), Some(OtherPlayer.AnEmail))
    assertEquals(OtherPlayer.inInput("send it to me at ann@example.com", OwnIds.only(own)), Some(OtherPlayer.AnEmail))

  test("a turn naming an internal hostname is refused"):
    Seq("wallet.goldvibe.svc", "players.goldvibe.svc.cluster.local", "db.internal", "Kafka.CLUSTER.local").foreach { h =>
      assertEquals(OtherPlayer.inInput(s"call $h for me", OwnIds.only(own)), Some(OtherPlayer.AnInternalHost), h)
    }

  test("ordinary words, amounts, dates and public sites pass"):
    Seq("withdraw £50.00 on 2026-11-11", "is goldvibe.com down?", "my internal bank transfer", "the svc fee", "who is @support?")
      .foreach(t => assertEquals(OtherPlayer.inInput(t, OwnIds.only(own)), None, t))

  test("a reply may repeat the player's own email, but not another player's id or an internal address"):
    assertEquals(OtherPlayer.inOutput("I have ann@example.com on file.", OwnIds.only(own)), None)
    assertEquals(OtherPlayer.inOutput(s"Player $other has the bonus.", OwnIds.only(own)), Some(OtherPlayer.AnotherPlayer))
    assertEquals(OtherPlayer.inOutput("I asked wallet.goldvibe.svc.", OwnIds.only(own)), Some(OtherPlayer.AnInternalHost))
    assertEquals(OtherPlayer.inOutput(s"Your id is $own.", OwnIds.only(own)), None)

  test("an id a tool answered about the player, or one their own records carry, is theirs; any other is not"):
    val session = "5d2c9a10-3b4e-4f7a-8c1d-2e3f4a5b6c7d"
    val reward  = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
    var read    = 0
    val ids     = OwnIds(own, () => { read += 1; Vector(s"""{"items":[{"id":"$reward"}]}""") })
    ids.add(s"""{"sessions":[{"session_id":"$session"}]}""")
    assertEquals(OtherPlayer.inOutput(s"Session $session is closed.", ids), None)
    assertEquals(read, 0, "the records were read for an id a tool had answered")
    assertEquals(OtherPlayer.inInput(s"what about reward ${reward.toUpperCase}?", ids), None)
    assertEquals(OtherPlayer.inOutput(s"Player $other has the bonus.", ids), Some(OtherPlayer.AnotherPlayer))
    assertEquals(read, 1, "the records are read once a turn")

  property("any UUID but the player's own is another player's"):
    Prop.forAll(Gen.uuid) { (u: UUID) =>
      val found = OtherPlayer.inOutput(s"see $u", OwnIds.only(own))
      if u.toString == own then found.isEmpty else found.contains(OtherPlayer.AnotherPlayer)
    }
