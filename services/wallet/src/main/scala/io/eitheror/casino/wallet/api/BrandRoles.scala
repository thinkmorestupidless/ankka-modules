package io.eitheror.casino.wallet.api

import com.thinkmorestupidless.ankka.sdk.{ServiceClients, ServiceIdentityMismatch, ServiceUnanswered, ServiceUnresolvable}
import io.eitheror.casino.contract.access.RoleBags

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/**
 * The brand's roles as its `players` service keeps them (spec 030 contracts/roles.md, *How a service opens
 * a bag*): one `RoleBags` per process, reading `GET /v1/casino/brands/<brand>/players/roles` as this
 * service every 10 seconds, started the first time an endpoint asks for it.
 */
object BrandRoles:
  val Players = "players"

  def path(brand: String): String = s"/v1/casino/brands/$brand/players/roles"

  /** One read: the body, or why there is none. */
  def fetch(services: ServiceClients, brand: String): () => Either[String, String] = () =>
    try
      val r = services(Players).request("GET", path(brand), None, None)
      if r.status == 200 then Right(String(r.body, "UTF-8")) else Left(s"players answered ${r.status}")
    catch
      case e: ServiceUnanswered       => Left(e.reason)
      case e: ServiceUnresolvable     => Left(e.reason)
      case e: ServiceIdentityMismatch => Left(e.detail)
      case NonFatal(e)                => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

  private val started = AtomicReference[Option[RoleBags]](None)

  /** The process's copy, started on first use. */
  def of(services: ServiceClients, brand: String): RoleBags = synchronized {
    started.get.getOrElse {
      val bags = RoleBags(fetch(services, brand)).start()
      started.set(Some(bags))
      bags
    }
  }
