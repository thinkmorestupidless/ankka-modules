package io.eitheror.casino.support.domain

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.support.config.ConfigBody

import scala.jdk.CollectionConverters.*

/** A refused `PUT`: its status, its code, and the field it names (contracts/routes.md). */
final case class ConfigRefusal(status: Int, code: String, field: Option[String], message: String)

/**
 * data-model.md's rules for `ConfigBody`, pure: a `PUT` body read over the current body (a field left out keeps its
 * value), each field checked, and the safeguarding rule of a real-money brand (FR-023).
 */
object ConfigRules:

  /** The personal fields players' `agent-profile` serves (spec 001 FR-023; spec 008 amendment P3). */
  val PersonalFields: Set[String] = Set("email", "firstName", "lastName", "country", "language", "dateOfBirth")

  val Fields: Vector[String] = Vector("enabled", "prompt", "handOffMessage", "supportHours", "unavailableMessage", "closingMessage",
    "safeguardingMessage", "helpline", "safeguardingOptIn", "personalFields", "model", "maxInputChars", "turnsPerMinute",
    "dailyTokens", "approvalMinutes", "retentionDays")

  /** The fields whose values a change's audit record may carry (contracts/audit.md): never the prompt or a message. */
  val ValuesRecorded: Set[String] = Set("enabled", "model", "maxInputChars", "turnsPerMinute", "dailyTokens", "approvalMinutes",
    "retentionDays", "personalFields", "safeguardingOptIn")

  private def invalid(field: String, why: String) = ConfigRefusal(400, "invalid_config", Some(field), s"$field $why")

  def read(body: JsonNode, current: ConfigBody, realMoney: Boolean): Either[ConfigRefusal, ConfigBody] =
    if body == null || !body.isObject then Left(ConfigRefusal(400, "invalid_body", None, "The body is not a JSON object"))
    else
      val names = body.fieldNames.asScala.toVector
      names.find(n => !Fields.contains(n)) match
        case Some(n) => Left(ConfigRefusal(400, "unknown_field", Some(n), s"$n is not a configuration field"))
        case None =>
          def has(f: String) = names.contains(f)
          def node(f: String) = body.get(f)
          def bool(f: String, d: Boolean) =
            if !has(f) then Right(d) else Option(node(f)).filter(_.isBoolean).map(_.asBoolean).toRight(invalid(f, "is not true or false"))
          def text(f: String, d: String, max: Int = 20_000) =
            if !has(f) then Right(d)
            else Option(node(f)).filter(_.isTextual).map(_.asText).filter(s => s.trim.nonEmpty && s.length <= max)
              .toRight(invalid(f, s"is not a text of 1 to $max characters"))
          def optional(f: String, d: Option[String]) =
            if !has(f) then Right(d)
            else node(f) match
              case n if n.isNull                          => Right(None)
              case n if n.isTextual && n.asText.trim.isEmpty => Right(None)
              case n if n.isTextual && n.asText.length <= 2000 => Right(Some(n.asText))
              case _                                      => Left(invalid(f, "is not a text of at most 2000 characters"))
          def whole(f: String, d: Long, lo: Long, hi: Long) =
            if !has(f) then Right(d)
            else Option(node(f)).filter(n => n.isIntegralNumber && n.canConvertToLong).map(_.asLong).filter(v => v >= lo && v <= hi)
              .toRight(invalid(f, s"is not a whole number from $lo to $hi"))
          def fields(d: Set[String]): Either[ConfigRefusal, Set[String]] =
            if !has("personalFields") then Right(d)
            else Option(node("personalFields")).filter(_.isArray) match
              case None => Left(invalid("personalFields", "is not a list of field names"))
              case Some(a) =>
                val named = a.elements.asScala.toVector.map(_.asText)
                named.find(n => !PersonalFields(n)) match
                  case Some(n) => Left(ConfigRefusal(400, "unknown_field", Some(n), s"$n is not a personal field the agent may read"))
                  case None    => Right(named.toSet)
          for
            enabled   <- bool("enabled", current.enabled)
            prompt    <- text("prompt", current.prompt)
            hours     <- text("supportHours", current.supportHours, 200)
            handOff   <- text("handOffMessage", current.handOffMessage, 2000)
            _         <- Either.cond(handOff.contains("{hours}") || handOff.contains(hours), (), invalid("handOffMessage", "names neither {hours} nor the hours"))
            busy      <- text("unavailableMessage", current.unavailableMessage, 2000)
            closing   <- text("closingMessage", current.closingMessage, 2000)
            sgMessage <- optional("safeguardingMessage", current.safeguardingMessage)
            helpline  <- optional("helpline", current.helpline)
            optIn     <- bool("safeguardingOptIn", current.safeguardingOptIn)
            personal  <- fields(current.personalFields)
            model     <- text("model", current.model, 200)
            maxInput  <- whole("maxInputChars", current.maxInputChars, 1, 20_000)
            turns     <- whole("turnsPerMinute", current.turnsPerMinute, 1, 120)
            tokens    <- whole("dailyTokens", current.dailyTokens, 1, Long.MaxValue)
            approval  <- whole("approvalMinutes", current.approvalMinutes, 1, 1440)
            retention <- whole("retentionDays", current.retentionDays, 1, 3650)
            next = ConfigBody(enabled, prompt, handOff, hours, busy, closing, sgMessage, helpline, optIn, personal, model,
              maxInput.toInt, turns.toInt, tokens, approval.toInt, retention.toInt)
            _ <- safeguarded(next, realMoney)
          yield next

  /** A real-money brand cannot turn the agent on without its safeguarding message and helpline (FR-023). */
  def safeguarded(b: ConfigBody, realMoney: Boolean): Either[ConfigRefusal, Unit] =
    if b.enabled && realMoney && (b.safeguardingMessage.isEmpty || b.helpline.isEmpty) then
      Left(ConfigRefusal(422, "safeguarding_not_configured", None,
        "A brand that takes real money needs a safeguarding message and a helpline before the agent is turned on"))
    else Right(())

  /** The fields `next` changes, by name, and the values of those an audit record may carry. */
  def changes(prev: ConfigBody, next: ConfigBody): (Vector[String], Map[String, String]) =
    val p = values(prev)
    val n = values(next)
    val changed = Fields.filter(f => p(f) != n(f))
    (changed, changed.filter(ValuesRecorded).map(f => f -> n(f)).toMap)

  private def values(b: ConfigBody): Map[String, String] = Map(
    "enabled" -> b.enabled.toString, "prompt" -> b.prompt, "handOffMessage" -> b.handOffMessage, "supportHours" -> b.supportHours,
    "unavailableMessage" -> b.unavailableMessage, "closingMessage" -> b.closingMessage,
    "safeguardingMessage" -> b.safeguardingMessage.getOrElse(""), "helpline" -> b.helpline.getOrElse(""),
    "safeguardingOptIn" -> b.safeguardingOptIn.toString, "personalFields" -> b.personalFields.toVector.sorted.mkString(","),
    "model" -> b.model, "maxInputChars" -> b.maxInputChars.toString, "turnsPerMinute" -> b.turnsPerMinute.toString,
    "dailyTokens" -> b.dailyTokens.toString, "approvalMinutes" -> b.approvalMinutes.toString, "retentionDays" -> b.retentionDays.toString)
