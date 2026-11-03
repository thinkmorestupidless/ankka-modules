package io.eitheror.casino.rewards.steps

import com.github.plokhotnyuk.jsoniter_scala.macros.named
import io.eitheror.casino.rewards.clients

/**
 * What the wallet answered a step (research R5), as the step hands it to the reward: the route's own answer, a
 * refusal with the wallet's code, or `NotOpen`, a timer's step the wallet refused with `NO_ACTIVE_BONUS` or
 * `NOT_PENDING_CAP`: the record is not open, so the reward moves as the timer would and no money moves (FR-015).
 * Each case is stored under its wire name, never its Scala name.
 */
sealed trait WalletAnswer

object WalletAnswer:
  @named("awarded") final case class Awarded(awarded: clients.Awarded) extends WalletAnswer
  @named("forfeited") final case class Forfeited(forfeited: clients.Forfeited) extends WalletAnswer
  @named("cancelled") final case class Cancelled(cancelled: clients.Cancelled) extends WalletAnswer
  @named("cap-closed") final case class CapClosed(closed: clients.CapClosed) extends WalletAnswer
  @named("switched") final case class Switched(switched: clients.Switched) extends WalletAnswer
  @named("credited") final case class Credited(credited: clients.Credited) extends WalletAnswer
  /** A timer's step on a record that is not open: move the reward, no money (FR-015). */
  @named("not-open") final case class NotOpen(code: String) extends WalletAnswer
  /** The wallet's refusal, its status and code as it answered (a 4xx naming one). */
  @named("refused") final case class Refused(status: Int, code: String, message: String) extends WalletAnswer
