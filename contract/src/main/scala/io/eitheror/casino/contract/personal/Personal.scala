package io.eitheror.casino.contract.personal

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}

/**
 * A field that identifies a person (spec 001 FR-023), marked so that the contract's schemas say so
 * (`"x-personal": true`) and every producer can be found.
 *
 * This is a marker, not protection: ankka 042's `Personal[A]`, which encrypts the value under the
 * player's subject so an erasure makes it unreadable, is specified and not yet built. When it ships,
 * this type becomes an alias of ankka's and the codec its envelope; until then a brand holding real
 * players' data cannot honour an erasure, which is why spec 001 FR-040 refuses real money without
 * erasure on (research R5). Nothing here pretends otherwise.
 */
opaque type Personal[A] = A

object Personal:
  def apply[A](value: A): Personal[A] = value

  extension [A](personal: Personal[A]) def reveal: A = personal

  /** Facts carry personal text only; a concrete codec, which jsoniter's macro needs for an opaque type. */
  given personalText: JsonValueCodec[Personal[String]] with
    def decodeValue(in: JsonReader, default: Personal[String]): Personal[String] = in.readString(null)
    def encodeValue(x: Personal[String], out: JsonWriter): Unit                  = out.writeVal(x)
    def nullValue: Personal[String]                                              = null

/** The subject a player's personal fields are held under: `player/<id>` (spec 001 clarification). */
final case class PlayerSubject(playerId: String):
  def id: String = s"player/$playerId"

/**
 * The fields spec 001 FR-023 names as personal, by the names facts and stores give them. A field of
 * a fact whose name is here must be typed `Personal`; a service spec may add names, never remove one.
 * FR-024's opposites (amounts, currencies, ids, times, statuses, game identifiers, a country, a
 * pseudonym) are deliberately absent.
 */
object PersonalFields:
  val names: Set[String] = Set(
    "given_name", "family_name", "display_name", "email", "email_address", "telephone", "mobile",
    "address_line", "city", "province", "state", "post_code", "postal_address", "date_of_birth",
    "nationality", "document", "document_number", "network_address", "ip_address", "device_id",
    "device_fingerprint", "user_agent", "note", "free_text", "payment_details"
  )

  /** Fields FR-024 says are not personal, so a check never mistakes one for a leak. */
  val notPersonal: Set[String] = Set("country", "currency", "pseudonym", "player_id", "occurred_at")
