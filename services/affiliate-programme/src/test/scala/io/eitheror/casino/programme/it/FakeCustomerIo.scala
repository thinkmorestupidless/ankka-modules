package io.eitheror.casino.programme.it

import io.eitheror.casino.programme.comms.CustomerIo

import scala.collection.mutable

/** Customer.io in process: keeps every send, answers what the test scripted (200 by default). */
final class FakeCustomerIo extends CustomerIo:
  final case class Sent(apiKey: String, region: String, transactionalId: String, to: String, data: Map[String, String])
  private val log    = mutable.ArrayBuffer.empty[Sent]
  private val script = mutable.Queue.empty[Either[String, Int]]
  @volatile var default: Either[String, Int] = Right(200)

  def send(apiKey: String, region: String, transactionalId: String, to: String, data: Map[String, String]): Either[String, Int] =
    synchronized {
      log += Sent(apiKey, region, transactionalId, to, data)
      if script.nonEmpty then script.dequeue() else default
    }

  def answer(results: Either[String, Int]*): Unit = synchronized(script.enqueueAll(results)): Unit
  def sent: Vector[Sent]                          = synchronized(log.toVector)
  def to(email: String): Vector[Sent]             = sent.filter(_.to == email)
  def reset(): Unit                               = synchronized { log.clear(); script.clear(); default = Right(200) }
