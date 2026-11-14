package io.eitheror.casino.publicapi.wire

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.Personal
import io.eitheror.casino.publicapi.records.PlayerState

/**
 * A player's identifying fields (FR-016–FR-018): each written only where the admitted key allows it and its value is
 * present; otherwise absent, never null or empty. An erased value (ankka 042) is absent with every key.
 * `dateOfBirth` is its first ten characters when date-shaped (original `player.ts:52`), else absent.
 */
object Fields:
  private val DateShaped = "^\\d{4}-\\d{2}-\\d{2}".r

  def of(s: PlayerState): Vector[(String, Option[Personal[String]])] = Vector(
    "email" -> s.email, "firstName" -> s.firstName, "lastName" -> s.lastName, "dateOfBirth" -> s.dateOfBirth, "phone" -> s.phone,
    "addressLine1" -> s.addressLine1, "city" -> s.city, "stateProv" -> s.stateProv, "postalCode" -> s.postalCode)

  def write(node: ObjectNode, s: PlayerState, allowed: Set[String]): Vector[String] =
    of(s).flatMap { (name, field) =>
      val value = io.eitheror.casino.publicapi.records.Records.reveal(field).flatMap { v =>
        if name == "dateOfBirth" then DateShaped.findPrefixOf(v) else Some(v)
      }
      value.filter(_ => allowed(name)).map { v => node.put(name, v); name }
    }
