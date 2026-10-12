package io.eitheror.casino.simulator.core

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient

import scala.concurrent.duration.FiniteDuration

/**
 * Sending one callback for a session and keeping the record (FR-002, FR-003, FR-020): schedule it
 * on its callback entity, plan it on the session so the round moves, send it through the outbound
 * path, record the attempt, and note any balance the brand answered. Used by the instruction routes
 * for the default scenario, by `ScenarioPlay` for every scripted send, and by a load profile.
 */
final class Play(client: ComponentClient):
  import Play.Sent


  def session(token: String): Option[Session] =
    try Some(client.forEventSourcedEntity(EntityId(token)).call(SessionEntity.get).invoke())
    catch case _: com.thinkmorestupidless.ankka.core.CommandError => None

  /**
   * Schedules, plans and sends the first attempt of a callback. `step` is the scenario step that
   * sent it, absent in the default scenario.
   */
  def send(
      session: Session,
      call: Call,
      shape: String,
      key: String,
      answerWait: FiniteDuration,
      step: Option[Int],
      callbackId: String = java.util.UUID.randomUUID().toString
  ): Sent =
    val protocol = Wiring.protocol
    val body     = protocol.body(shape, call)
    val route    = protocol.route(call.kind)
    client.forEventSourcedEntity(EntityId(callbackId)).call(CallbackEntity.schedule)
      .invoke(CallbackEvent.Scheduled(session.runId, session.launchToken, session.brand, call.kind, route, String(body, "UTF-8"), step, Wiring.now()))
    val role = protocol.roleOf(call.kind, call.providerKind, call.amount)
    client.forEventSourcedEntity(EntityId(session.launchToken)).call(SessionEntity.plan).invoke(
      SessionEvent.CallbackPlanned(
        callbackId, call.kind, call.round,
        transactionId = call.transactionId.getOrElse(""), cancelId = call.cancelId, role = role.toString,
        free = call.providerKind.exists(_.startsWith("free_")) && role == RoundRole.Stake, step = step
      )
    )
    attempt(session, callbackId, route, body, key, answerWait, 1)

  /** Sends a scheduled callback again, as attempt `n`, with the body recorded the first time. */
  def resend(session: Session, callbackId: String, key: String, answerWait: FiniteDuration, n: Int): Sent =
    val callback = client.forEventSourcedEntity(EntityId(callbackId)).call(CallbackEntity.get).invoke()
    attempt(session, callbackId, callback.route, callback.body.getBytes("UTF-8"), key, answerWait, n)

  private def attempt(session: Session, callbackId: String, route: String, body: Array[Byte], key: String, answerWait: FiniteDuration, n: Int): Sent =
    val attempt = Wiring.outbound.send(session.brand, route, body, key, answerWait, n)
    client.forEventSourcedEntity(EntityId(callbackId)).call(CallbackEntity.record).invoke(attempt)
    val reading = attempt.httpStatus.map(status => Wiring.protocol.read(status, attempt.answer.getOrElse("").getBytes("UTF-8")))
    reading.flatMap(_.balance).foreach(b => client.forEventSourcedEntity(EntityId(session.launchToken)).call(SessionEntity.sawBalance).invoke(b))
    Sent(callbackId, attempt, reading, body)

  /** Marks a callback finished, with what finished it. */
  def end(callbackId: String, sent: Sent): Unit =
    client.forEventSourcedEntity(EntityId(callbackId)).call(CallbackEntity.end).invoke(Play.outcomeOf(sent.attempt, sent.reading))

  def end(callbackId: String, outcome: String): Unit =
    client.forEventSourcedEntity(EntityId(callbackId)).call(CallbackEntity.end).invoke(outcome)

object Play:
  /** The outcome of one attempt, with what the protocol reads in it. */
  final case class Sent(callbackId: String, attempt: Attempt, reading: Option[Reading], body: Array[Byte])

  def outcomeOf(attempt: Attempt, reading: Option[Reading]): String =
    reading match
      case Some(r) if r.malformedBody => Callback.MalformedBody
      case Some(_)                    => Callback.Answered
      case None if attempt.error.exists(_.startsWith("brand unreachable")) => Callback.Unreachable
      case None                       => Callback.GaveUp
