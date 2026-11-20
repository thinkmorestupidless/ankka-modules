package io.eitheror.casino.payouts.api

import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.contract.access.RoleBags
import io.eitheror.casino.payouts.clients.{Neighbour, Reply}

import java.util.concurrent.atomic.AtomicReference

/**
 * The brand's roles as its `players` service keeps them (spec 030 contracts/roles.md, *How a service opens
 * a bag*): one `RoleBags` per process, reading `GET /v1/casino/brands/<brand>/players/roles` as this
 * service every 10 seconds, started the first time an endpoint asks for it.
 */
object BrandRoles:
  val Players = "players"

  def path(brand: String): String = s"/v1/casino/brands/$brand/players/roles"

  /** One read: the body, or why there is none. */
  def fetch(services: ServiceClients, brand: String): () => Either[String, String] =
    val players = Neighbour(services, Players)
    () => players.get(path(brand)) match
      case Reply.Answered(200, _, text) => Right(text)
      case Reply.Answered(status, _, _) => Left(s"players answered $status")
      case Reply.Unreachable(reason)    => Left(reason)

  private val started = AtomicReference[Option[RoleBags]](None)

  /** The process's copy, started on first use. */
  def of(services: ServiceClients, brand: String): RoleBags = synchronized {
    started.get.getOrElse {
      val bags = RoleBags(fetch(services, brand)).start()
      started.set(Some(bags))
      bags
    }
  }
