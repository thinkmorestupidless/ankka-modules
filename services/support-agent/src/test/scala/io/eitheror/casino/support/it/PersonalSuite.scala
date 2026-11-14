package io.eitheror.casino.support.it

import com.thinkmorestupidless.ankka.agent.Json as AgentJson

/**
 * FR-018 and SC-005 (T027): by default the model is given the display name and no other personal value; a field the
 * brand names is given in conversations started after the change, and no other. The fake players answers every
 * personal field it holds whatever is asked, so the service's own filter is what is tested. Every request the model
 * was sent is searched, the context and the tool results among them.
 */
class PersonalSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")
  private lazy val kit = SharedKit.kit

  private val Personal = Map("email" -> "ann@example.com", "lastName" -> "Lee", "dateOfBirth" -> "1990-01-01", "country" -> "Iceland")

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def sent: String = kit.model.requests.map(_.toString).mkString("\n")

  private def turn(session: String): Unit =
    kit.model.expectToolCall("get_player_state", AgentJson.obj(), "c-1").expectText("Done.")
    val s = kit.chat("p-1", session, "what do you know about me?")
    assertEquals(s.names().last, "done")

  test("by default the model is given the display name and no other personal value, and agent-profile is not asked"):
    kit.configure(_.copy(personalFields = Set.empty)): Unit
    turn("personal-default")
    assert(sent.contains("Ann"), "the display name reaches the model")
    Personal.foreach((f, v) => assert(!sent.contains(v), s"$f reached the model"))
    assertEquals(kit.players.calls.count(_.path.endsWith("/agent-profile")), 0)

  test("a field the brand names is given in a conversation started after the change, and no other"):
    kit.configure(_.copy(personalFields = Set("email"))): Unit
    turn("personal-email")
    assert(sent.contains("ann@example.com"), "the named field reaches the model")
    (Personal - "email").foreach((f, v) => assert(!sent.contains(v), s"$f reached the model"))
    assertEquals(kit.players.calls.filter(_.path.endsWith("/agent-profile")).map(_.query.get("fields")).distinct, Vector(Some("email")))

  test("a conversation started before the change keeps the version it started on"):
    kit.configure(_.copy(personalFields = Set.empty)): Unit
    turn("personal-before")
    kit.configure(_.copy(personalFields = Set("email"))): Unit
    kit.newModel(): Unit
    turn("personal-before")
    assert(!sent.contains("ann@example.com"), "the email reached a conversation started before the change")
