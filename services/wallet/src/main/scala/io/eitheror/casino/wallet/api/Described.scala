package io.eitheror.casino.wallet.api

import com.thinkmorestupidless.ankka.http.openapi.Description
import io.eitheror.casino.wallet.wire.RefusalBody

/**
 * What the wallet's player routes take and answer, for their descriptions (spec 031,
 * contracts/descriptions.md). The answers are the routes' own types (`MajorBalance`,
 * `MajorTransactions`, `RestrictedFundsStatus`); the refusal body is `RefusalBody`, as `Wire.refusal`
 * writes it.
 */
private[api] object Described:

  /** The shape of every refusal a player route names. */
  val refusals: Description = Description.none.refusalShape[RefusalBody]
