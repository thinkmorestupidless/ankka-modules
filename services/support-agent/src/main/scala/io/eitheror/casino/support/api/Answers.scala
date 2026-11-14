package io.eitheror.casino.support.api

import com.thinkmorestupidless.ankka.http.HttpProblem

/**
 * How the routes refuse (contracts/routes.md). A stream route refuses before its stream opens, so its refusal is an
 * `HttpProblem`, which ankka writes as `{status, error: <code>, details: {…named fields}}`; the original's
 * literal `Session already active` is kept as the code. The 401s and 403s of a caller the ACL refuses are the ACL's.
 */
object Answers:
  def refuse(status: Int, code: String, fields: (String, String)*): HttpProblem = HttpProblem(status, code, fields.toMap)

  def missingFields(fields: Seq[String]): HttpProblem = refuse(400, "missing_fields", "fields" -> fields.mkString(","))

  def agentUnavailable(message: String): HttpProblem = refuse(503, "agent_unavailable", "message" -> message)
  val sessionNotYours: HttpProblem                   = refuse(403, "session_not_yours")
  /** The original's text, naming the request the session awaits (FR-015). */
  def sessionActive(approvalId: String, tool: String): HttpProblem =
    refuse(409, "Session already active", "approvalId" -> approvalId, "action_name" -> tool)
  def conversationEnded(message: String): HttpProblem = refuse(409, "conversation_ended", "message" -> message)
  def tooManyTurns(retryAfter: Long): HttpProblem    = refuse(429, "too_many_turns", "retryAfter" -> retryAfter.toString)

  val invalidAction: HttpProblem   = refuse(422, "invalid_action")
  val noPendingAction: HttpProblem = refuse(404, "no_pending_action")
  val alreadyDecided: HttpProblem  = refuse(409, "already_decided")
  val notFound: HttpProblem        = refuse(404, "not_found")
