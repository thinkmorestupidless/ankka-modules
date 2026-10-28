package io.eitheror.casino.attribution.it

import io.eitheror.casino.attribution.carriers.WebhookHttp

import scala.collection.mutable

/**
 * A network's webhook endpoint: records every post with its headers and body, answers what the test
 * scripted (a status, or no answer within the brand's timeout), and checks each signature under the secret
 * the test gave it (contracts/protocol.md *A webhook post*).
 */
final class WebhookReceiver(@volatile var secret: String = "s3cret") extends FakeServer:
  /** What the next posts answer, in order; once used up, `default`. `None` is no answer: the post hangs. */
  private val script = mutable.Queue.empty[Option[Int]]
  @volatile var default: Option[Int] = Some(200)
  @volatile var hang: java.time.Duration = java.time.Duration.ofSeconds(15)

  def hookUrl: String = s"$address/hook"

  def answer(statuses: Option[Int]*): Unit = script.synchronized(script.enqueueAll(statuses)): Unit

  /** Whether a post's signature is the body's HMAC under the secret. */
  def signed(r: Received): Boolean =
    r.headers.get("x-attribution-signature").contains(s"sha256=${WebhookHttp.signature(r.body, secret)}")

  def posts: Vector[Received] = received.filter(_.method == "POST")

  def reset(): Unit = { script.synchronized(script.clear()); default = Some(200); clearReceived() }

  protected def route(r: Received): Answer =
    val next = script.synchronized(if script.nonEmpty then script.dequeue() else default)
    next match
      case Some(status) => Answer(status, "")
      case None =>
        Thread.sleep(hang.toMillis)
        Answer(504, "")
