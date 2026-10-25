package io.eitheror.casino.ingest.keys

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.ingest.merchant.MerchantClient
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/**
 * At start, `merchant`'s active keys into the index (FR-004's "only at start"; research R3): one `issue` per
 * key. A failure is logged with its cause and the service starts anyway, since `KeyFacts` builds the index
 * from the topic. The count is logged, never a digest.
 */
object KeyRefresh:
  private val log = LoggerFactory.getLogger(getClass)

  /** The number of keys issued; zero when `merchant` could not say. */
  def run(merchant: MerchantClient, components: ComponentClient, now: Instant, within: FiniteDuration = 30.seconds): Int =
    Try(Await.result(merchant.keys(), within)) match
      case Failure(e) =>
        log.warn("the key index was not refreshed from merchant ({}); the payments.merchants facts build it", e.getClass.getSimpleName)
        0
      case Success(keys) =>
        val issued = keys.count { k =>
          Try(KeyIndex.record(components, k.keyId,
            components.forKeyValueEntity(EntityId(k.keyId)).call(Key.issue).invoke(Issue(k.digest, k.subBrandId, now)))).isSuccess
        }
        log.info("the key index was refreshed from merchant: {} keys", issued)
        issued
