package io.eitheror.casino.rewards.api

import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.contract.access.RoleBags
import io.eitheror.casino.rewards.Settings
import io.eitheror.casino.rewards.clients.{Neighbour, Reply}

import java.util.concurrent.atomic.AtomicReference

/**
 * The brand's roles as its `players` service keeps them (spec 030 contracts/roles.md, *How a service opens
 * a bag*): one `RoleBags` per process, reading `GET /v1/casino/brands/<brand>/players/roles` as this
 * service, through the service's own `Neighbour`, every 10 seconds, started the first time an endpoint asks for it.
 */
object BrandRoles:
  def path(brand: String): String = s"/v1/casino/brands/$brand/players/roles"

  /** One read: the body, or why there is none (`Neighbour` answers a failure to reach `players` as a reason). */
  def fetch(services: ServiceClients, settings: Settings): () => Either[String, String] =
    val players = Neighbour(services, settings.players)
    () =>
      players.get(path(settings.brand)) match
        case Reply.Answered(200, _, text) => Right(text)
        case Reply.Answered(status, _, _) => Left(s"players answered $status")
        case Reply.Unreachable(why)       => Left(why)

  private val started = AtomicReference[Option[RoleBags]](None)

  /** The process's copy, started on first use. */
  def of(services: ServiceClients, settings: Settings): RoleBags = synchronized {
    started.get.getOrElse {
      val bags = RoleBags(fetch(services, settings)).start()
      started.set(Some(bags))
      bags
    }
  }
