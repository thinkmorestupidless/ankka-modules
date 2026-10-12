package io.eitheror.casino.simulator.brands

import io.eitheror.casino.simulator.core.{Attempt, Outbound, Wiring}
import io.eitheror.casino.simulator.st8.{Keys, Signer}

import java.io.ByteArrayInputStream
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration as JDuration
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.{SSLContext, TrustManagerFactory}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * The outside path to a brand (research R3): the JDK client to the brand's base address, signed,
 * with the provider's wait as the request's timeout. Never ankka's service client, which would
 * present the simulator's certificate as a platform identity (FR-024).
 *
 * A brand entry may name a CA to trust (`caPem`: a local installation's gateway certificate
 * authority, as PEM or as base64 of the PEM). Reaching a gateway hostname from inside a kind cluster
 * is the installation's to arrange, not this client's (`installation/overlays/dev-kind/gateway-dns.sh`).
 */
final class Sender(table: BrandTable, keys: Keys) extends Outbound:

  private val clients = ConcurrentHashMap[String, HttpClient]()

  private def client(brand: Brand): HttpClient =
    clients.computeIfAbsent(brand.slug, _ => {
      val builder = HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1)
      brand.caPem.foreach(pem => builder.sslContext(trusting(pem)))
      builder.build()
    })

  def send(slug: String, route: String, body: Array[Byte], key: String, answerWait: FiniteDuration, attempt: Int): Attempt =
    val brand = table(slug)
    val (keyId, signature) = keys.signingKey(key) match
      case Some((kid, privateKey)) => kid -> Signer.sign(body, privateKey)
      case None                    => "none" -> ""
    val target  = URI.create(brand.baseAddress.toString.stripSuffix("/") + route)
    val builder = HttpRequest.newBuilder(target)
      .timeout(JDuration.ofMillis(answerWait.toMillis))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofByteArray(body))
    if signature.nonEmpty then builder.header(Signer.Header, signature): Unit
    val sentAt  = Wiring.now()
    val started = System.nanoTime()
    def took    = (System.nanoTime() - started) / 1000000
    try
      val response = client(brand).send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
      val kind     = route.split('/').last
      val answer   = Wiring.protocol.redact(kind, response.body())
      val reading  = Wiring.protocol.read(response.statusCode(), response.body())
      Attempt(attempt, keyId, signature, sentAt, took, Some(response.statusCode()), reading.status, reading.balance, reading.currency, Some(String(answer, "UTF-8")), None)
    catch
      case _: java.net.http.HttpTimeoutException =>
        Attempt(attempt, keyId, signature, sentAt, took, None, None, None, None, None, Some(s"no answer within ${answerWait.toMillis}ms"))
      case NonFatal(e) =>
        Attempt(attempt, keyId, signature, sentAt, took, None, None, None, None, None, Some(s"brand unreachable: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"))

  private def trusting(caPem: String): SSLContext =
    val pem =
      if caPem.contains("-----BEGIN") then caPem
      else String(java.util.Base64.getMimeDecoder.decode(caPem.trim), "UTF-8")
    val certificates = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem.getBytes("UTF-8")))
    val store = KeyStore.getInstance(KeyStore.getDefaultType)
    store.load(null, null)
    certificates.toArray.zipWithIndex.foreach((c, i) => store.setCertificateEntry(s"ca-$i", c.asInstanceOf[java.security.cert.Certificate]))
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    factory.init(store)
    val context = SSLContext.getInstance("TLS")
    context.init(null, factory.getTrustManagers, null)
    context
