package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.runtime.ViewQueries
import com.thinkmorestupidless.ankka.sdk.DeclaredQuery

/** A read that would answer more rows than it may: refused, never cut short (review, should fix 5). */
final class TooManyRows(val query: DeclaredQuery, val limit: Int)
    extends RuntimeException(s"${query.view}/${query.name} matched more than $limit rows: refused rather than answer part of them")

/**
 * How a read gets every row it asks for (constitution II: every player, not a sample). A query whose rows can be many
 * is paged by key (`all`): each page asks for the rows after the last one's key, until a page is short. A query whose
 * rows are bounded by what it is asked (a day's rounds, the documents kept) is asked once for one row more than its
 * bound (`bounded`), and more than the bound fails the read loudly instead of answering part of it.
 */
object Paging:
  /** The rows a keyed page holds. */
  val Page = 10_000

  /** The most rows a bounded read answers. */
  val Bound = 10_000_000

  /** The key before every key: a page from the first row. */
  val First = ""

  /** Every row of a keyed query: `page(after)` answers at most `size` rows after `after`, in key order. */
  def all[R](page: String => Vector[R], key: R => String, size: Int = Page): Vector[R] =
    @scala.annotation.tailrec
    def from(after: String, acc: Vector[R]): Vector[R] =
      val rows = page(after)
      if rows.size < size then acc ++ rows else from(key(rows.last), acc ++ rows)
    from(First, Vector.empty)

  /** A keyed query's every row, its pages of `size` asked with `values` and the last key as `after`. */
  def keyed[R](q: ViewQueries[R], query: DeclaredQuery, key: R => String, values: (String, String)*): Vector[R] =
    keyedBy(Page)(q, query, key, values*)

  def keyedBy[R](size: Int)(q: ViewQueries[R], query: DeclaredQuery, key: R => String, values: (String, String)*): Vector[R] =
    all(after => q.ask(query, size, (values :+ ("after" -> after))*), key, size)

  /** A bounded query's rows: more than `limit` is `TooManyRows`. */
  def bounded[R](q: ViewQueries[R], query: DeclaredQuery, values: (String, String)*): Vector[R] =
    boundedTo(Bound)(q, query, values*)

  def boundedTo[R](limit: Int)(q: ViewQueries[R], query: DeclaredQuery, values: (String, String)*): Vector[R] =
    checked(query, limit)(q.ask(query, limit + 1, values*))

  /** `rows`, asked with a limit of `limit` + 1: more than `limit` is `TooManyRows`, logged. */
  def checked[R](query: DeclaredQuery, limit: Int)(rows: Vector[R]): Vector[R] =
    if rows.size <= limit then rows
    else
      val refused = TooManyRows(query, limit)
      log.error(refused.getMessage)
      throw refused

  private val log = org.slf4j.LoggerFactory.getLogger("io.eitheror.casino.reporting.reads.Paging")
