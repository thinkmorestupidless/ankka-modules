package io.eitheror.casino.kyc.it

import io.eitheror.casino.kyc.Bucket
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8

/**
 * The installation's object store in a container, as ankka's own storage suites start it
 * (`runtime/.../storage/GarageContainer.scala`): one node, the admin API making the bucket and its key.
 */
final class GarageContainer private (container: GenericContainer[?]):
  import GarageContainer.*

  val s3: String    = s"http://${container.getHost}:${container.getMappedPort(3900)}"
  private val admin = s"http://${container.getHost}:${container.getMappedPort(3903)}"
  private val http  = HttpClient.newHttpClient()

  /** A bucket with a key that may read, write and own it, as the platform gives a service (`ANKKA_S3_*`). */
  def bucket(name: String): Bucket =
    val id  = field(post("/v2/CreateBucket", s"""{"globalAlias":"$name"}"""), "id")
    val key = post("/v2/CreateKey", s"""{"name":"$name"}""")
    val (access, secret) = (field(key, "accessKeyId"), field(key, "secretAccessKey"))
    post("/v2/AllowBucketKey", s"""{"bucketId":"$id","accessKeyId":"$access","permissions":{"read":true,"write":true,"owner":true}}"""): Unit
    Bucket(s3, s3, Region, name, access, secret)

  def stop(): Unit = container.stop()

  private def post(path: String, json: String): String =
    val response = http.send(
      HttpRequest.newBuilder(URI.create(admin + path)).header("Authorization", s"Bearer $Token")
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
      HttpResponse.BodyHandlers.ofString()
    )
    if response.statusCode / 100 != 2 then throw IllegalStateException(s"garage admin $path: ${response.statusCode}")
    response.body

object GarageContainer:
  val Region        = "garage"
  private val Image = "dxflrs/garage:v2.3.0"
  private val Token = "kyc-suite"

  private val Config =
    """metadata_dir = "/var/lib/garage/meta"
      |data_dir = "/var/lib/garage/data"
      |db_engine = "lmdb"
      |replication_factor = 1
      |rpc_bind_addr = "[::]:3901"
      |
      |[s3_api]
      |s3_region = "garage"
      |api_bind_addr = "[::]:3900"
      |
      |[admin]
      |api_bind_addr = "[::]:3903"
      |""".stripMargin

  def start(): GarageContainer =
    val c = new GenericContainer(DockerImageName.parse(Image))
    c.withCopyToContainer(Transferable.of(Config.getBytes(UTF_8)), "/etc/garage.toml")
    c.withEnv("GARAGE_RPC_SECRET", "0" * 64)
    c.withEnv("GARAGE_ADMIN_TOKEN", Token)
    c.withCommand("/garage", "server", "--single-node")
    c.withExposedPorts(3900, 3903)
    c.waitingFor(Wait.forHttp("/health").forPort(3903).forStatusCode(200))
    c.start()
    new GarageContainer(c)

  private def field(json: String, name: String): String =
    s""""$name"\\s*:\\s*"([^"]+)"""".r.findFirstMatchIn(json).map(_.group(1))
      .getOrElse(throw IllegalStateException(s"garage admin: no $name in its answer"))
