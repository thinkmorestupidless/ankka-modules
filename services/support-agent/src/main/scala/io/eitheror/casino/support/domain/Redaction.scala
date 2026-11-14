package io.eitheror.casino.support.domain

import com.fasterxml.jackson.databind.JsonNode

import scala.jdk.CollectionConverters.*

/**
 * A tool's arguments as its trail and audit record keep them (data-model.md, FR-020, FR-028): the five tools'
 * arguments are amounts, currencies, periods, dates, limits and session ids, kept as given. Any other argument is
 * one the model invented, and is kept by its length only, since its text may be anything the player said.
 */
object Redaction:
  val Kept: Set[String] = Set("amount", "currency", "period", "cool_off_until", "game_session_id", "limit")

  def arguments(args: JsonNode): Map[String, String] =
    if args == null || !args.isObject then Map.empty
    else
      args.properties.asScala.map { e =>
        val v = e.getValue
        e.getKey -> (if Kept(e.getKey) && v.isValueNode then v.asText else s"<${if v.isValueNode then v.asText.length else v.toString.length} chars>")
      }.toMap
