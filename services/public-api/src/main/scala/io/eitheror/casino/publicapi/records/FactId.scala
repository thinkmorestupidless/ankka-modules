package io.eitheror.casino.publicapi.records

import io.eitheror.casino.contract.facts.{Fact, Topic}

import java.security.MessageDigest
import java.time.Instant

/**
 * A fact's id, reporting's exactly (`services/reporting/.../intake/FactId.scala`): the facts carry none and ankka 0.16
 * gives a consumer no `ce-id`, so it is the first 16 bytes, in hex, of the SHA-256 of the topic's name and the fact as
 * the contract writes it. A redelivery is the same value and so the same id.
 */
object FactId:
  def of[F <: Fact](topic: Topic[F], fact: F): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(topic.name.getBytes("UTF-8"))
    digest.update(topic.serializer.toBytes(fact))
    digest.digest().take(16).map(b => f"${b & 0xff}%02x").mkString

  /** Every family of every topic this service reads has its time as `occurredAt`. */
  def occurredAt(fact: Fact): Instant = fact match
    case p: Product =>
      p.productElementNames.zip(p.productIterator).collectFirst { case ("occurredAt", at: Instant) => at }
        .getOrElse(throw IllegalArgumentException(s"${p.productPrefix} has no occurredAt"))
    case other => throw IllegalArgumentException(s"${other.getClass.getSimpleName} has no occurredAt")

  /** The ids a record remembers: the last 64 (data-model.md *Records*), so a redelivery within them is ignored. */
  val Window = 64

  def remember(seen: Vector[String], id: String): Vector[String] =
    if seen.length < Window then seen :+ id else seen.drop(seen.length - Window + 1) :+ id
