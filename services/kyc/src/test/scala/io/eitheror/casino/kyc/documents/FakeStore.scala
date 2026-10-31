package io.eitheror.casino.kyc.documents

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.concurrent.TrieMap

/**
 * A bucket in memory for the suites that do not run the S3-compatible container: objects put by name
 * (as the browser would through a signed link), links that name the object and the lifetime, every
 * version kept, and an outage to switch on.
 */
final class FakeStore extends Store:
  private val objects  = TrieMap.empty[String, Vector[Head]]
  private val down     = AtomicBoolean(false)

  /** The browser's PUT through the signed link: a new version of the object. */
  def put(objectName: String, size: Long, contentType: String): Unit =
    objects.updateWith(objectName)(v => Some(v.getOrElse(Vector.empty) :+ Head(size, contentType))): Unit

  def names: Set[String]                 = objects.keySet.toSet
  def versions(objectName: String): Int  = objects.get(objectName).fold(0)(_.size)
  def outage(on: Boolean): Unit          = down.set(on)

  private def up[A](a: => A): Either[String, A] = if down.get then Left("the store is unreachable") else Right(a)

  def signPut(objectName: String, contentType: String, ttl: Duration): Either[String, String] =
    up(s"https://store.test/put/$objectName?type=$contentType&ttl=${ttl.toSeconds}")

  def signGet(objectName: String, ttl: Duration): Either[String, String] =
    up(s"https://store.test/get/$objectName?ttl=${ttl.toSeconds}")

  def head(objectName: String): Either[String, Option[Head]] = up(objects.get(objectName).flatMap(_.lastOption))

  def delete(objectName: String): Either[String, Unit] = up(objects.remove(objectName): Unit)

  def deleteAll(prefix: String): Either[String, Int] =
    up {
      val under = objects.keys.filter(_.startsWith(prefix)).toVector
      under.map(k => objects.remove(k).fold(0)(_.size)).sum
    }
