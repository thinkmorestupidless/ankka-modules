package io.eitheror.casino.engagement.events

/**
 * One Customer.io event a fact makes (contracts/facts.md): its name, the send key that makes it once
 * (`<event>/<fact id>` unless the table says otherwise), and its data in the original's order, every value a
 * string as the original sent them (the client writes the numeric ones as numbers, FR-003).
 */
final case class Event(name: String, key: String, data: Vector[(String, String)])

/** FR-011's two classes, and the identify with no event (FR-010). */
enum SendClass(val wire: String):
  case Marketing extends SendClass("marketing")
  case Service   extends SendClass("service")
  case Identify  extends SendClass("identify")
