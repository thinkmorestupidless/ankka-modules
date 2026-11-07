package io.eitheror.casino.programme.brands

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.Wiring
import io.eitheror.casino.programme.accounts.Secrets
import io.eitheror.casino.programme.links.{AddKey, SigningKeys}
import io.eitheror.casino.programme.tags.TagSigner

import java.time.Instant
import java.util.UUID

/**
 * What the programme needs before its first fact (data-model.md *Timed actions*): each brand of
 * `PROGRAMME_BRANDS` seeded, the email digest's key, and a signing key when there is none. Every part
 * is idempotent, so the timer running again (a retry, a restart) changes nothing.
 */
object Seeding:
  def run(components: ComponentClient, store: SecretStore, brands: Vector[String], now: Instant): Unit =
    brands.foreach { slug =>
      components.forKeyValueEntity(EntityId(slug)).call(BrandProgramme.seed).invoke(SeedBrand(slug, now)): Unit
    }
    Secrets(store).makeEmailIndexKey().left.foreach(u => throw IllegalStateException(s"the secret store is unavailable: ${u.reason}"))
    val keys = components.forKeyValueEntity(EntityId(SigningKeys.Id))
    if keys.call(SigningKeys.get).invoke().live.isEmpty then
      val kid          = UUID.randomUUID().toString
      val (x, private8) = TagSigner.generate()
      store.put(Secrets.signingKey(kid), private8)
      keys.call(SigningKeys.add).invoke(AddKey(kid, x, now)): Unit

/** The single timer `seed`, set at every start. */
final class Seed(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    Seeding.run(context.componentClient, context.secrets, Wiring.settings.get().brands, context.clock.instant())
    effects.done()

object Seed extends TimedAction.Companion[Seed](ComponentId("seed")):
  val TimerName = "seed"

  def create(context: TimedActionContext) = new Seed(context)
  val seed = handler("seed")(_.run)
