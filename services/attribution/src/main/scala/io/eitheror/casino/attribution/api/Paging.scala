package io.eitheror.casino.attribution.api

/**
 * An operator list's page (spec 030 L, research R14): `page` from 1 and `pageSize` from 1 to 200, 50 when
 * only `page` is named. Neither named is no paging: the list answers whole, as it did before 030.
 */
object Paging:
  val MaxPageSize     = 200
  val DefaultPageSize = 50

  final case class Page(page: Int, pageSize: Int):
    /** How many rows come before this page. */
    def skip: Long = (page.toLong - 1) * pageSize

  /** The page the parameters ask for, none when neither is named, or the first parameter that is not valid. */
  def from(page: Option[String], pageSize: Option[String]): Either[String, Option[Page]] =
    def named(v: Option[String]) = v.map(_.trim).filter(_.nonEmpty)
    (named(page), named(pageSize)) match
      case (None, None) => Right(None)
      case (p, s) =>
        for
          n    <- p.fold[Either[String, Int]](Right(1))(_.toIntOption.filter(_ >= 1).toRight("page"))
          size <- s.fold[Either[String, Int]](Right(DefaultPageSize))(_.toIntOption.filter(v => v >= 1 && v <= MaxPageSize).toRight("pageSize"))
        yield Some(Page(n, size))
