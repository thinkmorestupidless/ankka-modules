package io.eitheror.casino.publicapi.wire

import io.eitheror.casino.publicapi.api.Call
import io.eitheror.casino.publicapi.contract.{Refusal, Refusals}

/** A page asked for: `limit` 1 to the cap (default the cap), `offset` 0 or more (original `rest/paging.ts`). */
final case class Page(limit: Int, offset: Int)

object Paging:
  def parse(call: Call, max: Int): Either[Refusal, Page] =
    for
      limit <- call.param("limit").fold[Either[Refusal, Int]](Right(max))(raw =>
        integer(raw, "limit").flatMap { l =>
          if l < 1 then Left(Refusals.invalidRequest("limit must be at least 1"))
          else if l > max then Left(Refusals.limitExceeded(max))
          else Right(l.toInt)
        })
      offset <- call.param("offset").fold[Either[Refusal, Long]](Right(0L))(integer(_, "offset"))
      _      <- Either.cond(offset <= Int.MaxValue, (), Refusals.invalidRequest("offset is out of range"))
    yield Page(limit, offset.toInt)

  private def integer(raw: String, name: String): Either[Refusal, Long] =
    if !raw.matches("^[0-9]+$") then Left(Refusals.invalidRequest(s"$name must be an integer"))
    else raw.toLongOption.filter(_ <= 9007199254740991L).toRight(Refusals.invalidRequest(s"$name is out of range"))

  /** The envelope `{data, page: {limit, offset, total, hasMore}}`, `data` already rendered as JSON values. */
  def envelope(data: Seq[String], page: Page, total: Long): String =
    val hasMore = page.offset.toLong + page.limit < total
    s"""{"data":[${data.mkString(",")}],"page":{"limit":${page.limit},"offset":${page.offset},"total":$total,"hasMore":$hasMore}}"""
