package io.eitheror.casino.merchant.operators

import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.it.{Answer, FakeServer, Received}

/** The original's `OperatorInviteEmailServiceSpec`: the transactional send, its link and its template. */
class InvitationsSuite extends munit.FunSuite:

  private val cio = new FakeServer:
    protected def route(r: Received): Answer = Answer(200, """{"delivery_id":"d-1"}""")
  override def afterAll(): Unit = cio.stop()
  override def beforeEach(context: BeforeEach): Unit = cio.clearReceived()

  private val settings = Invitations.CustomerIo("app-key", "tx-invite", Some("tx-reset"))
  private def client(s: Option[Invitations.CustomerIo] = Some(settings)) = Invitations.customerIo("eu", () => s, Some(cio.address))

  test("an invitation is sent as a transactional email with the accept link carrying the token") {
    val link = Invitations.link("https://payments.example", Invitation.Kind.Invite, "tok-1")
    assertEquals(link, "https://payments.example/accept-invitation?token=tok-1")
    assertEquals(client().send(Invitation(Invitation.Kind.Invite, "u-1", "ops@acme.example", "tok-1", link)), Right(()))
    val sent = cio.received.head
    assertEquals((sent.path, sent.headers.get("authorization")), ("/v1/send/email", Some("Bearer app-key")))
    val body = sent.json.get
    assertEquals((body.textOr("transactional_message_id", ""), body.textOr("to", ""), body.get("message_data").textOr("invite_url", "")),
      ("tx-invite", "ops@acme.example", link))
  }

  test("a password reset uses its own template and lands on the reset page, not the accept page") {
    val link = Invitations.link("https://payments.example/", Invitation.Kind.PasswordReset, "tok-2")
    assertEquals(link, "https://payments.example/reset-password?token=tok-2")
    client().send(Invitation(Invitation.Kind.PasswordReset, "u-1", "ops@acme.example", "tok-2", link)): Unit
    assertEquals(cio.received.head.json.get.textOr("transactional_message_id", ""), "tx-reset")
  }

  test("with no provider configured nothing is sent and the reason is answered") {
    assert(client(None).send(Invitation(Invitation.Kind.Invite, "u", "e", "t", "l")).isLeft)
    assertEquals(cio.received.size, 0)
  }
