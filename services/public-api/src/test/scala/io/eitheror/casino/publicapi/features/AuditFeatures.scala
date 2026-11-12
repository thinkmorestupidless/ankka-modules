package io.eitheror.casino.publicapi.features

/** features/public-api/audit.feature, bound by T065. */
class AuditFeatures extends PublicApiFeatures("audit.feature"):
  override protected def ranElsewhere: Map[String, String] = PublicApiFeatures.pending("T065", "audit.feature")
