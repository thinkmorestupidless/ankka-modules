package io.eitheror.casino.provider.brands

import java.net.URI

/**
 * One brand a provider serves, as the outbound path needs it (spec 020 contracts/brand-config.md):
 * where its provider routes are, whether it is a test brand, its waits, the CA it is reached under,
 * where an original adapter takes the key, and the `site` its launches name.
 */
final case class Brand(
    slug: String,
    baseAddress: URI,
    testBrand: Boolean,
    providerWaitMillis: Long,
    keyRefreshBoundSeconds: Long,
    caPem: Option[String],
    registerKeyAt: Option[URI],
    site: String
)
