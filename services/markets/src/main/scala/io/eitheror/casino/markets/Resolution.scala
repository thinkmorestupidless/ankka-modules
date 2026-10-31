package io.eitheror.casino.markets

import com.thinkmorestupidless.predictions.markets.{MarketId, OperatorId}
import com.thinkmorestupidless.predictions.markets.components.{ResolutionAnswer, ResolutionPort}

/**
 * The resolution port (spec 023 R6): every question is allowed, because the operator's route has
 * already checked the markets permission on the caller's token, which the port never sees. A command
 * reaches the library only through that route, or as the library's own timer.
 */
object Resolution extends ResolutionPort:
  def mayDraft(operator: OperatorId): ResolutionAnswer                     = ResolutionAnswer.Allow
  def mayApprove(operator: OperatorId, market: MarketId): ResolutionAnswer = ResolutionAnswer.Allow
  def mayResolve(operator: OperatorId, market: MarketId): ResolutionAnswer = ResolutionAnswer.Allow
  def mayVoid(operator: OperatorId, market: MarketId): ResolutionAnswer    = ResolutionAnswer.Allow
  def mayClose(operator: OperatorId, market: MarketId): ResolutionAnswer   = ResolutionAnswer.Allow
  def maySuspend(operator: OperatorId, market: MarketId): ResolutionAnswer = ResolutionAnswer.Allow
