package io.eitheror.casino.publicapi.features

/** features/public-api/contract.feature, bound by T089. */
class ContractFeatures extends PublicApiFeatures("contract.feature"):
  override protected def ranElsewhere: Map[String, String] = PublicApiFeatures.pending("T089", "contract.feature")
