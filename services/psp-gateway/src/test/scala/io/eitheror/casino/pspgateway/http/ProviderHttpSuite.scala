package io.eitheror.casino.pspgateway.http

import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.pspgateway.providers.{CallFailure, Outbound}
import io.eitheror.casino.pspgateway.sessions.Provider

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** FR-023, R11: a provider call is bounded, and each provider's window says how it has been answering. */
class ProviderHttpSuite extends munit.FunSuite with LogCapturing:

  private val status = AtomicInteger(200)
  private val server = FunFixture[(HttpServer, Int)](
    _ =>
      val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      s.createContext("/silent", ex => { Thread.sleep(3000); ex.sendResponseHeaders(200, -1); ex.close() })
      s.createContext("/answer", ex =>
        val body = s"""{"got":"${String(ex.getRequestBody.readAllBytes, UTF_8)}","ct":"${ex.getRequestHeaders.getFirst("Content-Type")}"}"""
          .getBytes(UTF_8)
        ex.getResponseHeaders.add("X-Reply", "yes")
        ex.sendResponseHeaders(status.get, body.length.toLong)
        ex.getResponseBody.write(body)
        ex.close())
      s.createContext("/redirect", ex => { ex.getResponseHeaders.add("Location", "/answer"); ex.sendResponseHeaders(302, -1); ex.close() })
      s.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
      s.start()
      (s, s.getAddress.getPort),
    (s, _) => s.stop(0)
  )

  server.test("a call answers the status, the headers by lower-case name and the body") { (_, port) =>
    status.set(200)
    val http = ProviderHttp(Duration.ofSeconds(2))
    val a = http.call(Provider.T365, Outbound("POST", s"http://127.0.0.1:$port/answer", body = Some("x=1"),
      contentType = Some("application/x-www-form-urlencoded"))).toOption.get
    assertEquals(a.status, 200)
    assertEquals(a.header("X-Reply"), Some("yes"))
    assert(a.body.contains(""""got":"x=1""""), a.body)
    assert(a.body.contains("application/x-www-form-urlencoded"), a.body)
  }

  server.test("the timeout holds against a silent provider") { (_, port) =>
    val http = ProviderHttp(Duration.ofMillis(300))
    val started = System.nanoTime
    assertEquals(http.call(Provider.T365, Outbound.get(s"http://127.0.0.1:$port/silent")), Left(CallFailure.TimedOut))
    assert((System.nanoTime - started) / 1_000_000 < 2500, "bounded well before the provider would have answered")
  }

  server.test("no redirect is followed") { (_, port) =>
    assertEquals(ProviderHttp(Duration.ofSeconds(2)).call(Provider.T365, Outbound.get(s"http://127.0.0.1:$port/redirect")).map(_.status), Right(302))
  }

  test("an unreachable provider is unreachable") {
    assert(ProviderHttp(Duration.ofSeconds(1)).call(Provider.T365, Outbound.get("http://127.0.0.1:1/x")).left.exists(_.isInstanceOf[CallFailure.Unreachable]))
  }

  server.test("the window reaches degraded, then down, then up again") { (_, port) =>
    val http = ProviderHttp(Duration.ofSeconds(2))
    def calls(n: Int, s: Int) = { status.set(s); (1 to n).foreach(_ => http.call(Provider.Cozeni, Outbound.get(s"http://127.0.0.1:$port/answer"))) }
    calls(3, 200)
    assertEquals(http.health(Provider.Cozeni).state, "up")
    calls(5, 500)
    assertEquals(http.health(Provider.Cozeni).state, "down", "the last 5 failed")
    calls(1, 200)
    assertEquals(http.health(Provider.Cozeni).state, "degraded", "5 of 9 failed")
    calls(12, 200)
    assertEquals(http.health(Provider.Cozeni).state, "up")
    assertEquals(http.health(Provider.Cozeni).calls, 20, "the window is the last 20")
    assertEquals(http.health(Provider.T365).state, "up", "each provider has its own window")
  }

  test("the window's states and p95, from samples") {
    assertEquals(ProviderHttp.health(Vector.empty).state, "up")
    assertEquals(ProviderHttp.health(Vector.fill(6)((true, 0.1)) ++ Vector.fill(7)((false, 1.0))).state, "down")
    val degraded = ProviderHttp.health(Vector((false, 1.0), (true, 0.1), (false, 1.0), (false, 1.0), (true, 0.1)))
    assertEquals(degraded.state, "degraded")
    assertEquals(degraded.failed, 3)
    assertEquals(ProviderHttp.health((1 to 20).toVector.map(i => (true, i.toDouble))).p95Seconds, 19.0)
  }
