package io.eitheror.casino.kyc.api

import com.thinkmorestupidless.ankka.http.openapi.Description

/**
 * What the player's routes and the public requirements take and answer, for their descriptions
 * (spec 031, contracts/descriptions.md). The routes build their JSON as trees (`Render`,
 * `DocumentRoutes`), so these types exist only to give the document their shapes, field for field.
 */
private[api] object Described:

  /** `Answers.refusal`: the code, the message, and the fields it names. */
  final case class Refusal(error: String, message: Option[String])

  /** `Render.document`, as the player reads it. */
  final case class Document(documentId: String, category: String, status: String, uploadedAt: String,
      reviewedAt: Option[String], reasonCode: Option[String], submittedBy: String, reviewedBy: Option[String])

  final case class CategoryRow(category: String, requiredByPolicy: Boolean, requiredOfPlayer: Boolean, document: Option[Document])

  /** `Render.documents`: the player's status, each category with its latest document, and what the profile lacks. */
  final case class Documents(status: String, mode: String, categories: Vector[CategoryRow], other: Vector[Document],
      missingProfileFields: Vector[String])

  final case class Upload(category: String, contentType: String)
  final case class Offered(documentId: String, url: String, objectName: String, contentType: String, expiresAt: String)
  final case class Confirm(documentId: String)
  final case class Confirmed(document: Option[Document], status: String)
  final case class View(url: String, expiresAt: String)
  final case class Requirements(requiredDocTypes: Vector[String])

  val refusals: Description = Description.none.refusalShape[Refusal]

  val mine = "player_id_mismatch"
