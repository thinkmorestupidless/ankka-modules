package io.eitheror.casino.backoffice.session

import com.sun.net.httpserver.HttpServer
import io.eitheror.casino.backoffice.Settings

import java.net.{InetSocketAddress, URI}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** KeycloakRealmClient against a realm served here, which answers each path as the test says. */
class RealmClientSuite extends munit.FunSuite:

  private def realm(answers: Map[String, Int])(body: (KeycloakRealmClient, () => Vector[String]) => Unit): Unit =
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val asked  = ConcurrentLinkedQueue[String]()
    server.createContext("/", exchange => {
      val path = exchange.getRequestURI.getPath
      asked.add(path)
      exchange.getRequestBody.readAllBytes(): Unit
      val status = answers.collectFirst { case (suffix, s) if path.endsWith(suffix) => s }.getOrElse(404)
      exchange.sendResponseHeaders(status, -1)
      exchange.close()
    })
    server.start()
    try
      val base     = URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/realms/eitheror")
      val settings = Settings("goldvibe", base, "backoffice-goldvibe", URI.create("https://bo.example"), "s3cret")
      body(KeycloakRealmClient(settings), () => asked.asScala.toVector)
    finally server.stop(0)

  test("a failed revocation still ends the realm's session, and the revocation's failure is answered (review RealmClient.scala:81)") {
    realm(Map("/revoke" -> 503, "/logout" -> 204)) { (client, asked) =>
      assertEquals(client.revoke(Secret("refresh")), Left(RealmRefusal.Unavailable("revoke: 503")))
      assertEquals(asked().map(_.split('/').last), Vector("revoke", "logout"))
    }
  }

  test("both done is done; a failed logout after a good revocation is answered") {
    realm(Map("/revoke" -> 200, "/logout" -> 204)) { (client, _) =>
      assertEquals(client.revoke(Secret("refresh")), Right(()))
    }
    realm(Map("/revoke" -> 200, "/logout" -> 502)) { (client, _) =>
      assertEquals(client.revoke(Secret("refresh")), Left(RealmRefusal.Unavailable("logout: 502")))
    }
  }
