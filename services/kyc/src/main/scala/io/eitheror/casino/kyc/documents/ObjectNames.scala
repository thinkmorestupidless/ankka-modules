package io.eitheror.casino.kyc.documents

import io.eitheror.casino.kyc.record.Category

import java.time.Instant

/**
 * Where a document is kept in the bucket (R7): `subjects/<player>/kyc/<category>-<epoch millis>.<ext>`.
 * The name carries the player's id and the category, never anything the player typed. Everything one
 * player has is under `subjects/<player>/`, which the erasure deletes whole (FR-033).
 */
object ObjectNames:
  /** The content types the service accepts (FR-005, R18), with the extension the original gave each. */
  val Accepted: Map[String, String] = Map("image/jpeg" -> "jpg", "image/png" -> "png", "application/pdf" -> "pdf")

  def accepted(contentType: String): Boolean = Accepted.contains(contentType)

  /** The object's name, or none for a content type not accepted. */
  def of(playerId: String, category: Category, at: Instant, contentType: String): Option[String] =
    Accepted.get(contentType).map(ext => s"${prefix(playerId)}kyc/${category.wire}-${at.toEpochMilli}.$ext")

  /** Everything kept for the player. */
  def prefix(playerId: String): String = s"subjects/$playerId/"
