package io.eitheror.casino.reporting

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import io.eitheror.casino.reporting.intake.*

/**
 * Every component of the service (plan.md's structure), registered by the service and by its test kit alike. The
 * consumers are built for the settings' generation (research R8).
 */
object ReportingService:
  def descriptors(settings: Settings): Seq[ComponentDescriptor] = Seq(
    WalletBook.descriptor,
    MoneyFacts(settings).descriptor,
    Payment.descriptor,
    PaymentsFacts(settings).descriptor
  )
