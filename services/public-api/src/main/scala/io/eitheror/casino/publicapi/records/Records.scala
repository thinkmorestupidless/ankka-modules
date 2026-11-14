package io.eitheror.casino.publicapi.records

import com.thinkmorestupidless.ankka.core.{DataSubject, KeyAnswer, Keyring, Personal}
import io.eitheror.casino.contract.personal.Personal as Marked
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import scala.util.Try

/** What the record entities share: the ids of contracts/fields.md §6, personal fields as ankka's `Personal`, the log. */
object Records:
  /** A name-based (version 3) UUID, as contracts/fields.md §6 derives a round's and a secondary row's id. */
  def uuid3(name: String): String = UUID.nameUUIDFromBytes(name.getBytes(UTF_8)).toString

  /** The served id of a round: `uuid3("round/" + roundId)`, the provider's id kept internal. */
  def roundId(providerRoundKey: String): String = uuid3(s"round/$providerRoundKey")

  def subject(playerId: String): DataSubject = DataSubject(s"player/$playerId")

  /**
   * A fact's personal field as this service keeps it (reporting's `Personals`): `Personal` under `player/<id>`, so the
   * store seals it and an erasure leaves it `Erased`; `Erased` already for a player already erased, so a fact read again
   * after an erasure never writes the value back.
   */
  def personal(playerId: String, value: Option[Marked[String]]): Option[Personal[String]] =
    value.flatMap(v => Option(v.reveal)).filter(_.nonEmpty).map { v =>
      val s = subject(playerId)
      if erased(s) then Personal.Erased(s) else Personal(s, v)
    }

  private def erased(s: DataSubject): Boolean =
    Try(Keyring.current).flatMap(k => Try(k.key(k.project, s, create = false))).toOption.contains(KeyAnswer.Destroyed)

  /** A personal field's value: none when absent, empty or erased. */
  def reveal(field: Option[Personal[String]]): Option[String] =
    field.collect { case Personal.Present(_, v) if v != null && v.nonEmpty => v }

  private val log = LoggerFactory.getLogger("io.eitheror.casino.publicapi.records")

  /**
   * A record the contract cannot serve (a currency without two minor digits, a required field missing) is refused at
   * projection: no row, so it is `404` by id. Logged by kind and id only, never a value (original `props.ts:55-60`).
   */
  def refused(kind: String, id: String, why: String): Unit = log.warn("public_api_record_refused kind={} id={} reason={}", kind, id, why)

  def unreadable(topic: String, subject: String): Unit =
    log.warn("a {} fact this service cannot read was skipped (subject {})", topic, subject)
