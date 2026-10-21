package io.eitheror.casino.pspgateway.it

import io.eitheror.casino.pspgateway.sessions.Provider

import java.util.UUID
import scala.concurrent.duration.*

/**
 * FR-023: `/status` shows each provider's health over its last calls and whether it is configured. A
 * service of its own (build.sbt): T365 is pointed at a port nobody listens on, so its calls go
 * unanswered, which the window counts as a provider's silence, and Gigadat is given no base URL.
 */
class StatusSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 5.minutes

  private lazy val kit =
    val port = Kit.freePort()
    val stub = PspStub.start(port)
    val urls = stub.baseUrls.updated("T365_BASE_URL", s"http://127.0.0.1:${Kit.freePort()}") - "GIGADAT_BASE_URL"
    Kit(urls, port, Some(stub))

  override def beforeAll(): Unit = kit: Unit
  override def afterAll(): Unit  = kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    Provider.values.foreach(kit.credential("sb-1", _))

  private def status(headers: Seq[(String, String)] = kit.asService("local", "ingest")): Reply =
    kit.request("GET", "/status", None, headers)

  private def provider(r: Reply, p: Provider) = r.json.path("providers").path(p.wire)

  test("the project's caller reads nine providers, each with its state, its calls, its failures, its p95 and whether it is configured") {
    val r = status()
    assertEquals(r.status, 200, r.text)
    assertEquals(r.json.path("providers").size, 9, r.text)
    Provider.values.foreach { p =>
      val n = provider(r, p)
      Vector("state", "calls", "failed", "p95Seconds", "configured").foreach(f => assert(n.has(f), s"${p.wire} has no $f: $n"))
    }
    assert(r.json.has("undelivered") && r.json.has("refused"), r.text)
  }

  test("a provider that does not answer is degraded, then down; the others stay up; one with no base URL is not configured") {
    assertEquals(kit.initiate(UUID.randomUUID().toString, Provider.UnityFinance).status, 200)
    kit.initiate(UUID.randomUUID().toString, Provider.T365): Unit
    assertEquals(provider(status(), Provider.T365).path("state").asText, "degraded")
    (1 to 4).foreach(_ => kit.initiate(UUID.randomUUID().toString, Provider.T365))
    val r = status()
    assertEquals(provider(r, Provider.T365).path("state").asText, "down", r.text)
    assertEquals(provider(r, Provider.T365).path("failed").asInt, 5, r.text)
    assertEquals(provider(r, Provider.UnityFinance).path("state").asText, "up", r.text)
    assert(provider(r, Provider.UnityFinance).path("calls").asInt >= 1, r.text)
    assertEquals(provider(r, Provider.Gigadat).path("configured").asBoolean, false, r.text)
    assertEquals(provider(r, Provider.DreamzPay).path("configured").asBoolean, true, r.text)
  }

  test("a payments operator at platform scope reads it through the ingress; another project's service and no token do not") {
    assertEquals(status(kit.asOperator()).status, 200)
    assertEquals(status(kit.asPayouts("goldvibe")).status, 403)
    assertEquals(status(kit.asBrowser).status, 401)
  }
