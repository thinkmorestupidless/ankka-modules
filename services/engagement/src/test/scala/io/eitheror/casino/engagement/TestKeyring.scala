package io.eitheror.casino.engagement

import com.thinkmorestupidless.ankka.core.{DataSubject, KeyAnswer, Keyring}

import java.security.SecureRandom
import scala.collection.concurrent.TrieMap

/**
 * A keyring for the unit kits, which run no service and so install none: a key per subject, made on the first
 * write, and `erase` destroying one, as the installation's keyring does (feature 042). The integration kit has
 * ankka's own.
 */
final class TestKeyring extends Keyring:
  val project: String = "local"

  private val random = SecureRandom()
  private val keys   = TrieMap.empty[String, IArray[Byte]]
  private val erased = TrieMap.empty[String, Unit]

  def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
    if erased.contains(subject.value) then KeyAnswer.Destroyed
    else
      keys.get(subject.value) match
        case Some(k)       => KeyAnswer.Key(k)
        case None if create =>
          val bytes = new Array[Byte](32)
          random.nextBytes(bytes)
          KeyAnswer.Key(keys.getOrElseUpdate(subject.value, IArray.from(bytes)))
        case None          => KeyAnswer.Absent

  def erase(subject: DataSubject): Unit =
    keys.remove(subject.value): Unit
    erased.update(subject.value, ())

object TestKeyring:
  /** Runs `body` with a fresh keyring bound, as a unit suite's test does. */
  def around[T](body: TestKeyring => T): T =
    val k = TestKeyring()
    Keyring.binding(k)(body(k))
