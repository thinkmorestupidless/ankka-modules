package io.eitheror.casino.programme.it

import scala.collection.mutable

/** An affiliate's postback address: keeps every call, answers what the test scripted (200 by default). */
final class PostbackReceiver extends FakeServer:
  private val script = mutable.Queue.empty[Int]
  @volatile var default: Int = 200

  /** A postback address with the original's macros, on this receiver. */
  def url(template: String = "event={event}&click={clickid}&payout={payout}&status={status}"): String = s"$address/postback?$template"

  def answer(statuses: Int*): Unit = script.synchronized(script.enqueueAll(statuses)): Unit
  def calls: Vector[Received]      = received.filter(_.path == "/postback")
  def reset(): Unit                = { script.synchronized(script.clear()); default = 200; clearReceived() }

  protected def route(r: Received): Answer =
    Answer(script.synchronized(if script.nonEmpty then script.dequeue() else default), "")
