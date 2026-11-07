package io.eitheror.casino.engagement.events

import java.math.{BigDecimal, RoundingMode}

/** Minor units to the major units Customer.io is sent: two decimals, `HALF_UP`, as `S/CustomerIOClient.scala` rounds. */
object Amounts:
  def major(minor: Long): String = BigDecimal.valueOf(minor, 2).setScale(2, RoundingMode.HALF_UP).toPlainString
