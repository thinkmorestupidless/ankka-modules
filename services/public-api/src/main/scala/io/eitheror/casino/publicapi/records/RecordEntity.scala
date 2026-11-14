package io.eitheror.casino.publicapi.records

import com.thinkmorestupidless.ankka.sdk.KeyValueEntity

/**
 * A record of data-model.md: a key value entity whose state remembers the last 64 facts it applied (`FactId`), so a
 * fact delivered again changes nothing. `once` applies a change for a fact not yet seen and answers whether the state
 * changed.
 */
abstract class RecordEntity[S] extends KeyValueEntity[S]:
  protected def applied(s: S): Vector[String]
  protected def remembered(s: S, ids: Vector[String]): S

  protected def once(factId: String)(change: S => S): Effect[Boolean] =
    if applied(currentState).contains(factId) then effects.reply(false)
    else
      val next = remembered(change(currentState), FactId.remember(applied(currentState), factId))
      effects.updateState(next).thenReply(_ => true)
