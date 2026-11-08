package io.eitheror.casino.programme.comms

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

class NotificationSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-11-01T10:00:00Z")

  test("written once from a change; read once") {
    val id = Notification.idFor("membership/aff-1/goldvibe/approved")
    assertEquals(id, Notification.idFor("membership/aff-1/goldvibe/approved"))
    val k = KeyValueEntityTestKit.of(Notification, id)
    k.call(Notification.write)(WriteNotification("aff-1", Some("goldvibe"), "APPLICATION_APPROVED", "Approved", at)): Unit
    assert(!k.call(Notification.write)(WriteNotification("aff-1", None, "X", "Y", at)).changed)
    assertEquals(k.call(Notification.read)(at.plusSeconds(5)).replyValue.readAt, Some(at.plusSeconds(5)))
    assert(!k.call(Notification.read)(at.plusSeconds(9)).changed)
  }
