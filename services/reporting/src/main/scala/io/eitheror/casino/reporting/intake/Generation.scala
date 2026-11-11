package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.ComponentId

/**
 * The generation (research R8): the first segment of every intake and day entity id, `g<n>/…`, and the suffix of
 * every consumer's component id, `<name>-g<n>`. Raising `REPORTING_GENERATION` gives every consumer a new id, so it
 * reads its topic again from the earliest offset (or its entity's events from the first) into new entity ids; the
 * old generation's entities are left, and a consumer over entity events passes over another generation's.
 */
object Generation:
  def prefix(generation: Int): String = s"g$generation"

  /** `g<n>/<parts joined by '/'>`. */
  def id(generation: Int, parts: String*): String = (prefix(generation) +: parts).mkString("/")

  def componentId(name: String, generation: Int): ComponentId = ComponentId(s"$name-${prefix(generation)}")

  /** The generation an id begins with, if it begins with one. */
  def of(id: String): Option[Int] =
    val first = id.takeWhile(_ != '/')
    Option.when(first.length > 1 && first.head == 'g' && first.length < id.length)(first.tail).flatMap(_.toIntOption)

  /** Whether `id` belongs to `generation`. */
  def belongs(id: String, generation: Int): Boolean = of(id).contains(generation)

  /** The id after its generation: `p-1/EUR` of `g1/p-1/EUR`. */
  def rest(id: String): String = if of(id).isDefined then id.drop(id.indexOf('/') + 1) else id
