package io.eitheror.casino.provider.edge

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.*

/**
 * Who the provider's operator routes admit (research, *Found during implementation*, review
 * `platform_admin`): `games` is the installation operator; `platform_admin` reads the brand table
 * and has no other act here.
 */
class RolesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private def kit = ProviderTestKit.kit

  private val admin = ProviderTestKit.staffAs("platform-admin", "platform_admin")

  override def beforeAll(): Unit = kit.start()

  test("platform_admin reads the brand table") {
    assertEquals(kit.asStaff("GET", "/v1/provider/brands", admin).status, 200)
  }

  test("platform_admin starts no key rotation, removes no game, writes no brand entry and reads no keys or registry") {
    assertEquals(kit.asStaff("POST", "/v1/provider/keys/start", admin).status, 403)
    assertEquals(kit.asStaff("GET", "/v1/provider/keys", admin).status, 403)
    assertEquals(kit.asStaff("GET", "/v1/provider/registry", admin).status, 403)
    assertEquals(kit.asStaff("DELETE", "/v1/provider/registry/test-slot", admin).status, 403)
    assertEquals(kit.asStaff("PUT", "/v1/provider/brands/goldvibe", admin, Some(kit.entryBody("goldvibe", kit.brand.address))).status, 403)
  }
