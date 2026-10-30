package io.eitheror.casino.kyc.it

import io.eitheror.casino.kyc.documents.{Head, Store}

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** A store that passes every call to `underlying` and keeps each as (operation, object name or prefix). */
final class CountingStore(underlying: Store) extends Store:
  private val log = ConcurrentLinkedQueue[(String, String)]()

  def calls: Vector[(String, String)] = log.asScala.toVector

  private def counted[A](op: String, name: String)(a: => A): A = { log.add(op -> name); a }

  def signPut(objectName: String, contentType: String, ttl: Duration) = counted("signPut", objectName)(underlying.signPut(objectName, contentType, ttl))
  def signGet(objectName: String, ttl: Duration)                      = counted("signGet", objectName)(underlying.signGet(objectName, ttl))
  def head(objectName: String): Either[String, Option[Head]]          = counted("head", objectName)(underlying.head(objectName))
  def delete(objectName: String)                                      = counted("delete", objectName)(underlying.delete(objectName))
  def deleteAll(prefix: String)                                       = counted("deleteAll", prefix)(underlying.deleteAll(prefix))
